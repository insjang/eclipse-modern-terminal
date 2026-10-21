/*******************************************************************************
 * Copyright (c) 2026 Eclipse contributors and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package io.github.insjang.terminal.view.ui.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.insjang.terminal.view.ui.internal.tabs.DroppedFiles;
import org.junit.jupiter.api.Test;

/**
 * Tests for the text typed into a terminal when files are dropped onto it.
 */
public class DroppedFilesTest {

	private static final String WSL = "C:\\Windows\\System32\\wsl.exe";
	private static final String GIT_BASH = "C:\\apps\\git\\bin\\sh.exe";

	@Test
	public void wslSeesWindowsDrivesUnderMnt() {
		assertEquals("'/mnt/d/devs/docs/설계 문서.md'",
				DroppedFiles.format(new String[] { "D:\\devs\\docs\\설계 문서.md" }, WSL, true));
	}

	@Test
	public void wslSharePathsAreLinuxPaths() {
		assertEquals("'/home/nocafe/a.md'",
				DroppedFiles.format(new String[] { "\\\\wsl.localhost\\Ubuntu\\home\\nocafe\\a.md" }, WSL, true));
		assertEquals("'/home/a'", DroppedFiles.format(new String[] { "\\\\wsl$\\Ubuntu\\home\\a" }, WSL, true));
	}

	@Test
	public void gitBashSeesDrivesAtTheRoot() {
		assertEquals("'/c/work/a.txt'", DroppedFiles.format(new String[] { "C:\\work\\a.txt" }, GIT_BASH, true));
	}

	@Test
	public void severalFilesAreSeparatedBySpaces() {
		assertEquals("'/mnt/c/a' '/mnt/c/b'", DroppedFiles.format(new String[] { "C:\\a", "C:\\b" }, WSL, true));
	}

	@Test
	public void singleQuotesAreEscaped() {
		assertEquals("'/tmp/it'\\''s'", DroppedFiles.format(new String[] { "/tmp/it's" }, null, false));
	}

	@Test
	public void cmdQuotesOnlyPathsWithSpaces() {
		assertEquals("C:\\a.txt \"C:\\my docs\\b.txt\"",
				DroppedFiles.format(new String[] { "C:\\a.txt", "C:\\my docs\\b.txt" }, null, true));
	}
}
