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

import java.io.File;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;

import io.github.insjang.terminal.connector.ssh.sftp.RemoteFiles.Entry;
import io.github.insjang.terminal.view.ui.internal.tabs.ShellPaths;

/**
 * The local file system, for a local terminal. On Windows the shell may be WSL or Git Bash: the folders
 * it reports are mapped to Windows paths, and in WSL they are shown as Linux paths.
 */
public final class LocalFiles implements FileAccess {
	private static final boolean WINDOWS = File.separatorChar == '\\';
	private static final LinkOption NOFOLLOW = LinkOption.NOFOLLOW_LINKS;
	/** The characters a new name cannot have: it would be a path. */
	public static final String NOT_IN_NAMES = WINDOWS ? "/\\:" : "/"; //$NON-NLS-1$ //$NON-NLS-2$
	// ponytail: the output of hostname and of the WSL probe, asked once per command until restart
	private static final Map<List<String>, List<String>> OUTPUT = new ConcurrentHashMap<>();

	private final String distribution; // the WSL distribution, "" if not known; null if not WSL
	private final String home;
	private final List<String> hostNames;
	// WSL links Windows cannot read, by path: where they point, as a Windows path
	private final Map<String, String> targets = new ConcurrentHashMap<>();

	private LocalFiles(String distribution, String home, List<String> hostNames) {
		this.distribution = distribution;
		this.home = home;
		this.hostNames = hostNames;
	}

	/**
	 * The files of a local shell. Asks this machine its name and, for WSL, the distribution its name, home
	 * and default name: up to 5 seconds each the first time, so not on the UI thread.
	 */
	public static LocalFiles of(String shell, String arguments) {
		String userHome = System.getProperty("user.home"); //$NON-NLS-1$
		List<String> names = new ArrayList<>(run(List.of("hostname"))); //$NON-NLS-1$
		String wsl = WINDOWS ? ShellPaths.wslDistribution(shell, arguments) : null;
		if (wsl == null) {
			return new LocalFiles(null, userHome, names);
		}
		List<String> command = new ArrayList<>(List.of("wsl.exe")); //$NON-NLS-1$
		if (!wsl.isEmpty()) {
			command.addAll(List.of("--distribution", wsl)); //$NON-NLS-1$
		}
		// no quotes: Windows would have to pass them through two command lines
		command.addAll(List.of("--exec", "sh", "-c", "uname -n; echo $HOME; echo $WSL_DISTRO_NAME")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		List<String> out = run(command);
		if (!out.isEmpty()) {
			names.add(out.get(0)); // the distribution's own name: what its shells send
		}
		String distribution = !wsl.isEmpty() ? wsl : out.size() > 2 ? out.get(2) : ""; //$NON-NLS-1$
		String linuxHome = out.size() > 1 ? ShellPaths.toWindows(out.get(1), distribution) : null;
		return new LocalFiles(distribution, linuxHome != null ? linuxHome : userHome, names);
	}

	private static List<String> run(List<String> command) {
		return OUTPUT.computeIfAbsent(command, c -> {
			String out = output(c);
			return out == null ? List.of() : out.lines().map(String::strip).toList();
		});
	}

	/** What a command prints, null if it fails or takes more than 5 seconds. */
	private static String output(List<String> command) {
		try {
			Process p = new ProcessBuilder(command).redirectError(Redirect.DISCARD).start();
			p.getOutputStream().close();
			// read while waiting: a long output would fill the pipe
			CompletableFuture<byte[]> out = CompletableFuture.supplyAsync(() -> {
				try {
					return p.getInputStream().readNBytes(1 << 20);
				} catch (IOException e) {
					return new byte[0];
				}
			});
			if (!p.waitFor(5, TimeUnit.SECONDS)) {
				p.destroyForcibly();
				return null;
			}
			return p.exitValue() != 0 ? null : new String(out.join(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
	}

	/** The WSL distribution, "" if it is not known, null if the shell is not WSL. */
	public String distribution() {
		return distribution;
	}

	public String home() {
		return home;
	}

	/** This machine's names, and the distribution's in WSL: OSC 7 from elsewhere is not followed. */
	public List<String> hostNames() {
		return hostNames;
	}

	@Override
	public List<Entry> list(String dir) throws IOException {
		List<Entry> entries = new ArrayList<>();
		List<String> unread = new ArrayList<>();
		try (DirectoryStream<Path> s = Files.newDirectoryStream(Path.of(dir))) {
			for (Path p : s) {
				try {
					entries.add(entry(p));
				} catch (IOException e) {
					// a WSL symbolic link seen from Windows over \\wsl.localhost: no attributes, whether
					// followed or not, and it cannot be deleted from here either
					unread.add(p.toString());
				}
			}
		} catch (FileSystemException e) {
			throw new IOException(reason(e), e);
		}
		entries.addAll(wslLinks(unread));
		entries.sort(RemoteFiles.ORDER);
		return entries;
	}

	/**
	 * Links that Windows cannot read, resolved in WSL by one wsl.exe for the whole folder: a link to a
	 * folder is a folder (opened at its target), anything else (also a broken link) a file.
	 */
	private List<Entry> wslLinks(List<String> links) {
		List<String> resolved = new ArrayList<>();
		if (distribution != null) {
			// one wsl.exe per ~30000 characters: the Windows command line ends at 32767
			for (int from = 0, to; from < links.size(); from = to) {
				int length = 0;
				for (to = from; to < links.size() && (to == from || length + links.get(to).length() < 30000); to++) {
					length += links.get(to).length() + 3;
				}
				String out = output(realpathCommand(distribution, links.subList(from, to)));
				List<String> part = out == null ? List.of() : wslTargets(out, distribution);
				for (int i = 0; i < to - from; i++) {
					resolved.add(i < part.size() ? part.get(i) : null);
				}
			}
		}
		List<Entry> entries = new ArrayList<>();
		for (int i = 0; i < links.size(); i++) {
			String link = links.get(i);
			String target = i < resolved.size() ? resolved.get(i) : null;
			boolean dir = false;
			if (target != null) {
				targets.put(link, target);
				dir = Files.isDirectory(Path.of(target)); // resolved: no link left on the way
			}
			entries.add(new Entry(Path.of(link).getFileName().toString(), link, dir, true, 0, 0, -1));
		}
		return entries;
	}

	/** wsl.exe printing the targets of Windows paths in WSL, all links resolved, each ended by NUL. */
	public static List<String> realpathCommand(String distribution, List<String> paths) {
		List<String> command = new ArrayList<>(List.of("wsl.exe")); //$NON-NLS-1$
		if (!distribution.isEmpty()) {
			command.addAll(List.of("--distribution", distribution)); //$NON-NLS-1$
		}
		command.addAll(List.of("--exec", "realpath", "-m", "-z", "--")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
		paths.forEach(p -> command.add(ShellPaths.toWsl(p)));
		return command;
	}

	/** The output of {@link #realpathCommand} as Windows paths, in order; null for one that cannot be mapped. */
	public static List<String> wslTargets(String out, String distribution) {
		List<String> targets = new ArrayList<>();
		for (String linux : out.split("\0")) { //$NON-NLS-1$
			targets.add(ShellPaths.toWindows(linux, distribution));
		}
		return targets;
	}

	@Override
	public String target(Entry entry) {
		return targets.getOrDefault(entry.path(), entry.path());
	}

	static Entry entry(Path p) throws IOException {
		BasicFileAttributes a = WINDOWS ? Files.readAttributes(p, BasicFileAttributes.class, NOFOLLOW)
				: Files.readAttributes(p, PosixFileAttributes.class, NOFOLLOW);
		boolean link = link(a);
		int mode = -1; // not shown
		if (a instanceof PosixFileAttributes posix) {
			mode = a.isSymbolicLink() ? 0120000 : a.isDirectory() ? 0040000 : 0100000;
			for (PosixFilePermission permission : posix.permissions()) {
				mode |= 0400 >> permission.ordinal(); // OWNER_READ ... OTHERS_EXECUTE
			}
		}
		return new Entry(p.getFileName().toString(), p.toString(), link ? Files.isDirectory(p) : a.isDirectory(), link,
				a.size(), a.lastModifiedTime().toMillis(), mode);
	}

	/** A symbolic link, or on Windows a junction (or other reparse point) to a folder: never entered to delete. */
	private static boolean link(BasicFileAttributes a) {
		return a.isSymbolicLink() || a.isDirectory() && a.isOther();
	}

	@Override
	public void mkdir(String path) throws IOException {
		try {
			Files.createDirectory(Path.of(path));
		} catch (FileSystemException e) {
			throw failure(e);
		}
	}

	@Override
	public void rename(String from, String to) throws IOException {
		Path source = Path.of(from), target = Path.of(to);
		try {
			if (from.equalsIgnoreCase(to) && Files.exists(target, NOFOLLOW) && Files.isSameFile(source, target)) {
				// another case of the same name (Windows, macOS): Files.move would do nothing
				if (!source.toFile().renameTo(target.toFile())) {
					throw new IOException(from + ": " + to); //$NON-NLS-1$
				}
				return;
			}
			Files.move(source, target); // the link itself, never what it points to
		} catch (FileSystemException e) {
			throw failure(e);
		}
	}

	@Override
	public void delete(String path, IProgressMonitor monitor) throws IOException {
		try {
			delete(Path.of(path), monitor);
		} catch (FileSystemException e) {
			throw failure(e);
		}
	}

	private static void delete(Path p, IProgressMonitor monitor) throws IOException {
		if (monitor.isCanceled()) {
			throw new OperationCanceledException();
		}
		BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, NOFOLLOW);
		if (a.isDirectory() && !link(a)) {
			try (DirectoryStream<Path> s = Files.newDirectoryStream(p)) {
				for (Path child : s) {
					delete(child, monitor);
				}
			}
		}
		try {
			Files.delete(p); // a link or junction: the link only
		} catch (AccessDeniedException e) {
			// Windows does not delete a read-only file (git objects are): clear the flag, as Explorer does
			DosFileAttributeView dos = WINDOWS ? Files.getFileAttributeView(p, DosFileAttributeView.class, NOFOLLOW) : null;
			if (dos == null || !dos.readAttributes().isReadOnly()) {
				throw e;
			}
			dos.setReadOnly(false);
			Files.delete(p);
		}
		monitor.worked(1);
	}

	@Override
	public boolean isOpen() {
		return true;
	}

	@Override
	public String child(String dir, String name) {
		return Path.of(dir).resolve(name).toString();
	}

	@Override
	public String parentOf(String path) {
		Path parent = Path.of(path).getParent();
		return parent == null ? null : parent.toString();
	}

	@Override
	public String typed(String current, String home, String text) {
		String t = text.strip();
		if (t.equals("~")) { //$NON-NLS-1$
			return home;
		}
		if (t.startsWith("~/") || t.startsWith("~\\")) { //$NON-NLS-1$ //$NON-NLS-2$
			return Path.of(home).resolve(t.substring(2)).normalize().toString();
		}
		String shell = t.startsWith("/") ? fromShell(t) : null; //$NON-NLS-1$
		return shell != null ? shell : Path.of(current).resolve(t).normalize().toString();
	}

	@Override
	public String fromShell(String path) {
		String p = WINDOWS ? ShellPaths.toWindows(path, distribution) : path.startsWith("/") ? path : null; //$NON-NLS-1$
		try {
			return p == null ? null : Path.of(p).normalize().toString();
		} catch (InvalidPathException e) {
			return null; // e.g. a Linux name with ':' in WSL
		}
	}

	@Override
	public String shown(String path) {
		return distribution != null ? ShellPaths.toWsl(path) : path;
	}

	private static IOException failure(FileSystemException e) {
		return new IOException(e.getFile() + ": " + reason(e), e); //$NON-NLS-1$
	}

	/** The reason, or "Access denied" for an AccessDeniedException without one. */
	public static String reason(FileSystemException e) {
		if (e.getReason() != null) {
			return e.getReason();
		}
		String words = e.getClass().getSimpleName().replace("Exception", "").replaceAll("(?<=[a-z])(?=[A-Z])", " "); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		return words.charAt(0) + words.substring(1).toLowerCase(Locale.ROOT);
	}
}
