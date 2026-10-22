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
package io.github.insjang.terminal.view.ui.internal.tabs;

import java.util.Arrays;
import java.util.Locale;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Turns files dropped onto a terminal into the text typed into it: their paths, quoted for the
 * shell and translated to the paths the shell sees.
 */
public final class DroppedFiles {

	private DroppedFiles() {
	}

	/**
	 * Returns the dropped paths as shell arguments separated by spaces.
	 *
	 * @param paths The dropped file paths. Must not be <code>null</code>.
	 * @param processPath The path of the shell process of the terminal ("/bin/sh" for SSH) or <code>null</code> if
	 *            unknown (taken as cmd.exe on Windows).
	 * @param windows Whether Eclipse runs on Windows.
	 * @return The text to type into the terminal.
	 */
	public static String format(String[] paths, String processPath, boolean windows) {
		String shell = processPath == null ? "" : processPath.replace('\\', '/').toLowerCase(Locale.ROOT); //$NON-NLS-1$
		UnaryOperator<String> argument;
		if (!windows || shell.startsWith("/") && !shell.startsWith("//")) { //$NON-NLS-1$ //$NON-NLS-2$
			// a remote shell from Windows (SSH) too
			argument = DroppedFiles::posixQuote;
		} else if (shell.matches("(.*/)?wsl(\\.exe)?|bash(\\.exe)?|.*/system32/bash(\\.exe)?")) { //$NON-NLS-1$
			// bash.exe without a folder: System32 comes first in the PATH
			argument = p -> posixQuote(ShellPaths.toWsl(p));
		} else if (shell.matches("(.*/)?(sh|bash)(\\.exe)?")) { //$NON-NLS-1$
			argument = p -> posixQuote(ShellPaths.toMsys(p));
		} else if (shell.matches("(.*/)?(powershell|pwsh)(\\.exe)?")) { //$NON-NLS-1$
			// single quotes: in double quotes PowerShell would run a $(...) in the name; it takes the curly quotes
			// as single quotes too, so every one of them is doubled
			argument = p -> "'" + p.replaceAll("(['\u2018\u2019\u201a\u201b])", "$1$1") + "'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		} else {
			// cmd.exe, or unknown: a name never holds a double quote, and inside them & | < > ^ are plain text
			argument = p -> '"' + p + '"';
		}
		// a control character in a name (a newline is legal on Linux) must not reach the shell as a key
		return Arrays.stream(paths).map(p -> p.replaceAll("\\p{Cntrl}", "")).map(argument).collect(Collectors.joining(" ")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	private static String posixQuote(String p) {
		return "'" + p.replace("'", "'\\''") + "'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
	}
}
