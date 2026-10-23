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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.junit.jupiter.api.Test;

import io.github.insjang.terminal.internal.emulator.MockTerminalControlForText;
import io.github.insjang.terminal.internal.emulator.VT100Emulator;
import io.github.insjang.terminal.internal.model.TerminalFind.Match;
import io.github.insjang.terminal.model.ITerminalTextData;
import io.github.insjang.terminal.model.TerminalTextDataFactory;

public class TerminalFindTest {

	private static ITerminalTextData terminal(int columns, String text) {
		ITerminalTextData data = TerminalTextDataFactory.makeTerminalTextData();
		data.setMaxHeight(100);
		VT100Emulator emulator = new VT100Emulator(data, new MockTerminalControlForText(), null);
		emulator.resetState();
		emulator.setDimensions(5, columns);
		emulator.setInputStreamReader(new StringReader(text));
		emulator.processText();
		return data;
	}

	private static List<Match> find(ITerminalTextData data, String query, boolean caseSensitive, boolean regex) {
		return TerminalFind.findAll(data, TerminalFind.pattern(query, caseSensitive, regex), 1000);
	}

	@Test
	public void testPlain() {
		ITerminalTextData data = terminal(40, "Error one\r\nno error\r\nERROR: x");
		assertEquals(List.of(new Match(0, 0, 0, 4), new Match(1, 3, 1, 7), new Match(2, 0, 2, 4)),
				find(data, "error", false, false));
		assertEquals(List.of(new Match(1, 3, 1, 7)), find(data, "error", true, false));
		assertEquals(List.of(), find(data, "e.r", false, false)); // not a regex
	}

	@Test
	public void testWideCharacters() {
		// 한 and 글 cover two cells each: "ab" is in cells 5 and 6
		ITerminalTextData data = terminal(40, "한글 ab 한");
		assertEquals(List.of(new Match(0, 0, 0, 3)), find(data, "한글", false, false));
		assertEquals(List.of(new Match(0, 5, 0, 6)), find(data, "ab", false, false));
		assertEquals(List.of(new Match(0, 2, 0, 9)), find(data, "글 ab 한", false, false));
	}

	@Test
	public void testWrappedLine() {
		// 12 characters on 10 columns: "world" runs from the first row on to the second
		ITerminalTextData data = terminal(10, "hello world!");
		assertEquals(List.of(new Match(0, 6, 1, 0)), find(data, "world", false, false));
	}

	@Test
	public void testRegex() {
		ITerminalTextData data = terminal(40, "exit 0\r\nexit 12\r\n");
		assertEquals(List.of(new Match(1, 0, 1, 6)), find(data, "exit [1-9]\\d*", false, true));
		assertEquals(List.of(), find(data, "^", false, true)); // empty matches are skipped
		assertThrows(PatternSyntaxException.class, () -> TerminalFind.pattern("(", false, true));
	}

	@Test
	public void testLimit() {
		ITerminalTextData data = terminal(40, "aaaa");
		assertEquals(2, TerminalFind.findAll(data, TerminalFind.pattern("a", false, false), 2).size());
	}

	@Test
	public void testNextGoesRoundFromWhereItIs() {
		ITerminalTextData data = terminal(40, "a1\r\nxx\r\na2 a3\r\n\ud55ca4");
		Pattern p = TerminalFind.pattern("a\\d", false, true);
		long t = Long.MAX_VALUE;
		Match a1 = new Match(0, 0, 0, 1), a2 = new Match(2, 0, 2, 1), a3 = new Match(2, 3, 2, 4), a4 = new Match(3, 2, 3, 3);
		assertEquals(a4, TerminalFind.next(data, p, null, true, t)); // from the bottom: the last one
		assertEquals(a1, TerminalFind.next(data, p, null, false, t));
		assertEquals(a3, TerminalFind.next(data, p, a4, true, t));
		assertEquals(a2, TerminalFind.next(data, p, a3, true, t)); // on the same line
		assertEquals(a4, TerminalFind.next(data, p, a1, true, t)); // round at the top
		assertEquals(a1, TerminalFind.next(data, p, a4, false, t)); // round at the bottom
		assertEquals(a3, TerminalFind.next(data, p, a2, false, t));
		Match only = new Match(1, 0, 1, 1);
		assertEquals(only, TerminalFind.next(data, TerminalFind.pattern("xx", false, false), only, true, t)); // itself
		assertNull(TerminalFind.next(data, TerminalFind.pattern("zz", false, false), null, true, t));
		assertArrayEquals(new int[] { 4, 2 }, TerminalFind.count(data, p, a3, 100, t));
	}

	@Test
	public void testPastTheCapTheLastMatchIsStillFound() {
		// more matches than any cap: the last one is found from the bottom, not the 10000th from the top
		ITerminalTextData data = TerminalTextDataFactory.makeTerminalTextData();
		data.setMaxHeight(20000);
		VT100Emulator emulator = new VT100Emulator(data, new MockTerminalControlForText(), null);
		emulator.resetState();
		emulator.setDimensions(5, 40);
		emulator.setInputStreamReader(new StringReader("a\r\n".repeat(15000) + "last a"));
		emulator.processText();
		Pattern p = TerminalFind.pattern("a", false, false);
		assertEquals(data.getHeight() - 1, TerminalFind.next(data, p, null, true, Long.MAX_VALUE).startLine());
		assertEquals(10001, TerminalFind.count(data, p, null, 10000, Long.MAX_VALUE)[0]); // stops past the cap
	}

	@Test
	public void testDeepRegexStops() {
		// a group alternative over a long logical line recurses past the stack: reported as too slow
		ITerminalTextData data = TerminalTextDataFactory.makeTerminalTextData();
		data.setMaxHeight(5000);
		VT100Emulator emulator = new VT100Emulator(data, new MockTerminalControlForText(), null);
		emulator.resetState();
		emulator.setDimensions(5, 80);
		emulator.setInputStreamReader(new StringReader("ab".repeat(100000)));
		emulator.processText();
		Pattern p = TerminalFind.pattern("(a|b)*c", false, true);
		assertThrows(TerminalFind.TooSlowException.class,
				() -> TerminalFind.next(data, p, null, true, System.nanoTime() + 5_000_000_000L));
	}

	@Test
	public void testRunawayRegexStops() {
		ITerminalTextData data = terminal(80, "a".repeat(60) + "!");
		Pattern p = TerminalFind.pattern("(.*a){20}x", false, true); // backtracks for ages
		long start = System.nanoTime();
		assertThrows(TerminalFind.TooSlowException.class,
				() -> TerminalFind.next(data, p, null, true, System.nanoTime() + 200_000_000L));
		assertTrue((System.nanoTime() - start) / 1000000 < 2000);
	}
}
