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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.insjang.terminal.model.ITerminalTextDataReadOnly;

/**
 * Finds text in the lines of a terminal, scrollback included. A line that ran on
 * to the next one is searched as one with it, and each match is given in cells:
 * a wide character covers two, so its match ends in the second.
 */
public final class TerminalFind {

	/** A match from its first cell to its last, both included. */
	public record Match(int startLine, int startColumn, int endLine, int endColumn) {
		/** @return whether this match starts before the given cell */
		public boolean startsBefore(int line, int column) {
			return startLine < line || startLine == line && startColumn < column;
		}
	}

	private TerminalFind() {
	}

	/**
	 * @param query the text, or a regular expression
	 * @throws java.util.regex.PatternSyntaxException for a bad regular expression
	 */
	public static Pattern pattern(String query, boolean caseSensitive, boolean regex) {
		int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
		return Pattern.compile(regex ? query : Pattern.quote(query), flags);
	}

	/** A search ran past its time: a regular expression that backtracks without end. */
	public static final class TooSlowException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		TooSlowException() {
			super(null, null, false, false);
		}
	}

	/** The text of one logical line (rows that ran on joined) with the cells of its chars. */
	private record Logical(CharSequence text, int[][] cells, int first, int last) {
	}

	// the text a matcher reads, looking at the clock every few thousand chars
	private record Timed(CharSequence text, long deadline, int[] reads) implements CharSequence {
		@Override
		public char charAt(int index) {
			if ((++reads[0] & 0xfff) == 0 && System.nanoTime() > deadline) {
				throw new TooSlowException();
			}
			return text.charAt(index);
		}

		@Override
		public int length() {
			return text.length();
		}

		@Override
		public CharSequence subSequence(int start, int end) {
			return new Timed(text.subSequence(start, end), deadline, reads);
		}

		@Override
		public String toString() {
			return text.toString();
		}
	}

	/**
	 * @return the matches in line order, at most <code>limit</code>; empty matches are skipped
	 */
	public static List<Match> findAll(ITerminalTextDataReadOnly data, Pattern pattern, int limit) {
		List<Match> matches = new ArrayList<>();
		for (int line = 0; line < data.getHeight() && matches.size() < limit; line = logical(data, line).last() + 1) {
			for (Match m : matches(logical(data, line), pattern, Long.MAX_VALUE)) {
				if (matches.size() < limit) {
					matches.add(m);
				}
			}
		}
		return matches;
	}

	/**
	 * The match above <code>from</code> (<code>up</code>) or below it, going round at the
	 * top and the bottom; with no <code>from</code>, the last one (up) or the first. Reads
	 * from there on, never more of the buffer than it needs.
	 *
	 * @throws TooSlowException past the deadline (System.nanoTime)
	 * @return the match, or null where there is none
	 */
	public static Match next(ITerminalTextDataReadOnly data, Pattern pattern, Match from, boolean up, long deadline) {
		int height = data.getHeight();
		if (height == 0) {
			return null;
		}
		int start = from != null ? logicalStart(data, Math.min(from.startLine(), height - 1))
				: up ? logicalStart(data, height - 1) : 0;
		int line = start;
		// the line of from twice: its matches on the near side first, at the end the ones beyond it
		for (int visit = 0;; visit++) {
			Logical logical = logical(data, line);
			List<Match> matches = matches(logical, pattern, deadline);
			boolean first = visit == 0, last = visit > 0 && line == start;
			Match found = null;
			for (Match m : matches) {
				boolean before = from != null && m.startsBefore(from.startLine(), from.startColumn());
				boolean after = from != null && from.startsBefore(m.startLine(), m.startColumn());
				// first visit: the near side of from; last (round again): the far side, from itself too
				boolean ok = from == null || !first && !last || (first ? (up ? before : after) : (up ? !before : !after));
				if (ok && (up || found == null)) {
					found = m; // up: the last one that will do, down: the first
				}
			}
			if (found != null || last || from == null && visit > 0 && line == start) {
				return found;
			}
			if (up) {
				line = logical.first() == 0 ? logicalStart(data, height - 1) : logicalStart(data, logical.first() - 1);
			} else {
				line = logical.last() + 1 >= height ? 0 : logical.last() + 1;
			}
			if (from == null && line == start) {
				return null; // round once without a match
			}
		}
	}

	/**
	 * @return the number of matches (counting stops past <code>limit</code>) and how many
	 *         of them start before <code>at</code>
	 * @throws TooSlowException past the deadline
	 */
	public static int[] count(ITerminalTextDataReadOnly data, Pattern pattern, Match at, int limit, long deadline) {
		int total = 0, before = 0;
		for (int line = 0; line < data.getHeight() && total <= limit; line = logical(data, line).last() + 1) {
			for (Match m : matches(logical(data, line), pattern, deadline)) {
				total++;
				if (at != null && m.startsBefore(at.startLine(), at.startColumn())) {
					before++;
				}
			}
		}
		return new int[] { total, before };
	}

	private static int logicalStart(ITerminalTextDataReadOnly data, int line) {
		while (line > 0 && data.isWrappedLine(line - 1)) {
			line--;
		}
		return line;
	}

	private static Logical logical(ITerminalTextDataReadOnly data, int first) {
		StringBuilder text = new StringBuilder();
		// per char of text: its line, its first cell and its last cell
		int[][] cells = new int[3][256];
		int line = first;
		do {
			cells = appendLine(data, line, text, cells);
		} while (data.isWrappedLine(line) && line + 1 < data.getHeight() && ++line > 0);
		return new Logical(text, cells, first, line);
	}

	private static List<Match> matches(Logical logical, Pattern pattern, long deadline) {
		List<Match> found = new ArrayList<>();
		Matcher m = pattern.matcher(new Timed(logical.text(), deadline, new int[1]));
		int[][] cells = logical.cells();
		while (find(m)) {
			if (m.end() > m.start()) {
				int s = m.start(), e = m.end() - 1;
				found.add(new Match(cells[0][s], cells[1][s], cells[0][e], cells[2][e]));
			}
		}
		if (System.nanoTime() > deadline) {
			throw new TooSlowException();
		}
		return found;
	}

	// an expression that recurses too deep (a long run of a group alternative) ends as a slow one does
	private static boolean find(Matcher m) {
		try {
			return m.find();
		} catch (StackOverflowError e) {
			throw new TooSlowException();
		}
	}

	private static int[][] appendLine(ITerminalTextDataReadOnly data, int line, StringBuilder text, int[][] cells) {
		char[] chars = data.getChars(line);
		if (chars == null) {
			return cells;
		}
		String row = new String(chars);
		int end = row.length();
		while (end > 0 && row.charAt(end - 1) == '\000') {
			end--; // never written: no text
		}
		for (int col = 0; col < end; col++) {
			if (CharWidth.isFiller(row, col)) {
				continue;
			}
			String cluster = col + 1 < row.length() ? data.getCluster(line, col) : null;
			String s = cluster != null ? cluster : String.valueOf(row.charAt(col) == '\000' ? ' ' : row.charAt(col));
			int last = cluster != null || col + 1 < row.length() && CharWidth.isFiller(row, col + 1) ? col + 1 : col;
			for (int i = 0; i < s.length(); i++) {
				int at = text.length();
				if (at == cells[0].length) {
					for (int k = 0; k < 3; k++) {
						cells[k] = Arrays.copyOf(cells[k], at * 2);
					}
				}
				cells[0][at] = line;
				cells[1][at] = col;
				cells[2][at] = last;
				text.append(s.charAt(i));
			}
			if (cluster != null) {
				col++;
			}
		}
		return cells;
	}
}
