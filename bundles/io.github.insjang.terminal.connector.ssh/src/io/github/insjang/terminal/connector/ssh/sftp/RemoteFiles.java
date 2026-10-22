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
import java.io.UncheckedIOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.AttributeRepository.AttributeKey;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClient.Attributes;
import org.apache.sshd.sftp.client.SftpClient.DirEntry;
import org.apache.sshd.sftp.client.SftpClient.OpenMode;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.client.extensions.openssh.OpenSSHPosixRenameExtension;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;

/**
 * SFTP operations on the session of an SSH terminal, without UI. One {@link SftpClient} per session,
 * opened on first use; it closes with the session (or {@link #close()}). Remote paths use '/'.
 */
public final class RemoteFiles implements FileAccess, AutoCloseable {
	private static final AttributeKey<RemoteFiles> KEY = new AttributeKey<>();
	private static final int BUFFER = 32 * 1024;
	// Windows device names, also with an extension
	private static final Pattern DEVICE = Pattern.compile("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?"); //$NON-NLS-1$

	/** A directory entry; {@code dir} follows symbolic links. Times are epoch milliseconds. */
	public record Entry(String name, String path, boolean dir, boolean link, long size, long modified, int permissions) {
		static Entry of(String path, Attributes a, boolean dir) {
			return new Entry(RemoteFiles.name(path), path, dir, a.isSymbolicLink(), a.getSize(),
					a.getModifyTime() != null ? a.getModifyTime().toMillis() : 0, a.getPermissions());
		}
	}

	/** Folders first, then by name ignoring case. */
	static final Comparator<Entry> ORDER = Comparator.comparing((Entry e) -> !e.dir()).thenComparing(Entry::name,
			String.CASE_INSENSITIVE_ORDER);

	/** The name of the row to the parent folder, which no listed entry has. */
	public static final String PARENT = ".."; //$NON-NLS-1$
	/** The columns {@link #order(int, boolean)} sorts by. */
	public static final int BY_NAME = 0, BY_SIZE = 1, BY_MODIFIED = 2;

	/** The row to the parent folder. */
	public static Entry parentRow(String path) {
		return new Entry(PARENT, path, true, false, 0, 0, -1);
	}

	/** {@link #PARENT} first, folders before files, then by the column (ties by name), either way up. */
	public static Comparator<Entry> order(int column, boolean descending) {
		Comparator<Entry> name = Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER);
		Comparator<Entry> by = switch (column) {
		case BY_SIZE -> Comparator.comparingLong(Entry::size).thenComparing(name);
		case BY_MODIFIED -> Comparator.comparingLong(Entry::modified).thenComparing(name);
		default -> name;
		};
		return Comparator.comparing((Entry e) -> !e.name().equals(PARENT)).thenComparing(e -> !e.dir())
				.thenComparing(descending ? by.reversed() : by);
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

	@Override
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
			ByteArrayOutputStream out = new ByteArrayOutputStream() {
				// keeps the first 256 bytes: a host name is at most 255
				@Override
				public synchronized void write(byte[] b, int off, int len) {
					super.write(b, off, Math.max(0, Math.min(len, 256 - count)));
				}

				@Override
				public synchronized void write(int b) {
					if (count < 256) {
						super.write(b);
					}
				}
			};
			channel.setOut(out);
			channel.setErr(OutputStream.nullOutputStream());
			channel.open().verify(5, TimeUnit.SECONDS);
			channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), TimeUnit.SECONDS.toMillis(5));
			String name = out.toString(StandardCharsets.UTF_8).lines().findFirst().orElse("").trim(); //$NON-NLS-1$
			return name.isEmpty() || name.length() > 255 ? null : name;
		} catch (IOException e) {
			return null;
		}
	}

	/** The absolute path of the login directory. */
	public String home() throws IOException {
		return client.canonicalPath("."); //$NON-NLS-1$
	}

	@Override
	public List<Entry> list(String dir) throws IOException {
		List<Entry> entries = new ArrayList<>();
		try {
			list(dir, entries);
		} catch (UncheckedIOException e) {
			throw e.getCause(); // the listing reads on while iterating
		}
		entries.sort(ORDER);
		return entries;
	}

	private void list(String dir, List<Entry> entries) throws IOException {
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

	@Override
	public void mkdir(String path) throws IOException {
		client.mkdir(path);
	}

	@Override
	public void rename(String from, String to) throws IOException {
		client.rename(from, to);
	}

	@Override
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
	 * that exists already: false skips it, {@link OperationCanceledException} stops. Links to folders
	 * inside a folder are not followed (no loops, no copy of what they point to).
	 *
	 * @return the remote paths of the folder links skipped
	 */
	public List<String> download(String remote, Path localDir, Predicate<Path> overwrite, IProgressMonitor monitor)
			throws IOException {
		Entry e = stat(remote);
		if (e == null) {
			throw new IOException("No such file: " + remote); //$NON-NLS-1$
		}
		List<String> skipped = new ArrayList<>();
		download(e, localDir.resolve(localName(e.name())), overwrite, monitor, skipped);
		return skipped;
	}

	private void download(Entry e, Path local, Predicate<Path> overwrite, IProgressMonitor monitor, List<String> skipped)
			throws IOException {
		checkCanceled(monitor);
		if (e.dir()) {
			Files.createDirectories(local);
			for (Entry child : list(e.path())) {
				if (child.dir() && child.link()) {
					skipped.add(child.path());
				} else {
					download(child, local.resolve(localName(child.name())), overwrite, monitor, skipped);
				}
			}
		} else if (!Files.exists(local, LinkOption.NOFOLLOW_LINKS) || overwrite.test(local)) {
			monitor.subTask(e.path());
			// into a new file next to it, moved over the target when complete: a cancel or failure never
			// truncates a file that was there, and only the file made here is deleted
			// owner-only while it is written (POSIX): the content may be a private file's; the final mode is
			// set just before the move. CREATE_NEW, so only a file made here is ever deleted
			Path dir = local.toAbsolutePath().getParent();
			boolean posix = Files.getFileStore(dir).supportsFileAttributeView(PosixFileAttributeView.class);
			Path temp = dir.resolve(".mt-download-" + Long.toHexString(ThreadLocalRandom.current().nextLong() >>> 1) + ".tmp"); //$NON-NLS-1$ //$NON-NLS-2$
			Set<StandardOpenOption> create = EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
			OutputStream out = Channels.newOutputStream(posix ? Files.newByteChannel(temp, create, OWNER_ONLY)
					: Files.newByteChannel(temp, create));
			try {
				try (InputStream in = client.read(e.path()); OutputStream o = out) {
					copy(in, o, monitor);
				}
				if (posix) {
					finalMode(local, temp);
				}
				try {
					Files.move(temp, local, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				} catch (AtomicMoveNotSupportedException x) {
					Files.move(temp, local, StandardCopyOption.REPLACE_EXISTING);
				}
			} catch (IOException | OperationCanceledException x) {
				Files.deleteIfExists(temp);
				throw x;
			}
		}
	}

	private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY = PosixFilePermissions
			.asFileAttribute(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));

	// a file replaced keeps its permissions (e.g. the x bit of a script); a new file gets what any new file
	// gets in that folder (umask, default ACL): Java cannot read the umask, so an empty probe file made with
	// CREATE_NEW next to it tells; on failure the file stays owner-only
	private static void finalMode(Path old, Path temp) {
		try {
			Set<PosixFilePermission> mode;
			if (Files.isRegularFile(old, LinkOption.NOFOLLOW_LINKS)) {
				mode = Files.getPosixFilePermissions(old, LinkOption.NOFOLLOW_LINKS);
			} else {
				Path probe = temp.resolveSibling(temp.getFileName() + ".mode"); //$NON-NLS-1$
				Files.newByteChannel(probe, EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)).close();
				try {
					mode = Files.getPosixFilePermissions(probe, LinkOption.NOFOLLOW_LINKS);
				} finally {
					Files.deleteIfExists(probe);
				}
			}
			Files.setPosixFilePermissions(temp, mode);
		} catch (IOException | UnsupportedOperationException e) {
			// owner-only then
		}
	}

	/** Downloads one file to a local path (replaced), returning the remote entry as read. */
	public Entry get(String remote, Path local, IProgressMonitor monitor) throws IOException {
		Entry e = stat(remote);
		if (e == null) {
			throw new IOException("No such file: " + remote); //$NON-NLS-1$
		}
		download(e, local, p -> true, monitor, new ArrayList<>());
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

	/**
	 * Uploads one file to a remote path (replaced), returning the remote entry as written.
	 * <p>
	 * When the server has posix-rename@openssh.com, the file is written to a temporary file and then
	 * renamed over the target, keeping its permissions (a new file gets the server's default): a cancel, an error or a lost connection leaves
	 * the old file as it was. The temporary file is {@code .<name>.mt-upload-<random>/<name>} in the same
	 * folder: a new folder (mkdir fails on any existing name or link), made 0700 before the file is
	 * created in it with CREATE|EXCL and made 0600 before any data is written. Others can never open it:
	 * a file created 0644 under umask and chmod-ed later could be opened in between and read once
	 * written (the SFTP client cannot send the mode with the open request).
	 * <p>
	 * Written in place (a cancel then leaves the file cut) without the extension, when the folder is not
	 * writable or the temporary name cannot be made, for a link or other non-regular file, for a file
	 * without the owner write bit, and for a file of another owner or of a group the
	 * new file cannot get (a rename would change them).
	 */
	public Entry put(Path local, String remote, IProgressMonitor monitor) throws IOException {
		monitor.subTask(remote);
		Attributes old = lstat(remote);
		OpenSSHPosixRenameExtension rename = client.getExtension(OpenSSHPosixRenameExtension.class);
		// a file without the owner write bit is written in place, so the server refuses it as before
		if ((old == null || old.isRegularFile() && (old.getPermissions() & 0200) != 0) && rename.isSupported()) {
			String dir = tempName(remote);
			String temp = join(dir, name(remote));
			OutputStream out = null;
			int mode = -1;
			try {
				int created = privateDir(dir);
				// a new file gets what the server would give it: 0666 less the umask the new folder shows
				mode = old != null ? old.getPermissions() & 07777 : created & 0666;
				out = created >= 0 ? createPrivate(temp, old) : null;
			} finally {
				if (out == null) {
					rmdirQuietly(dir); // in place below
				}
			}
			if (out != null) {
				try {
					try (InputStream in = Files.newInputStream(local); OutputStream o = out) {
						copy(in, o, monitor);
					}
					client.setStat(temp, new Attributes().perms(mode));
					rename.posixRename(temp, remote);
					return stat(remote);
				} finally {
					removeQuietly(temp); // gone after the rename
					rmdirQuietly(dir);
				}
			}
		}
		try (InputStream in = Files.newInputStream(local); OutputStream out = client.write(remote)) {
			copy(in, out, monitor);
		} catch (IOException | RuntimeException x) {
			if (old == null) {
				removeQuietly(remote); // only a partial file made here
			}
			throw x;
		}
		return stat(remote);
	}

	private Attributes lstat(String path) throws IOException {
		try {
			return client.lstat(path);
		} catch (SftpException e) {
			if (e.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE) {
				return null;
			}
			throw e;
		}
	}

	// makes a new folder only we can enter; its mode as made (0777 less the umask), or -1
	private int privateDir(String dir) throws IOException {
		try {
			client.mkdir(dir);
		} catch (SftpException e) {
			return -1; // the folder is not writable, or the name exists
		}
		try {
			int made = client.lstat(dir).getPermissions() & 0777;
			client.setStat(dir, new Attributes().perms(0700));
			Attributes a = client.lstat(dir);
			return a.isDirectory() && (a.getPermissions() & 0777) == 0700 ? made : -1;
		} catch (SftpException e) {
			return -1; // removed by the caller
		}
	}

	// the file in the private folder, 0600, with the owner and group of the old file; else null
	private OutputStream createPrivate(String temp, Attributes old) throws IOException {
		OutputStream out;
		try {
			out = client.write(temp, OpenMode.Write, OpenMode.Create, OpenMode.Exclusive);
		} catch (SftpException e) {
			return null;
		}
		try {
			client.setStat(temp, new Attributes().perms(0600));
			Attributes made = client.lstat(temp);
			if (old != null && made.getGroupId() != old.getGroupId()) {
				try {
					client.setStat(temp, new Attributes().owner(made.getUserId(), old.getGroupId()));
				} catch (SftpException e) {
					// not a member of that group: checked below
				}
				made = client.lstat(temp);
			}
			if (old == null || made.getUserId() == old.getUserId() && made.getGroupId() == old.getGroupId()) {
				return out;
			}
		} catch (IOException | RuntimeException x) {
			out.close();
			removeQuietly(temp);
			throw x;
		}
		out.close();
		removeQuietly(temp);
		return null;
	}

	private void rmdirQuietly(String path) {
		try {
			client.rmdir(path);
		} catch (IOException ignored) {
			// already gone, or left over
		}
	}

	private void removeQuietly(String path) {
		try {
			client.remove(path);
		} catch (IOException ignored) {
			// leave it
		}
	}

	/** ".name.mt-upload-<random>" next to the file (the folder of the temporary copy). */
	static String tempName(String remote) {
		return join(parent(remote), '.' + name(remote) + ".mt-upload-" //$NON-NLS-1$
				+ Long.toHexString(ThreadLocalRandom.current().nextLong() >>> 1));
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

	@Override
	public String child(String dir, String name) {
		return join(dir, name);
	}

	@Override
	public String parentOf(String path) {
		return normalize(path).equals("/") ? null : parent(path); //$NON-NLS-1$
	}

	@Override
	public String typed(String current, String home, String text) {
		return resolve(current, home, text);
	}

	@Override
	public String fromShell(String path) {
		return normalize(path);
	}

	@Override
	public String shown(String path) {
		return path;
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

	/**
	 * A remote name as a local file name Windows accepts, the same on every system: control characters
	 * and \/:*?"<>| become '_', and so do trailing dots and spaces (Windows drops them, so "..." would
	 * be the folder itself); an empty name is "_"; a device name (CON, PRN, AUX, NUL, COM1-9, LPT1-9,
	 * also with an extension, any case) gets a '_' in front.
	 */
	public static String localName(String name) {
		String n = name.replaceAll("[\\x00-\\x1f\\\\/:*?\"<>|]", "_") //$NON-NLS-1$ //$NON-NLS-2$
				.replaceAll("[. ](?=[. ]*$)", "_"); //$NON-NLS-1$ //$NON-NLS-2$
		if (n.isEmpty()) {
			return "_"; //$NON-NLS-1$
		}
		return DEVICE.matcher(n).matches() ? '_' + n : n;
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
