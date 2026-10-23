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

import static io.github.insjang.terminal.model.ITerminalTextDataReadOnly.MARK_OUTPUT;
import static io.github.insjang.terminal.model.ITerminalTextDataReadOnly.MARK_PROMPT;

import io.github.insjang.terminal.model.ITerminalTextDataReadOnly;

/**
 * Finds the shell integration marks (OSC 133) in the lines of a terminal.
 */
public final class ShellMarks {
	private ShellMarks() {
	}

	/** @return the last prompt line before <code>line</code>, or -1 */
	public static int previousPrompt(ITerminalTextDataReadOnly data, int line) {
		for (int l = Math.min(line, data.getHeight()) - 1; l >= 0; l--) {
			if ((data.getLineMark(l) & MARK_PROMPT) != 0) {
				return l;
			}
		}
		return -1;
	}

	/** @return the first prompt line after <code>line</code>, or -1 */
	public static int nextPrompt(ITerminalTextDataReadOnly data, int line) {
		for (int l = Math.max(line + 1, 0); l < data.getHeight(); l++) {
			if ((data.getLineMark(l) & MARK_PROMPT) != 0) {
				return l;
			}
		}
		return -1;
	}

	/**
	 * The output of the last command that has started: from its output mark to the
	 * line before the next prompt (or the last line), blank lines at the end left out.
	 *
	 * @return the first and the last line, or null for none
	 */
	public static int[] lastOutput(ITerminalTextDataReadOnly data) {
		int start = -1;
		for (int l = data.getHeight() - 1; l >= 0 && start < 0; l--) {
			int mark = data.getLineMark(l);
			if ((mark & MARK_OUTPUT) != 0) {
				start = l;
			}
		}
		// on a prompt line: the next prompt came right after, the command printed nothing
		if (start < 0 || (data.getLineMark(start) & MARK_PROMPT) != 0) {
			return null;
		}
		int next = nextPrompt(data, start - 1);
		int end = (next < 0 ? data.getHeight() : next) - 1;
		while (end >= start && isBlank(data.getChars(end))) {
			end--;
		}
		return end < start ? null : new int[] { start, end };
	}

	private static boolean isBlank(char[] chars) {
		if (chars != null) {
			for (char c : chars) {
				if (c != '\000' && c != ' ') {
					return false;
				}
			}
		}
		return true;
	}
}
