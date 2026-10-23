/*******************************************************************************
 * Copyright (c) 2026 insjang and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package io.github.insjang.terminal.internal.model;

import static io.github.insjang.terminal.model.ITerminalTextDataReadOnly.MARK_FAILED;
import static io.github.insjang.terminal.model.ITerminalTextDataReadOnly.MARK_OUTPUT;
import static io.github.insjang.terminal.model.ITerminalTextDataReadOnly.MARK_PROMPT;
import static io.github.insjang.terminal.model.ITerminalTextDataReadOnly.MARK_SUCCEEDED;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.StringReader;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.insjang.terminal.internal.emulator.MockTerminalControlForText;
import io.github.insjang.terminal.internal.emulator.VT100Emulator;
import io.github.insjang.terminal.model.ITerminalTextData;
import io.github.insjang.terminal.model.TerminalTextDataFactory;

public class ShellMarksTest {
	// what the README snippets send: D;status and A before the prompt, B after it, C before the output
	private static final String A = "\033]133;A\007", B = "\033]133;B\007", C = "\033]133;C\007";

	private static String D(int status) {
		return "\033]133;D;" + status + "\007";
	}

	private ITerminalTextData data;
	private VT100Emulator emulator;

	@BeforeEach
	public void before() {
		data = TerminalTextDataFactory.makeTerminalTextData();
		data.setMaxHeight(8);
		emulator = new VT100Emulator(data, new MockTerminalControlForText(), null);
		emulator.resetState();
		emulator.setDimensions(4, 40);
	}

	private void run(String... text) {
		emulator.setInputStreamReader(new StringReader(String.join("", text)));
		emulator.processText();
	}

	// a prompt, the command typed and run, its output
	private static String command(String cmd, String... output) {
		// C after the newline, as the README snippets send it
		return A + "$ " + B + cmd + "\r\n" + C + String.join("\r\n", output) + (output.length > 0 ? "\r\n" : "");
	}

	@Test
	public void testMarksAndStatus() {
		run(D(0), command("ls", "a", "b"), D(0), command("false"), D(1), A + "$ " + B);
		assertEquals(MARK_PROMPT | MARK_SUCCEEDED, data.getLineMark(0));
		assertEquals(MARK_OUTPUT, data.getLineMark(1));
		assertEquals(0, data.getLineMark(2));
		assertEquals(MARK_PROMPT | MARK_FAILED, data.getLineMark(3)); // false: no output, C on the next prompt's line
		assertEquals(MARK_PROMPT, data.getLineMark(4) & MARK_PROMPT);
		assertEquals(3, ShellMarks.previousPrompt(data, 4));
		assertEquals(4, ShellMarks.nextPrompt(data, 3));
		assertEquals(-1, ShellMarks.nextPrompt(data, 4));
	}

	@Test
	public void testBashSnippet() {
		// what bash 5.2 sent with the README snippet for: echo hi, false, an empty line
		run("\033]133;D;0\007\033]133;A\007\033[?2004h$ \033]133;B\007echo hi\r\n\033[?2004l\r\033]133;C\007hi\r\n",
				"\033]133;D;0\007\033]133;A\007\033[?2004h$ \033]133;B\007false\r\n\033[?2004l\r\033]133;C\007",
				"\033]133;D;1\007\033]133;A\007\033[?2004h$ \033]133;B\007\r\n\033[?2004l\r",
				"\033]133;D;1\007\033]133;A\007\033[?2004h$ \033]133;B\007");
		assertEquals(MARK_PROMPT | MARK_SUCCEEDED, data.getLineMark(0));
		assertEquals(MARK_OUTPUT, data.getLineMark(1));
		assertEquals(MARK_PROMPT | MARK_FAILED, data.getLineMark(2));
		assertEquals(MARK_PROMPT | MARK_OUTPUT, data.getLineMark(3));
		assertEquals(MARK_PROMPT, data.getLineMark(4));
		assertNull(ShellMarks.lastOutput(data)); // false printed nothing
	}

	@Test
	public void testEmptyEnterRunsNothing() {
		run(command("false"), D(1), A + "$ " + B + "\r\n", D(1), A + "$ " + B);
		assertEquals(MARK_PROMPT | MARK_FAILED, data.getLineMark(0));
		// Enter on an empty line: no C, no status (the output mark is that of false, which printed nothing)
		assertEquals(MARK_PROMPT | MARK_OUTPUT, data.getLineMark(1));
		assertEquals(MARK_PROMPT, data.getLineMark(2));
	}

	@Test
	public void testLastOutput() {
		run(command("ls", "a", "b", ""), D(0), A + "$ " + B);
		assertArrayEquals(new int[] { 1, 2 }, ShellMarks.lastOutput(data)); // the blank line left out
		run("true\r\n" + C, D(0), A + "$ " + B);
		assertNull(ShellMarks.lastOutput(data)); // true printed nothing
	}

	@Test
	public void testMarksScrollAndGo() {
		run(command("seq", "1", "2"));
		assertEquals(MARK_PROMPT, data.getLineMark(0));
		// 8 lines at most: the prompt line moves up, then drops out of the buffer
		run("3\r\n4\r\n5\r\n");
		assertEquals(MARK_PROMPT, data.getLineMark(0) & MARK_PROMPT);
		run("6\r\n7\r\n");
		assertEquals(-1, ShellMarks.previousPrompt(data, data.getHeight()));
	}

	@Test
	public void testRepeatedMarksAreCheap() {
		// a full 10000 line scrollback, then a flood of D and of C, newline, D: no walk over the buffer each time
		data.setMaxHeight(10000);
		run(A + "$ " + B + "x\r\n" + C + "\r\n".repeat(12000));
		long start = System.nanoTime();
		run(D(1).repeat(100000));
		run((C + "\r\n" + D(1)).repeat(100000));
		run((A + C + D(1)).repeat(100000));
		long ms = (System.nanoTime() - start) / 1000000;
		assertEquals(true, ms < 1000, "took " + ms + " ms");
	}

	@Test
	public void testEraseLineKeepsTheMark() {
		run(A + "$ " + B + "\r\033[2K$ " + B);
		assertEquals(MARK_PROMPT, data.getLineMark(0));
	}

	@Test
	public void testAlternateScreenKeepsMarks() {
		run(command("vim"), "\033[?1049h", "screen", "\033[?1049l");
		assertEquals(MARK_PROMPT, data.getLineMark(0));
	}
}
