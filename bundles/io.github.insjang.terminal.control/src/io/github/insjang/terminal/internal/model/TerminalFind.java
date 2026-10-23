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

	/**
	 * @return the matches in line order, at most <code>limit</code>; empty matches are skipped
	 */
	public static List<Match> findAll(ITerminalTextDataReadOnly data, Pattern pattern, int limit) {
		List<Match> matches = new ArrayList<>();
		StringBuilder text = new StringBuilder();
		// per char of text: its line, its first cell and its last cell
		int[][] cells = new int[3][256];
		int height = data.getHeight();
		int line = 0;
		while (line < height && matches.size() < limit) {
			text.setLength(0);
			do {
				cells = appendLine(data, line, text, cells);
			} while (data.isWrappedLine(line++) && line < height);
			Matcher m = pattern.matcher(text);
			while (m.find() && matches.size() < limit) {
				if (m.end() > m.start()) {
					int s = m.start(), e = m.end() - 1;
					matches.add(new Match(cells[0][s], cells[1][s], cells[0][e], cells[2][e]));
				}
			}
		}
		return matches;
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
