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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.insjang.terminal.connector.ssh.sftp.RemoteEditor;
import io.github.insjang.terminal.connector.ssh.sftp.RemoteFiles;
import io.github.insjang.terminal.connector.ssh.sftp.RemoteFiles.Entry;

public class RemoteFilesTest {
	@Test
	public void pathsJoinAndSplit() {
		assertEquals("/a", RemoteFiles.join("/", "a"));
		assertEquals("/a/b", RemoteFiles.join("/a", "b"));
		assertEquals("/a", RemoteFiles.parent("/a/b/"));
		assertEquals("/", RemoteFiles.parent("/a"));
		assertEquals("/", RemoteFiles.parent("/"));
		assertEquals("b", RemoteFiles.name("/a/b/"));
		assertEquals("/", RemoteFiles.name("/"));
	}

	@Test
	public void typedPathsResolve() {
		assertEquals("/home/me/x", RemoteFiles.resolve("/tmp", "/home/me", "~/x"));
		assertEquals("/home/me", RemoteFiles.resolve("/tmp", "/home/me", " ~ "));
		assertEquals("/tmp/a", RemoteFiles.resolve("/tmp", "/home/me", "a/./b/.."));
		assertEquals("/etc", RemoteFiles.resolve("/tmp", "/home/me", "//etc//"));
		assertEquals("/", RemoteFiles.resolve("/tmp", "/home/me", "../../.."));
		assertEquals("/~x", RemoteFiles.resolve("/", "/home/me", "~x"));
	}

	@Test
	public void localNamesAreSafeOnWindows() {
		assertEquals("a_b_c_.txt", RemoteFiles.localName("a:b*c?.txt"));
		assertEquals("plain", RemoteFiles.localName("plain"));
		assertEquals("ctl_x_", RemoteFiles.localName("ctl\u0001x\u001f"));
		assertEquals("trail_", RemoteFiles.localName("trail ")); // Windows drops trailing spaces and dots
		assertEquals("trail_", RemoteFiles.localName("trail."));
		assertEquals("a.b__", RemoteFiles.localName("a.b. "));
		assertEquals("_", RemoteFiles.localName("."));
		assertEquals("__", RemoteFiles.localName(".."));
		assertEquals("___", RemoteFiles.localName("...")); // else the chosen folder itself
		assertEquals("_", RemoteFiles.localName(""));
		assertEquals(".bashrc", RemoteFiles.localName(".bashrc"));
		assertEquals("_CON", RemoteFiles.localName("CON"));
		assertEquals("_nul.txt", RemoteFiles.localName("nul.txt"));
		assertEquals("_Com9.tar.gz", RemoteFiles.localName("Com9.tar.gz"));
		assertEquals("_LPT1", RemoteFiles.localName("LPT1"));
		assertEquals("CON_", RemoteFiles.localName("CON ")); // no longer a device once the space is gone
		assertEquals("COM0", RemoteFiles.localName("COM0"));
		assertEquals("CONSOLE", RemoteFiles.localName("CONSOLE"));
		assertEquals("한글 이름.txt", RemoteFiles.localName("한글 이름.txt"));
	}

	@Test
	public void permissionsLikeLs() {
		assertEquals("drwxr-xr-x", RemoteFiles.permissions(0040755));
		assertEquals("-rw-r-----", RemoteFiles.permissions(0100640));
		assertEquals("lrwxrwxrwx", RemoteFiles.permissions(0120777));
		assertEquals("-rwsr-sr-t", RemoteFiles.permissions(0107755));
		assertEquals("-rwSr-Sr-T", RemoteFiles.permissions(0107644));
	}

	@Test
	public void sizes() {
		assertEquals("0 B", RemoteFiles.formatSize(0));
		assertEquals("1023 B", RemoteFiles.formatSize(1023));
		assertEquals("1.0 KB", RemoteFiles.formatSize(1024));
		assertEquals("1.5 MB", RemoteFiles.formatSize(1024 * 1024 * 3 / 2));
		assertEquals("8.0 EB", RemoteFiles.formatSize(Long.MAX_VALUE));
	}

	@Test
	public void changeDetectionUsesSizeAndTime() {
		Entry read = new Entry("f", "/f", false, false, 10, 1000, 0100644);
		assertTrue(RemoteFiles.unchanged(read, new Entry("f", "/f", false, false, 10, 1000, 0100600)));
		assertFalse(RemoteFiles.unchanged(read, new Entry("f", "/f", false, false, 11, 1000, 0100644)));
		assertFalse(RemoteFiles.unchanged(read, new Entry("f", "/f", false, false, 10, 2000, 0100644)));
		assertFalse(RemoteFiles.unchanged(read, null)); // deleted
	}

	@Test
	public void remoteFilesNeverOpenOutsideEclipse() {
		String text = "org.eclipse.ui.DefaultTextEditor";
		assertEquals("org.eclipse.jdt.ui.CompilationUnitEditor",
				RemoteEditor.editorFor("org.eclipse.jdt.ui.CompilationUnitEditor", true, false));
		assertEquals(text, RemoteEditor.editorFor(null, false, false)); // nothing registered (x.bat)
		assertEquals(text, RemoteEditor.editorFor("org.eclipse.ui.systemExternalEditor", false, false));
		assertEquals(text, RemoteEditor.editorFor("org.eclipse.ui.systemInPlaceEditor", false, true));
		assertEquals(text, RemoteEditor.editorFor("some.external.Program", false, false)); // user association
		assertEquals(text, RemoteEditor.editorFor("org.eclipse.ui.systemExternalEditor", true, false));
	}

	@Test
	public void terminalFolderOnThisServerOnly() {
		List<String> names = List.of("server.example.com", "web1");
		assertTrue(RemoteFiles.sameHost("", names)); // file:///path
		assertTrue(RemoteFiles.sameHost("LocalHost", names));
		assertTrue(RemoteFiles.sameHost("WEB1", names)); // $HOSTNAME, any case
		assertTrue(RemoteFiles.sameHost("server.example.com", names));
		assertFalse(RemoteFiles.sameHost("web2", names)); // ssh from the terminal to another machine
		assertFalse(RemoteFiles.sameHost("server", names)); // no guessing on short names
		assertFalse(RemoteFiles.sameHost("web1", List.of()));
	}

	@Test
	public void binaryFilesAreToldByANulByte() throws IOException {
		Path dir = Files.createTempDirectory("binary");
		try {
			assertFalse(RemoteEditor.binary(Files.writeString(dir.resolve("t"), "text\n한글")));
			assertFalse(RemoteEditor.binary(Files.write(dir.resolve("e"), new byte[0])));
			byte[] late = new byte[9000];
			Arrays.fill(late, (byte) 'x');
			late[8500] = 0;
			assertFalse(RemoteEditor.binary(Files.write(dir.resolve("late"), late))); // past the first 8 KB
			late[8191] = 0;
			assertTrue(RemoteEditor.binary(Files.write(dir.resolve("b"), late)));
		} finally {
			try (var s = Files.list(dir)) {
				s.forEach(p -> p.toFile().delete());
			}
			Files.delete(dir);
		}
	}
}
