/*******************************************************************************
 * Copyright (c) 2021 Kichwa Coders Canada Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package io.github.insjang.terminal.internal.emulator;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.StringJoiner;

import io.github.insjang.terminal.connector.Logger;
import io.github.insjang.terminal.control.TerminalDirectory;
import io.github.insjang.terminal.model.ITerminalTextData;
import io.github.insjang.terminal.model.TerminalTextDataFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class VT100EmulatorTest {

	private static final int WINDOW_COLUMNS = 80;
	private static final int WINDOW_LINES = 24;
	private static final String CLEAR_CURSOR_TO_EOL = "\033[K";
	private static final String CURSOR_POSITION_TOP_LEFT = "\033[H";
	private static final String CLEAR_ENTIRE_SCREEN_AND_SCROLLBACK = "\033[3J";
	private static final String CLEAR_ENTIRE_SCREEN = "\033[2J";
	private static final String SCROLL_REVERSE = "\033M";

	private static String TITLE_ST_BEL(String title) {
		return "\033]0;" + title + "\007";
	}

	private static String TITLE_ST_ESC_BACKSLASH(String title) {
		return "\033]0;" + title + "\033\\";
	}

	private static String TITLE(String title) {
		return TITLE_ST_BEL(title);
	}

	private static String SCROLL_REGION(int startRow, int endRow) {
		return "\033[" + startRow + ";" + endRow + "r";
	}

	/**
	 * Set the cursor position to line/column. Note that this is the logical
	 * line and column, so 1, 1 is the top left.
	 */
	private static final String ALTERNATE_SCREEN_ON = "\u001b[?1049h";
	private static final String ALTERNATE_SCREEN_OFF = "\u001b[?1049l";

	private static String CURSOR_POSITION(int line, int column) {
		return "\033[" + line + ";" + column + "H";
	}

	@BeforeAll
	public static void beforeAll() {
		Logger.setUnderTest(true);
	}

	@AfterAll
	public static void afterAll() {
		Logger.setUnderTest(false);
	}

	private ITerminalTextData data;

	private MockTerminalControlForText control = new MockTerminalControlForText();

	private VT100Emulator emulator;

	@BeforeEach
	public void before() {
		data = TerminalTextDataFactory.makeTerminalTextData();
		emulator = new VT100Emulator(data, control, null);
		emulator.resetState();
		emulator.setDimensions(WINDOW_LINES, WINDOW_COLUMNS);
	}

	private Reader input(String... input) {
		StringReader reader = new StringReader(String.join("", input));
		emulator.setInputStreamReader(reader);
		return reader;
	}

	private void run(String... input) {
		Reader reader = input(input);
		emulator.processText();
		try {
			assertEquals(-1, reader.read());
		} catch (IOException e) {
			throw new RuntimeException("Wrap exception so that run can be called in functions", e);
		}
	}

	/**
	 * Convert the data's char arrays into a string that can be compared with
	 * an expected array of lines. Each line in the data has its \0 characters
	 * changed to spaces and then stripTrailing is run.
	 *
	 * @param expectedArray lines that are joined with \n before testing against actual
	 */
	private void assertTextEquals(String... expectedArray) {
		int height = data.getHeight();
		StringJoiner sj = new StringJoiner("\n");
		for (int i = 0; i < height; i++) {
			char[] chars = data.getChars(i);
			String line = chars == null ? "" : new String(chars);
			String lineCleanedup = line.replace('\0', ' ');
			String stripTrailing = lineCleanedup.stripTrailing();
			sj.add(stripTrailing);
		}
		String expected = String.join("\n", expectedArray).stripTrailing();
		String actual = sj.toString().stripTrailing();
		assertEquals(expected, actual);
	}

	private void assertTextEquals(List<String> expected) {
		assertTextEquals(expected.toArray(String[]::new));
	}

	private void assertCursorLocation(int line, int column) {
		assertAll(() -> assertEquals(line, data.getCursorLine(), "cursor line"),
				() -> assertEquals(column, data.getCursorColumn(), "cursor column"));
	}

	/**
	 * This tests the test harness ({@link #assertTextEquals(String...)} as much as the code.
	 */
	@Test
	public void testBasicOperaiion() {
		assertAll(() -> assertCursorLocation(0, 0), () -> assertTextEquals(""));
		run("Hello");
		assertAll(() -> assertCursorLocation(0, 5), () -> assertTextEquals("Hello"));
		emulator.clearTerminal();
		assertAll(() -> assertCursorLocation(0, 0), () -> assertTextEquals(""));

		// test multiline
		emulator.clearTerminal();
		run("Hello 1\r\nHello 2");
		// test both ways of asserting multiple lines
		assertAll(() -> assertCursorLocation(1, 7), //
				() -> assertTextEquals("Hello 1\nHello 2"), //
				() -> assertTextEquals("Hello 1", "Hello 2"));

		// test with no carriage return
		emulator.clearTerminal();
		run("Hello 1\nHello 2");
		assertTextEquals("Hello 1", "       Hello 2");

		// test \b backspace
		emulator.clearTerminal();
		run("Hello 1");
		assertAll(() -> assertCursorLocation(0, 7), () -> assertTextEquals("Hello 1"));
		run("\b\b");
		assertAll(() -> assertCursorLocation(0, 5), () -> assertTextEquals("Hello 1"));
		run(CLEAR_CURSOR_TO_EOL);
		assertAll(() -> assertCursorLocation(0, 5), () -> assertTextEquals("Hello"));
	}

	@Test
	public void testMultiline() {
		List<String> expected = new ArrayList<>();
		for (int i = 0; i < data.getHeight(); i++) {
			String line = "Hello " + i;
			expected.add(line);
			run(line);
			if (i != data.getHeight() - 1) {
				run("\r\n");
			}
		}
		assertTextEquals(expected);

		// add the final newline and check that the first line has been scrolled away
		run("\r\n");
		expected.remove(0);
		assertTextEquals(expected);
	}

	@Test
	public void testScrollBack() {
		data.setMaxHeight(1000);
		List<String> expected = new ArrayList<>();
		for (int i = 0; i < 1000; i++) {
			String line = "Hello " + i;
			run(line + "\r\n");
			expected.add(line);
		}
		expected.remove(0);
		assertTextEquals(expected);
	}

	@Test
	public void testCursorPosition() {
		run(CURSOR_POSITION_TOP_LEFT);
		assertAll(() -> assertCursorLocation(0, 0), () -> assertTextEquals(""));
		run("Hello");
		assertAll(() -> assertCursorLocation(0, 5), () -> assertTextEquals("Hello"));
		run(CURSOR_POSITION_TOP_LEFT);
		assertAll(() -> assertCursorLocation(0, 0), () -> assertTextEquals("Hello"));
		run(CURSOR_POSITION(2, 2));
		assertAll(() -> assertCursorLocation(1, 1), () -> assertTextEquals("Hello"));
		emulator.clearTerminal();

		data.setMaxHeight(1000);
		List<String> expected = new ArrayList<>();
		for (int i = 0; i < WINDOW_LINES; i++) {
			String line = "Hello " + i;
			run(line + "\r\n");
			expected.add(line);
		}
		assertAll(() -> assertCursorLocation(WINDOW_LINES, 0), () -> assertTextEquals(expected));
		run(CURSOR_POSITION_TOP_LEFT);
		// because we added WINDOW_LINES number of lines, and ended it with a \r\n the first
		// line we added is now in the scrollback, so the cursor is at line 1
		assertAll(() -> assertCursorLocation(1, 0), () -> assertTextEquals(expected));
		run("Bye           \r\n");
		expected.set(1, "Bye");
		assertAll(() -> assertCursorLocation(2, 0), () -> assertTextEquals(expected));
		run(CURSOR_POSITION_TOP_LEFT);
		assertAll(() -> assertCursorLocation(1, 0), () -> assertTextEquals(expected));
		run(CURSOR_POSITION(2, 2));
		assertAll(() -> assertCursorLocation(2, 1), () -> assertTextEquals(expected));
	}

	@Test
	public void testTitle() {
		run( //
				TITLE("TITLE1"), //
				"HELLO", //
				TITLE("TITLE2"));
		assertAll(() -> assertTextEquals("HELLO"),
				() -> assertEquals(List.of("TITLE1", "TITLE2"), control.getAllTitles()));
	}

	@Test
	public void testE3ClearScreenAndScrollback() {
		data.setMaxHeight(1000);
		List<String> expected = new ArrayList<>();
		for (int i = 0; i < 1000; i++) {
			String line = "Hello " + i;
			run(line + "\r\n");
			expected.add(line);
		}
		expected.remove(0);
		assertAll(() -> assertCursorLocation(999, 0), () -> assertTextEquals(expected));
		run(CLEAR_ENTIRE_SCREEN_AND_SCROLLBACK);
		assertAll(() -> assertTextEquals(""));
	}

	/**
	 * Runs what "clear" command does on modern Linux installs, including E3 extension
	 */
	@Test
	public void testClear() {
		data.setMaxHeight(1000);
		List<String> expected = new ArrayList<>();
		for (int i = 0; i < 1000; i++) {
			String line = "Hello " + i;
			run(line + "\r\n");
			expected.add(line);
		}
		expected.remove(0);
		assertAll(() -> assertCursorLocation(999, 0), () -> assertTextEquals(expected));
		run(CURSOR_POSITION_TOP_LEFT + CLEAR_ENTIRE_SCREEN + CLEAR_ENTIRE_SCREEN_AND_SCROLLBACK);
		assertAll(() -> assertCursorLocation(0, 0), () -> assertTextEquals(""));
	}

	@Test
	public void testClearScreenKeepsItInHistory() {
		data.setMaxHeight(1000);
		for (int i = 0; i < 30; i++) {
			run("Hello " + i + "\r\n");
		}
		// what ConPTY sends at the start of every connection on Windows
		run(CLEAR_ENTIRE_SCREEN + "\u001b[m" + CURSOR_POSITION_TOP_LEFT + "prompt$ ");
		int top = data.getHeight() - WINDOW_LINES; // first line of the screen
		assertAll(() -> assertEquals("Hello 29", new String(data.getChars(top - 1)).trim()),
				() -> assertEquals("Hello 0", new String(data.getChars(0)).trim()),
				() -> assertEquals("prompt$", new String(data.getChars(top)).replace('\000', ' ').trim()),
				() -> assertCursorLocation(top, 8)); // the top of the screen, counted in the buffer
	}

	/**
	 * Runs what "up arrow" would send back to terminal in less/man/etc.
	 */
	@Test
	public void testScrollReverseNoScrollback() {
		List<String> expected = new ArrayList<>();
		for (int i = 0; i < WINDOW_LINES; i++) {
			String line = "Hello " + i;
			run(line);
			expected.add(line);
			if (i != data.getHeight() - 1) {
				run("\r\n");
			}
		}
		assertAll(() -> assertCursorLocation(WINDOW_LINES - 1, expected.get(expected.size() - 1).length()),
				() -> assertTextEquals(expected));
		run(CURSOR_POSITION_TOP_LEFT);
		assertAll(() -> assertCursorLocation(0, 0), () -> assertTextEquals(expected));
		run(SCROLL_REVERSE);
		expected.add(0, "");
		expected.remove(expected.size() - 1);
		assertAll(() -> assertCursorLocation(0, 0), () -> assertTextEquals(expected));
	}

	/**
	 * Runs what "up arrow" would send back to terminal in less/man/etc.
	 */
	@Test
	public void testScrollReverse() {
		data.setMaxHeight(1000);
		List<String> expected = new ArrayList<>();
		for (int i = 0; i < WINDOW_LINES; i++) {
			String line = "Hello " + i;
			run(line);
			expected.add(line);
			run("\r\n");
		}
		assertAll(() -> assertCursorLocation(WINDOW_LINES, 0), () -> assertTextEquals(expected));
		run(CURSOR_POSITION_TOP_LEFT);
		assertAll(() -> assertCursorLocation(1, 0), () -> assertTextEquals(expected));
		run(SCROLL_REVERSE);
		expected.add(1, "");
		assertAll(() -> assertCursorLocation(1, 0), () -> assertTextEquals(expected));
		run("New text on top line following scroll reverse");
		expected.set(1, "New text on top line following scroll reverse");
		assertAll(() -> assertCursorLocation(1, expected.get(1).length()), () -> assertTextEquals(expected));
	}

	/**
	 * Runs what "up arrow" would send back to terminal in less/man/etc.
	 * but with a scrolling region set
	 */
	@Test
	public void testScrollReverseScrollingRegion() {
		data.setMaxHeight(1000);
		List<String> expected = new ArrayList<>();
		for (int i = 0; i < WINDOW_LINES; i++) {
			String line = "Hello " + i;
			run(line);
			expected.add(line);
			run("\r\n");
		}
		assertAll(() -> assertCursorLocation(WINDOW_LINES, 0), () -> assertTextEquals(expected));
		run(CURSOR_POSITION_TOP_LEFT + "\n");
		assertAll(() -> assertCursorLocation(2, 0), () -> assertTextEquals(expected));
		run(SCROLL_REGION(2, WINDOW_LINES));
		run(SCROLL_REVERSE);
		expected.add(2, "");
		assertAll(() -> assertCursorLocation(2, 0), () -> assertTextEquals(expected));
		run("New text on top line following scroll reverse");
		expected.set(2, "New text on top line following scroll reverse");
		assertAll(() -> assertCursorLocation(2, expected.get(2).length()), () -> assertTextEquals(expected));
	}

	@Test
	public void testStringTerminator() {
		run( //
				TITLE_ST_BEL("TITLE1"), //
				"HELLO1", //
				TITLE_ST_ESC_BACKSLASH("TITLE2"), //
				"HELLO2", //
				TITLE_ST_BEL("TITLE3"), //
				"HELLO3", //
				TITLE_ST_ESC_BACKSLASH("TITLE4") //
		);
		assertAll(() -> assertTextEquals("HELLO1HELLO2HELLO3"),
				() -> assertEquals(List.of("TITLE1", "TITLE2", "TITLE3", "TITLE4"), control.getAllTitles()));
	}

	@Test
	public void testMalformedStringTerminator() {
		run( //
				TITLE_ST_ESC_BACKSLASH("TITLE1"), //
				"\033]0;" + "TITLE1" + "\033X", //
				TITLE_ST_ESC_BACKSLASH("TITLE2"), //
				"HELLO" //
		);
		assertAll(() -> assertTextEquals("HELLO"),
				() -> assertEquals(List.of("TITLE1", "TITLE2"), control.getAllTitles()));
	}

	@Test
	public void testEscapeInsideOscStartsNewSequence() {
		// as xterm: ESC not followed by '\' drops the OSC, and "[31m" is a color, not text
		run("\033]0;t\033[31mRED");
		assertAll(() -> assertTextEquals("RED"), () -> assertEquals(List.of(), control.getAllTitles()));
	}

	private static TerminalDirectory dir(String host, String path) {
		return new TerminalDirectory(host, path);
	}

	@Test
	public void testWorkingDirectory() {
		run("\033]7;file://host/home/me\007", "A", //
				"\033]7;file:///tmp/a%20b\033\\", "B", // empty host, ST
				"\033]7;file://localhost/tmp/%ED%95%9C%EA%B8%80%20dir\007", // UTF-8 bytes
				"\033]7;file://h/tmp/\ud55c\uae00 dir+x%25\007", // as sent unencoded, '+' and "%25"
				"\033]7;FILE://h/\007");
		assertAll(() -> assertTextEquals("AB"),
				() -> assertEquals(List.of(dir("host", "/home/me"), dir("", "/tmp/a b"), dir("localhost", "/tmp/\ud55c\uae00 dir"),
						dir("h", "/tmp/\ud55c\uae00 dir+x%"), dir("h", "/")), control.getDirectories()),
				() -> assertEquals(List.of(), control.getAllTitles()));
	}

	@Test
	public void testWorkingDirectoryMalformed() {
		run("\033]7;/tmp\007", // not a URI
				"\033]7;http://h/tmp\007", // not file:
				"\033]7;file://host\007", // no path
				"\033]7;file:///tmp/%zz\007", // bad escape
				"\033]7;file:///tmp/%4\007", // cut escape
				"\033]7;file:///tmp/%FF\007", // not UTF-8
				"\033]7;file:///tmp/%0Aetc\007", // control character
				"\033]7;\007", "\033]\007", "\033]7\007", // empty
				"\033]7;file:///" + "a".repeat(VT100Emulator.MAX_OS_COMMAND) + "\007", // too long
				"OK", "\033]7;file:///ok\007");
		assertAll(() -> assertTextEquals("OK"), () -> assertEquals(List.of(dir("", "/ok")), control.getDirectories()));
	}

	@Test
	public void testWorkingDirectorySplitAcrossReads() {
		run("X\033]7;file://h/tmp/%E");
		run("D%95%9C/y\033");
		run("\\Y");
		assertAll(() -> assertTextEquals("XY"), () -> assertEquals(List.of(dir("h", "/tmp/\ud55c/y")), control.getDirectories()));
	}

	@Test
	public void testNotifications() {
		run("A\007B", // bell
				"\033]9;Build done\007", // iTerm2
				"\033]9;4;1;50\007", "\033]9;12\007", // ConEmu progress and others: no notification
				"\033]777;notify;Claude;Needs input; now\033\\", // body with ';', ST
				"\033]777;notify;OnlyTitle\007", //
				"\033]9;\u0001re\bd\u0085\u202e\007", // control and bidi characters dropped
				"\033]9;" + "x".repeat(1000) + "\007");
		assertAll(() -> assertTextEquals("AB"),
				() -> assertEquals(List.of("null|null", "null|Build done", "Claude|Needs input; now", "OnlyTitle|null",
						"null|red", "null|" + "x".repeat(VT100Emulator.MAX_NOTIFICATION - 1) + "\u2026"),
						control.getNotifications()),
				() -> assertEquals(List.of(), control.getAllTitles()));
	}

	@Test
	public void testClipboard() {
		String big = "\ud55c".repeat(200000); // 600 KB of UTF-8, past the limit of other OSCs
		String bigBase64 = Base64.getEncoder().encodeToString(big.getBytes(StandardCharsets.UTF_8));
		run("\033]52;c;" + Base64.getEncoder().encodeToString("hi \ud55c\uae00\n".getBytes(StandardCharsets.UTF_8)) + "\007",
				"\033]52;c;?\007", // a read: never answered
				"\033]52;;aGk=\033\\", // no target, ST
				"\033]52;c;!!!\007", "\033]52;c;\007", // not base64, empty
				"\033]52;c;" + bigBase64 + "\007", //
				"\033]52;c;" + "A".repeat(VT100Emulator.MAX_CLIPBOARD_OSC + 4) + "\007", // too long
				"OK");
		assertAll(() -> assertTextEquals("OK"),
				() -> assertEquals(List.of("hi \ud55c\uae00\n", "hi", big), control.getClipboard()));
	}

	@Test
	public void testAlternateScreen() {
		data.setMaxHeight(1000);
		run("Hello 1\r\nHello 2");
		assertAll(() -> assertCursorLocation(1, 7), () -> assertTextEquals("Hello 1", "Hello 2"));
		// a full screen program takes the screen: it starts blank and no history is kept
		run(ALTERNATE_SCREEN_ON);
		assertAll(() -> assertCursorLocation(0, 0), () -> assertTextEquals(""));
		assertEquals(WINDOW_LINES, data.getHeight());
		run("Full screen");
		assertTextEquals("Full screen");
		run(ALTERNATE_SCREEN_ON); // programs do ask twice
		assertTextEquals("Full screen");
		// and gives it back with what was there before, cursor included
		run(ALTERNATE_SCREEN_OFF);
		assertAll(() -> assertCursorLocation(1, 7), () -> assertTextEquals("Hello 1", "Hello 2"));
		run(ALTERNATE_SCREEN_OFF); // harmless twice as well
		assertAll(() -> assertCursorLocation(1, 7), () -> assertTextEquals("Hello 1", "Hello 2"));
	}

	@Test
	public void testDecPrivateModes() {
		// the cursor hidden and shown again by a program
		run("\u001b[?25l");
		assertFalse(control.isCursorShown());
		run("\u001b[?25h");
		assertTrue(control.isCursorShown());
		// several modes in one sequence, as ncurses sends them: every one of them counts
		run("\u001b[?2004;25l");
		assertAll(() -> assertFalse(control.isCursorShown()), () -> assertFalse(control.isBracketedPaste()));
		run("\u001b[?25;2004h");
		assertAll(() -> assertTrue(control.isCursorShown()), () -> assertTrue(control.isBracketedPaste()));
	}

	@Test
	public void testMouseModes() {
		// how ncurses turns the mouse on: both modes in one sequence
		run("\u001b[?1006;1000h");
		assertAll(() -> assertEquals(1000, control.getMouseMode()), () -> assertTrue(control.isSgrMouseEncoding()));
		run("\u001b[?1002h");
		assertEquals(1002, control.getMouseMode());
		run("\u001b[?1000;1006l");
		assertAll(() -> assertEquals(0, control.getMouseMode()), () -> assertFalse(control.isSgrMouseEncoding()));
		run("\u001b[?1004h");
		assertTrue(control.isFocusReporting());
		run("\u001b[?1004l");
		assertFalse(control.isFocusReporting());
	}

	@Test
	public void testFaint() {
		// SGR 2 makes the text faint; 22 takes both bold and faint off, as in xterm
		run("\u001b[2mfaint\u001b[22m normal \u001b[1;2mboth\u001b[22mplain");
		assertTrue(data.getStyle(0, 0).isDim());
		assertFalse(data.getStyle(0, 0).isBold());
		assertFalse(data.getStyle(0, 6).isDim());
		assertTrue(data.getStyle(0, 13).isDim());
		assertTrue(data.getStyle(0, 13).isBold());
		assertFalse(data.getStyle(0, 17).isDim());
		assertFalse(data.getStyle(0, 17).isBold());
	}

	@Test
	public void testModifiedSpecialKeys() {
		// xterm's spelling: the plain key with the modifier as a parameter, 2 shift, 3 alt, 5 control
		assertEquals("\u001b[1;5A", VT100TerminalControl.modifiedSpecialKey(0x1000001, org.eclipse.swt.SWT.CTRL));
		assertEquals("\u001b[1;2F", VT100TerminalControl.modifiedSpecialKey(0x1000008, org.eclipse.swt.SWT.SHIFT));
		assertEquals("\u001b[5;3~", VT100TerminalControl.modifiedSpecialKey(0x1000005, org.eclipse.swt.SWT.ALT));
		assertEquals("\u001b[1;6P", VT100TerminalControl.modifiedSpecialKey(0x100000a, org.eclipse.swt.SWT.CTRL | org.eclipse.swt.SWT.SHIFT));
		assertEquals("\u001b[15;5~", VT100TerminalControl.modifiedSpecialKey(0x100000e, org.eclipse.swt.SWT.CTRL));
		assertEquals("\u001b[24;2~", VT100TerminalControl.modifiedSpecialKey(0x1000015, org.eclipse.swt.SWT.SHIFT));
		// no modifier held that xterm can spell: not this way
		assertEquals(null, VT100TerminalControl.modifiedSpecialKey(0x1000001, 0));
	}

	@Test
	public void testResetStateLeavesAlternateScreen() {
		data.setMaxHeight(1000);
		run("Hello 1\r\nHello 2");
		run(ALTERNATE_SCREEN_ON);
		run("Full screen");
		// the connection ends while the program has the screen; the next one starts
		// on the normal screen, with its history back
		emulator.resetState();
		assertAll(() -> assertTextEquals("Hello 1", "Hello 2"), () -> assertEquals(1000, data.getMaxHeight()));
		// a late request to leave does not bring back the old program's screen
		run(ALTERNATE_SCREEN_OFF);
		assertTextEquals("Hello 1", "Hello 2");
		for (int i = 0; i < WINDOW_LINES; i++) {
			run("\r\nmore");
		}
		assertEquals(WINDOW_LINES + 2, data.getHeight()); // history accumulates again
	}

	@Test
	public void testRepeatedAlternateScreenOffKeepsMargins() {
		run("A\r\nB\r\nC");
		run(SCROLL_REGION(1, 2));
		run(ALTERNATE_SCREEN_OFF); // already on the normal screen: must change nothing
		run(CURSOR_POSITION(2, 1), "\n");
		// the newline on the bottom margin scrolls rows 1 and 2 only
		assertTextEquals("B", "", "C");
	}

	@Test
	public void testBufferLineLimitWhileAlternateScreen() {
		data.setMaxHeight(1000);
		emulator.setBufferLineLimit(300);
		assertEquals(300, data.getMaxHeight());
		run(ALTERNATE_SCREEN_ON);
		// a preference change must not give the alternate screen a history
		emulator.setBufferLineLimit(500);
		assertEquals(WINDOW_LINES, data.getMaxHeight());
		for (int i = 0; i < 2 * WINDOW_LINES; i++) {
			run("line\r\n");
		}
		assertEquals(WINDOW_LINES, data.getHeight());
		// and the new limit is the normal screen's once it is back
		run(ALTERNATE_SCREEN_OFF);
		assertEquals(500, data.getMaxHeight());
	}

	@Test
	public void testResetStateEndsDecPrivateModes() {
		run("\u001b[?25l\u001b[?2004h");
		assertAll(() -> assertFalse(control.isCursorShown()), () -> assertTrue(control.isBracketedPaste()));
		// the connection ends while the program has them set; the next one starts clean
		emulator.resetState();
		assertAll(() -> assertTrue(control.isCursorShown()), () -> assertFalse(control.isBracketedPaste()));
	}

	@Test
	public void testResetStateEndsMouseModes() {
		run("\u001b[?1002;1006;1004h\u001b[?2026h");
		assertAll(() -> assertEquals(1002, control.getMouseMode()), () -> assertTrue(control.isSgrMouseEncoding()),
				() -> assertTrue(control.isFocusReporting()), () -> assertTrue(control.isSynchronizedOutput()));
		// a program cut off while listening to the mouse, or in the middle of a
		// synchronized update, must not leave the next connection with it
		emulator.resetState();
		assertAll(() -> assertEquals(0, control.getMouseMode()), () -> assertFalse(control.isSgrMouseEncoding()),
				() -> assertFalse(control.isFocusReporting()), () -> assertFalse(control.isSynchronizedOutput()));
	}
}
