/*******************************************************************************
 * Copyright (c) 2026 insjang and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package io.github.insjang.terminal.view.ui.internal.tabs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Paths as the shells of local terminals on Windows write them (WSL, Git Bash, a file URI), and as
 * Windows writes them. Pure string work, the same on every system, except {@link #wslDistributions()}.
 */
public final class ShellPaths {
	private static final Pattern WSL_DRIVE = Pattern.compile("/mnt/([a-zA-Z])(/.*)?"); //$NON-NLS-1$
	private static final Pattern MSYS_DRIVE = Pattern.compile("/([a-zA-Z])(/.*)?"); //$NON-NLS-1$
	private static final Pattern URI_DRIVE = Pattern.compile("/([a-zA-Z]):([/\\\\].*)?"); //$NON-NLS-1$
	private static List<String> distributions;

	private ShellPaths() {
	}

	/**
	 * The WSL distribution a shell runs in: the name after -d or --distribution of wsl.exe, "" for the
	 * default one (wsl.exe without it, or the old System32\bash.exe), null if the shell is not WSL.
	 */
	public static String wslDistribution(String shell, String arguments) {
		String s = shell == null ? "" : shell.replace('\\', '/').toLowerCase(Locale.ROOT); //$NON-NLS-1$
		if (s.endsWith("/system32/bash.exe")) { //$NON-NLS-1$
			return ""; //$NON-NLS-1$
		}
		if (!s.equals("wsl.exe") && !s.equals("wsl") && !s.endsWith("/wsl.exe")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			return null;
		}
		String[] args = arguments == null ? new String[0] : arguments.trim().split("\\s+"); //$NON-NLS-1$
		for (int i = 0; i < args.length - 1; i++) {
			if (args[i].equals("-d") || args[i].equals("--distribution")) { //$NON-NLS-1$ //$NON-NLS-2$
				return args[i + 1].replace("\"", ""); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}
		return ""; //$NON-NLS-1$
	}

	/**
	 * A path as a shell on Windows reports it, as a Windows path: in WSL (distribution not null)
	 * /mnt/d/a is D:\a and the rest is under \\wsl.localhost\&lt;distribution&gt; (unknown when the
	 * distribution is ""); elsewhere /D:/a (a file URI) and /d/a (Git Bash) are D:\a. Null if it
	 * cannot be told.
	 */
	public static String toWindows(String path, String distribution) {
		if (!path.startsWith("/")) { //$NON-NLS-1$
			return null;
		}
		Matcher uri = URI_DRIVE.matcher(path);
		if (uri.matches()) {
			return drive(uri.group(1), uri.group(2));
		}
		if (distribution != null) {
			Matcher drive = WSL_DRIVE.matcher(path);
			if (drive.matches()) {
				return drive(drive.group(1), drive.group(2));
			}
			return distribution.isEmpty() ? null : "\\\\wsl.localhost\\" + distribution + path.replace('/', '\\'); //$NON-NLS-1$
		}
		Matcher msys = MSYS_DRIVE.matcher(path);
		return msys.matches() ? drive(msys.group(1), msys.group(2)) : null;
	}

	private static String drive(String letter, String rest) {
		return letter.toUpperCase(Locale.ROOT) + ":" + (rest == null ? "\\" : rest.replace('/', '\\')); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** C:\a\b is /mnt/c/a/b, \\wsl$\Ubuntu\home\a (or \\wsl.localhost\...) is /home/a. */
	public static String toWsl(String path) {
		String p = path.replace('\\', '/');
		String lower = p.toLowerCase(Locale.ROOT);
		if (lower.startsWith("//wsl$/") || lower.startsWith("//wsl.localhost/")) { //$NON-NLS-1$ //$NON-NLS-2$
			int slash = p.indexOf('/', p.indexOf('/', 2) + 1);
			return slash == -1 ? "/" : p.substring(slash); //$NON-NLS-1$
		}
		return hasDrive(p) ? "/mnt/" + Character.toLowerCase(p.charAt(0)) + p.substring(2) : p; //$NON-NLS-1$
	}

	/** C:\a\b is /c/a/b, as Git Bash sees it. */
	public static String toMsys(String path) {
		String p = path.replace('\\', '/');
		return hasDrive(p) ? "/" + Character.toLowerCase(p.charAt(0)) + p.substring(2) : p; //$NON-NLS-1$
	}

	private static boolean hasDrive(String p) {
		return p.length() >= 2 && p.charAt(1) == ':' && Character.isLetter(p.charAt(0));
	}

	/**
	 * What wsl.exe prints about itself (-l): UTF-16LE, or UTF-8 when WSL_UTF8=1 is set. UTF-16 has a NUL
	 * byte in every line end, UTF-8 none.
	 */
	public static String wslText(byte[] out) {
		boolean utf16 = false;
		for (byte b : out) {
			utf16 |= b == 0;
		}
		return new String(out, utf16 ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_8).replace("\uFEFF", ""); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** The WSL distributions, the default one first; empty without WSL. */
	// ponytail: asks wsl.exe once, on the first use, and keeps the answer until restart
	public static synchronized List<String> wslDistributions() {
		if (distributions == null) {
			distributions = List.of();
			try {
				Process wsl = new ProcessBuilder("wsl.exe", "-l", "-q").redirectErrorStream(true).start(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				distributions = wslText(wsl.getInputStream().readAllBytes()).lines().map(String::strip)
						.filter(l -> !l.isEmpty()).toList();
			} catch (IOException e) {
				// no WSL
			}
		}
		return distributions;
	}
}
