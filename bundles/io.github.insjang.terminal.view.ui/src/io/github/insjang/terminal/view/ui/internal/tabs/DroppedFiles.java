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
	 * @param processPath The path of the shell process of the terminal or <code>null</code> if unknown.
	 * @param windows Whether Eclipse runs on Windows.
	 * @return The text to type into the terminal.
	 */
	public static String format(String[] paths, String processPath, boolean windows) {
		String shell = processPath == null ? "" : processPath.replace('\\', '/').toLowerCase(Locale.ROOT); //$NON-NLS-1$
		UnaryOperator<String> argument;
		if (!windows) {
			argument = DroppedFiles::posixQuote;
		} else if (shell.endsWith("/wsl.exe") || shell.endsWith("/system32/bash.exe")) { //$NON-NLS-1$ //$NON-NLS-2$
			argument = p -> posixQuote(toWsl(p));
		} else if (shell.endsWith("/sh.exe") || shell.endsWith("/bash.exe")) { //$NON-NLS-1$ //$NON-NLS-2$
			argument = p -> posixQuote(toMsys(p));
		} else {
			// cmd.exe and PowerShell
			argument = p -> p.contains(" ") ? '"' + p + '"' : p; //$NON-NLS-1$
		}
		return Arrays.stream(paths).map(argument).collect(Collectors.joining(" ")); //$NON-NLS-1$
	}

	/** C:\a\b is /mnt/c/a/b, \\wsl$\Ubuntu\home\a (or \\wsl.localhost\...) is /home/a. */
	static String toWsl(String path) {
		String p = path.replace('\\', '/');
		String lower = p.toLowerCase(Locale.ROOT);
		if (lower.startsWith("//wsl$/") || lower.startsWith("//wsl.localhost/")) { //$NON-NLS-1$ //$NON-NLS-2$
			int slash = p.indexOf('/', p.indexOf('/', 2) + 1);
			return slash == -1 ? "/" : p.substring(slash); //$NON-NLS-1$
		}
		return hasDrive(p) ? "/mnt/" + Character.toLowerCase(p.charAt(0)) + p.substring(2) : p; //$NON-NLS-1$
	}

	/** C:\a\b is /c/a/b, as Git Bash sees it. */
	static String toMsys(String path) {
		String p = path.replace('\\', '/');
		return hasDrive(p) ? "/" + Character.toLowerCase(p.charAt(0)) + p.substring(2) : p; //$NON-NLS-1$
	}

	private static boolean hasDrive(String p) {
		return p.length() >= 2 && p.charAt(1) == ':' && Character.isLetter(p.charAt(0));
	}

	private static String posixQuote(String p) {
		return "'" + p.replace("'", "'\\''") + "'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
	}
}
