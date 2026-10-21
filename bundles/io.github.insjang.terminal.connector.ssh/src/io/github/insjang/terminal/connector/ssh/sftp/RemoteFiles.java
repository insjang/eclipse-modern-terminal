/*******************************************************************************
 * Copyright (c) 2026 insjang and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package io.github.insjang.terminal.connector.ssh.sftp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.AttributeRepository.AttributeKey;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClient.Attributes;
import org.apache.sshd.sftp.client.SftpClient.DirEntry;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;

/**
 * SFTP operations on the session of an SSH terminal, without UI. One {@link SftpClient} per session,
 * opened on first use; it closes with the session (or {@link #close()}). Remote paths use '/'.
 */
public final class RemoteFiles implements AutoCloseable {
	private static final AttributeKey<RemoteFiles> KEY = new AttributeKey<>();
	private static final int BUFFER = 32 * 1024;

	/** A directory entry; {@code dir} follows symbolic links. Times are epoch milliseconds. */
	public record Entry(String name, String path, boolean dir, boolean link, long size, long modified, int permissions) {
		static Entry of(String path, Attributes a, boolean dir) {
			return new Entry(RemoteFiles.name(path), path, dir, a.isSymbolicLink(), a.getSize(),
					a.getModifyTime() != null ? a.getModifyTime().toMillis() : 0, a.getPermissions());
		}
	}

	private final ClientSession session;
	private final SftpClient client;

	private RemoteFiles(ClientSession session, SftpClient client) {
		this.session = session;
		this.client = client;
	}

	/** The SFTP client of the session, opened if there is none (or it was closed). */
	public static RemoteFiles of(ClientSession session) throws IOException {
		synchronized (session) {
			RemoteFiles files = session.getAttribute(KEY);
			if (files == null || !files.isOpen()) {
				files = new RemoteFiles(session, SftpClientFactory.instance().createSftpClient(session));
				session.setAttribute(KEY, files);
			}
			return files;
		}
	}

	public ClientSession getSession() {
		return session;
	}

	public boolean isOpen() {
		return client.isOpen() && session.isOpen();
	}

	@Override
	public void close() {
		try {
			client.close();
		} catch (IOException e) {
			// closing anyway
		}
	}

	/**
	 * The server's own name, as {@code hostname} prints it: what shells put in OSC 7 ($HOSTNAME). Null
	 * if it cannot be run (no exec channel, no reply within 5 seconds).
	 */
	public String hostName() {
		try (ChannelExec channel = session.createExecChannel("hostname")) { //$NON-NLS-1$
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			channel.setOut(out);
			channel.setErr(OutputStream.nullOutputStream());
			channel.open().verify(5, TimeUnit.SECONDS);
			channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), TimeUnit.SECONDS.toMillis(5));
			String name = out.toString(StandardCharsets.UTF_8).trim();
			return name.isEmpty() || name.contains("\n") ? null : name; //$NON-NLS-1$
		} catch (IOException e) {
			return null;
		}
	}

	/** The absolute path of the login directory. */
	public String home() throws IOException {
		return client.canonicalPath("."); //$NON-NLS-1$
	}

	/** The entries of a directory (no "." and ".."), folders first, then by name. */
	public List<Entry> list(String dir) throws IOException {
		List<Entry> entries = new ArrayList<>();
		for (DirEntry e : client.readDir(dir)) {
			String name = e.getFilename();
			if (name.equals(".") || name.equals("..")) { //$NON-NLS-1$ //$NON-NLS-2$
				continue;
			}
			Attributes a = e.getAttributes();
			String path = join(dir, name);
			boolean isDir = a.isDirectory();
			if (a.isSymbolicLink()) {
				try {
					isDir = client.stat(path).isDirectory();
				} catch (IOException broken) {
					// a dangling link is listed as a file
				}
			}
			entries.add(Entry.of(path, a, isDir));
		}
		entries.sort(Comparator.comparing((Entry e) -> !e.dir()).thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
		return entries;
	}

	/** The entry of a path (links followed), or <code>null</code> if there is none. */
	public Entry stat(String path) throws IOException {
		try {
			Attributes a = client.stat(path);
			return Entry.of(path, a, a.isDirectory());
		} catch (SftpException e) {
			if (e.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE) {
				return null;
			}
			throw e;
		}
	}

	public void mkdir(String path) throws IOException {
		client.mkdir(path);
	}

	public void rename(String from, String to) throws IOException {
		client.rename(from, to);
	}

	/** Deletes a file, a link or a folder with all its content (links are not followed). */
	public void delete(String path, IProgressMonitor monitor) throws IOException {
		checkCanceled(monitor);
		if (client.lstat(path).isDirectory()) {
			for (DirEntry e : client.readDir(path)) {
				String name = e.getFilename();
				if (!name.equals(".") && !name.equals("..")) { //$NON-NLS-1$ //$NON-NLS-2$
					delete(join(path, name), monitor);
				}
			}
			client.rmdir(path);
		} else {
			client.remove(path);
		}
		monitor.worked(1);
	}

	/**
	 * Copies a remote file or folder into a local folder. {@code overwrite} is asked for each file
	 * that exists already: false skips it, {@link OperationCanceledException} stops.
	 */
	public void download(String remote, Path localDir, Predicate<Path> overwrite, IProgressMonitor monitor)
			throws IOException {
		Entry e = stat(remote);
		if (e == null) {
			throw new IOException("No such file: " + remote); //$NON-NLS-1$
		}
		download(e, localDir.resolve(localName(e.name())), overwrite, monitor);
	}

	private void download(Entry e, Path local, Predicate<Path> overwrite, IProgressMonitor monitor) throws IOException {
		checkCanceled(monitor);
		if (e.dir()) {
			Files.createDirectories(local);
			for (Entry child : list(e.path())) {
				download(child, local.resolve(localName(child.name())), overwrite, monitor);
			}
		} else if (!Files.exists(local) || overwrite.test(local)) {
			monitor.subTask(e.path());
			try (InputStream in = client.read(e.path()); OutputStream out = Files.newOutputStream(local)) {
				copy(in, out, monitor);
			} catch (IOException | OperationCanceledException x) {
				Files.deleteIfExists(local); // no partial file
				throw x;
			}
		}
	}

	/** Downloads one file to a local path (replaced), returning the remote entry as read. */
	public Entry get(String remote, Path local, IProgressMonitor monitor) throws IOException {
		Entry e = stat(remote);
		if (e == null) {
			throw new IOException("No such file: " + remote); //$NON-NLS-1$
		}
		download(e, local, p -> true, monitor);
		return e;
	}

	/**
	 * Copies a local file or folder into a remote folder. {@code overwrite} is asked for each file
	 * that exists already: false skips it, {@link OperationCanceledException} stops.
	 */
	public void upload(Path local, String remoteDir, Predicate<String> overwrite, IProgressMonitor monitor)
			throws IOException {
		String remote = join(remoteDir, local.getFileName().toString());
		checkCanceled(monitor);
		if (Files.isDirectory(local)) {
			Entry existing = stat(remote);
			if (existing == null) {
				client.mkdir(remote);
			} else if (!existing.dir()) {
				throw new IOException("Not a folder: " + remote); //$NON-NLS-1$
			}
			List<Path> children;
			try (Stream<Path> s = Files.list(local)) {
				children = s.sorted().toList();
			}
			for (Path child : children) {
				upload(child, remote, overwrite, monitor);
			}
		} else if (stat(remote) == null || overwrite.test(remote)) {
			put(local, remote, monitor);
		}
	}

	/** Uploads one file to a remote path (replaced), returning the remote entry as written. */
	public Entry put(Path local, String remote, IProgressMonitor monitor) throws IOException {
		monitor.subTask(remote);
		try (InputStream in = Files.newInputStream(local); OutputStream out = client.write(remote)) {
			copy(in, out, monitor);
		} catch (OperationCanceledException x) {
			try {
				client.remove(remote); // no partial file
			} catch (IOException ignored) {
				// leave it
			}
			throw x;
		}
		return stat(remote);
	}

	private static void copy(InputStream in, OutputStream out, IProgressMonitor monitor) throws IOException {
		byte[] buffer = new byte[BUFFER];
		int n;
		while ((n = in.read(buffer)) != -1) {
			checkCanceled(monitor);
			out.write(buffer, 0, n);
			monitor.worked(n);
		}
	}

	private static void checkCanceled(IProgressMonitor monitor) {
		if (monitor.isCanceled()) {
			throw new OperationCanceledException();
		}
	}

	/** Total bytes of the local files below a path (for progress). */
	public static long localSize(Path local) throws IOException {
		try (Stream<Path> s = Files.walk(local)) {
			return s.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
		}
	}

	// ---- pure helpers ----

	/**
	 * Whether the host of an OSC 7 directory is the server: no host, "localhost", or one of the server's
	 * names (the address connected to, its {@link #hostName()}), ignoring case. Any other host is
	 * another machine, e.g. an ssh started in the terminal.
	 */
	public static boolean sameHost(String host, List<String> names) {
		return host.isEmpty() || host.equalsIgnoreCase("localhost") //$NON-NLS-1$
				|| names.stream().anyMatch(host::equalsIgnoreCase);
	}

	/** dir + "/" + name, without doubling the '/'. */
	public static String join(String dir, String name) {
		return dir.endsWith("/") ? dir + name : dir + '/' + name; //$NON-NLS-1$
	}

	/** The parent of an absolute path ("/" for "/" and "/a"). */
	public static String parent(String path) {
		String p = normalize(path);
		int i = p.lastIndexOf('/');
		return i <= 0 ? "/" : p.substring(0, i); //$NON-NLS-1$
	}

	/** The last segment of a path. */
	public static String name(String path) {
		String p = normalize(path);
		return p.equals("/") ? p : p.substring(p.lastIndexOf('/') + 1); //$NON-NLS-1$
	}

	/**
	 * Resolves a path typed by the user against the current folder: "~" is the home, "." and ".."
	 * and repeated or trailing '/' are removed. The result is absolute.
	 */
	public static String resolve(String current, String home, String typed) {
		String t = typed.trim();
		if (t.equals("~") || t.startsWith("~/")) { //$NON-NLS-1$ //$NON-NLS-2$
			t = home + t.substring(1);
		} else if (!t.startsWith("/")) { //$NON-NLS-1$
			t = current + '/' + t;
		}
		return normalize(t);
	}

	/** Removes ".", "..", repeated and trailing '/' of an absolute path. */
	public static String normalize(String path) {
		List<String> out = new ArrayList<>();
		for (String s : path.split("/")) { //$NON-NLS-1$
			if (s.isEmpty() || s.equals(".")) { //$NON-NLS-1$
				continue;
			}
			if (s.equals("..")) { //$NON-NLS-1$
				if (!out.isEmpty()) {
					out.remove(out.size() - 1);
				}
			} else {
				out.add(s);
			}
		}
		return '/' + String.join("/", out); //$NON-NLS-1$
	}

	/** A remote name as a local file name (characters Windows refuses become '_'). */
	public static String localName(String name) {
		return name.replaceAll("[\\\\/:*?\"<>|]", "_"); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** "drwxr-xr-x" for the file type and mode bits of SFTP permissions. */
	public static String permissions(int mode) {
		char type = switch (mode & 0170000) {
		case 0040000 -> 'd';
		case 0120000 -> 'l';
		default -> '-';
		};
		StringBuilder sb = new StringBuilder().append(type);
		String rwx = "rwxrwxrwx"; //$NON-NLS-1$
		for (int i = 0; i < 9; i++) {
			sb.append((mode & (0400 >> i)) != 0 ? rwx.charAt(i) : '-');
		}
		if ((mode & 04000) != 0) {
			sb.setCharAt(3, sb.charAt(3) == 'x' ? 's' : 'S');
		}
		if ((mode & 02000) != 0) {
			sb.setCharAt(6, sb.charAt(6) == 'x' ? 's' : 'S');
		}
		if ((mode & 01000) != 0) {
			sb.setCharAt(9, sb.charAt(9) == 'x' ? 't' : 'T');
		}
		return sb.toString();
	}

	/** 999 B, 1.0 KB, 12.3 MB, ... (1024 based). */
	public static String formatSize(long bytes) {
		if (bytes < 1024) {
			return bytes + " B"; //$NON-NLS-1$
		}
		String units = "KMGTPE"; //$NON-NLS-1$
		double v = bytes;
		int u = -1;
		while (v >= 1024 && u < units.length() - 1) {
			v /= 1024;
			u++;
		}
		return String.format(Locale.ROOT, "%.1f %sB", v, units.charAt(u)); //$NON-NLS-1$
	}

	/** Whether the remote file is still the one read: same size and modification time. */
	public static boolean unchanged(Entry read, Entry now) {
		return now != null && read.size() == now.size() && read.modified() == now.modified();
	}
}
