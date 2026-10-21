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

import org.junit.jupiter.api.Test;

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
}
