/*******************************************************************************
 * Copyright (c) 2026 insjang and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package io.github.insjang.terminal.connector.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.jupiter.api.Test;

import io.github.insjang.terminal.connector.local.launcher.LocalLauncherDelegate;
import io.github.insjang.terminal.connector.ssh.sftp.LocalFiles;
import io.github.insjang.terminal.connector.ssh.sftp.RemoteFiles;
import io.github.insjang.terminal.connector.ssh.sftp.RemoteFiles.Entry;
import io.github.insjang.terminal.view.ui.internal.tabs.ShellPaths;

public class LocalFilesTest {
	@Test
	public void wslDistributionFromTheLaunch() {
		assertEquals("Ubuntu", ShellPaths.wslDistribution("C:\\Windows\\System32\\wsl.exe", "--distribution Ubuntu"));
		assertEquals("Ubuntu-24.04", ShellPaths.wslDistribution("C:\\WINDOWS\\system32\\WSL.EXE", " -d Ubuntu-24.04  --cd ~"));
		// the home before the command: after "-e zsh" it would be zsh's arguments
		assertEquals("--cd ~ -d Ubuntu -e zsh", LocalLauncherDelegate.homeFirst("-d Ubuntu -e zsh"));
		assertEquals("--cd ~", LocalLauncherDelegate.homeFirst(""));
		assertEquals("", ShellPaths.wslDistribution("wsl.exe", null)); // the default distribution
		assertEquals("", ShellPaths.wslDistribution("wsl.exe", "-d")); // no name after it
		assertEquals("", ShellPaths.wslDistribution("C:\\Windows\\System32\\bash.exe", "")); // the old launcher
		assertNull(ShellPaths.wslDistribution("C:\\Program Files\\Git\\bin\\bash.exe", "--login -i"));
		assertNull(ShellPaths.wslDistribution("C:\\Windows\\system32\\cmd.exe", null));
		assertNull(ShellPaths.wslDistribution("/bin/bash", "-d Ubuntu"));
		assertNull(ShellPaths.wslDistribution(null, null));
	}

	@Test
	public void shellPathsAsWindowsPaths() {
		// WSL
		assertEquals("D:\\a b\\c", ShellPaths.toWindows("/mnt/d/a b/c", "Ubuntu"));
		assertEquals("C:\\", ShellPaths.toWindows("/mnt/c", "Ubuntu"));
		assertEquals("\\\\wsl.localhost\\Ubuntu\\home\\me", ShellPaths.toWindows("/home/me", "Ubuntu"));
		assertEquals("\\\\wsl.localhost\\Ubuntu\\", ShellPaths.toWindows("/", "Ubuntu"));
		assertEquals("\\\\wsl.localhost\\Ubuntu\\mnt\\wsl", ShellPaths.toWindows("/mnt/wsl", "Ubuntu")); // not a drive
		assertNull(ShellPaths.toWindows("/home/me", "")); // distribution not known
		assertEquals("E:\\x", ShellPaths.toWindows("/mnt/e/x", "")); // drives are known anyway
		// Git Bash, and a file URI path as cmd or PowerShell prompts send it
		assertEquals("C:\\Users\\me", ShellPaths.toWindows("/c/Users/me", null));
		assertEquals("C:\\", ShellPaths.toWindows("/c", null));
		assertEquals("C:\\Users\\me", ShellPaths.toWindows("/C:/Users/me", null));
		assertEquals("C:\\", ShellPaths.toWindows("/C:", null));
		assertNull(ShellPaths.toWindows("/usr/bin", null)); // Git Bash's own tree: not followed
		assertNull(ShellPaths.toWindows("relative", "Ubuntu"));
		// and back, for showing a WSL folder
		assertEquals("/home/me", ShellPaths.toWsl(ShellPaths.toWindows("/home/me", "Ubuntu")));
		assertEquals("/", ShellPaths.toWsl("\\\\wsl.localhost\\Ubuntu"));
		assertEquals("/mnt/d/a b", ShellPaths.toWsl("D:\\a b"));
	}

	@Test
	public void terminalFolderOnThisMachineOnly() {
		// this machine's hostname and a WSL distribution's uname -n, as LocalFiles collects them
		List<String> names = List.of("INSJANG-PPV-HP", "ubuntu-box");
		assertTrue(RemoteFiles.sameHost("", names));
		assertTrue(RemoteFiles.sameHost("LOCALHOST", names));
		assertTrue(RemoteFiles.sameHost("insjang-ppv-hp", names)); // $HOSTNAME of Git Bash or WSL
		assertTrue(RemoteFiles.sameHost("Ubuntu-Box", names));
		assertFalse(RemoteFiles.sameHost("server.example.com", names)); // ssh started in the local terminal
		assertFalse(RemoteFiles.sameHost("insjang-ppv-hp.example.com", names));
		assertFalse(RemoteFiles.sameHost("other", List.of()));
	}

	@Test
	public void localFoldersOnThisSystem() throws IOException {
		LocalFiles files = LocalFiles.of("/bin/bash", null); // not Windows here: no WSL
		assertNull(files.distribution());
		assertEquals("/a/c", files.fromShell("/a/b/../c"));
		assertNull(files.fromShell("not/absolute"));
		assertEquals("/tmp/x", files.typed("/tmp", "/home/me", "x/./y/.."));
		assertEquals("/home/me/x", files.typed("/tmp", "/home/me", "~/x"));
		assertEquals("/home/me", files.typed("/tmp", "/home/me", " ~ "));
		assertEquals("/etc", files.typed("/tmp", "/home/me", "/etc"));
		assertNull(files.parentOf("/"));
		assertEquals("/a", files.parentOf("/a/b"));
		assertEquals("Access denied", LocalFiles.reason(new AccessDeniedException("/x")));
		assertEquals("No such file", LocalFiles.reason(new NoSuchFileException("/x")));
	}

	@Test
	public void deleteNeverFollowsLinks() throws IOException {
		Path root = Files.createTempDirectory("mt-local");
		try {
			Path outside = Files.createDirectories(root.resolve("outside"));
			Path kept = Files.writeString(outside.resolve("keep.txt"), "keep");
			Path dir = Files.createDirectories(root.resolve("dir/sub"));
			Files.writeString(dir.resolve("f.txt"), "x");
			Files.createSymbolicLink(dir.resolve("to-outside"), outside);
			Files.createSymbolicLink(dir.resolve("to-file"), kept);
			LocalFiles files = LocalFiles.of("/bin/sh", null);
			List<Entry> entries = files.list(dir.toString());
			assertEquals(List.of("to-outside", "f.txt", "to-file"), entries.stream().map(Entry::name).toList());
			assertTrue(entries.get(0).dir() && entries.get(0).link()); // a link to a folder is entered like one
			assertEquals("lrwxrwxrwx", RemoteFiles.permissions(entries.get(0).permissions()));
			files.delete(root.resolve("dir").toString(), new NullProgressMonitor());
			assertFalse(Files.exists(root.resolve("dir"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
			assertEquals("keep", Files.readString(kept));
		} finally {
			try (var s = Files.walk(root)) {
				s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
			}
		}
	}

	@Test
	public void wslLinksAreResolvedInWsl() {
		assertEquals(List.of("wsl.exe", "--distribution", "Ubuntu", "--exec", "realpath", "-m", "-z", "--", "/home/me/proj",
				"/mnt/c/a b"),
				LocalFiles.realpathCommand("Ubuntu", List.of("\\\\wsl.localhost\\Ubuntu\\home\\me\\proj", "C:\\a b")));
		assertEquals(List.of("wsl.exe", "--exec", "realpath", "-m", "-z", "--", "/x"),
				LocalFiles.realpathCommand("", List.of("\\\\wsl$\\Debian\\x"))); // the default distribution
		// realpath -z: each target ended by NUL; Windows drives back to drive letters, the rest under \\wsl.localhost
		assertEquals(List.of("C:\\appdev\\work", "\\\\wsl.localhost\\Ubuntu\\etc", "\\\\wsl.localhost\\Ubuntu\\a\nb"),
				LocalFiles.wslTargets("/mnt/c/appdev/work\0/etc\0/a\nb\0", "Ubuntu"));
		assertEquals(Arrays.asList("D:\\x", null), LocalFiles.wslTargets("/mnt/d/x\0/home\0", "")); // not known
		// a batch is cut by the length as passed: "/mnt/c/a b" in quotes and a space
		assertEquals("\"/mnt/c/a b\" ".length(), LocalFiles.argLength("C:\\a b"));
	}

	@Test
	public void wslModes() {
		assertEquals(List.of("wsl.exe", "--distribution", "Ubuntu", "--exec", "find", "/home/me", "-mindepth", "1", "-maxdepth",
				"1", "-printf", "%m %y %f\\0"), LocalFiles.modesCommand("Ubuntu", "\\\\wsl.localhost\\Ubuntu\\home\\me"));
		// find -printf "%m %y %f\0": octal mode, type, name (spaces kept); type bits added as in SFTP
		assertEquals(java.util.Map.of("a b", 0100644, "d", 0040755, "l", 0120777),
				LocalFiles.wslModes("644 f a b\0" + "755 d d\0" + "777 l l\0"));
	}

	@Test
	public void wslListIsReadAsUtf16OrUtf8() {
		// wsl -l -q: UTF-16LE, or UTF-8 with WSL_UTF8=1
		String list = "Ubuntu-24.04\r\n우분투\r\n";
		assertEquals(list, ShellPaths.wslText(list.getBytes(java.nio.charset.StandardCharsets.UTF_16LE)));
		assertEquals(list, ShellPaths.wslText(("\uFEFF" + list).getBytes(java.nio.charset.StandardCharsets.UTF_16LE)));
		assertEquals(list, ShellPaths.wslText(list.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
	}
}
