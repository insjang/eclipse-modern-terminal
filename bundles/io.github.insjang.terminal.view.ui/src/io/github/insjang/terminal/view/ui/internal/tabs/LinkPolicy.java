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
import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

import io.github.insjang.terminal.control.TerminalDirectory;

/**
 * What Ctrl+click may do with a hyperlink a program put in the terminal (OSC 8):
 * the output is not to be trusted, so a link only opens a web page or a local file
 * in an Eclipse editor, never runs a program, and says where it goes when its text
 * says something else.
 */
public final class LinkPolicy {
	/** The editor for a file whose editor would be a program outside Eclipse. */
	public static final String TEXT_EDITOR_ID = "org.eclipse.ui.DefaultTextEditor"; //$NON-NLS-1$

	private LinkPolicy() {
	}

	/**
	 * @param link an http, https or file URI (others never get stored)
	 * @param localHost the name of this computer
	 * @return the web URL as it is, a file URI as its local path; null for a file on
	 *         another host or a UNC path (opening it would hand over the Windows login)
	 */
	public static String target(String link, String localHost) {
		if (!link.regionMatches(true, 0, "file://", 0, 7)) { //$NON-NLS-1$
			return link;
		}
		TerminalDirectory file = TerminalDirectory.parse(link);
		if (file == null) {
			return null;
		}
		String host = file.host();
		if (!host.isEmpty() && !host.equalsIgnoreCase("localhost") && !host.equalsIgnoreCase(localHost)) { //$NON-NLS-1$
			return null;
		}
		String path = file.path();
		if (path.startsWith("//") || path.startsWith("/\\") || path.startsWith("\\")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			return null;
		}
		// file:///C:/a on Windows
		return path.length() > 3 && path.charAt(0) == '/' && path.charAt(2) == ':' ? path.substring(1) : path;
	}

	/** @return whether the text is an http or https URL, in any case */
	public static boolean isWeb(String s) {
		return s.regionMatches(true, 0, "http://", 0, 7) || s.regionMatches(true, 0, "https://", 0, 8); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/**
	 * @param target what the link opens (see {@link #target})
	 * @param visible the text under the mouse
	 * @return whether to show where the link goes and ask first: a web link unless the
	 *         text is the URL itself (scheme and host in any case); a file link unless the
	 *         text is the link, the path, or the file name (as ls --hyperlink shows it)
	 */
	public static boolean confirm(String link, String target, String visible) {
		if (visible == null || visible.isEmpty()) {
			return true;
		}
		if (isWeb(link)) {
			return !normalised(visible).equals(normalised(link));
		}
		return !visible.equals(link) && !visible.equals(target) && !target.endsWith('/' + visible)
				&& !target.endsWith('\\' + visible);
	}

	// scheme and host in lower case, the rest as it is
	private static String normalised(String url) {
		int start = url.indexOf("://"); //$NON-NLS-1$
		if (start < 0) {
			return url;
		}
		int end = authorityEnd(url, start + 3);
		return url.substring(0, end).toLowerCase(Locale.ROOT) + url.substring(end);
	}

	private static int authorityEnd(String url, int from) {
		int end = url.length();
		for (char c : new char[] { '/', '?', '#' }) {
			int i = url.indexOf(c, from);
			if (i >= 0 && i < end) {
				end = i;
			}
		}
		return end;
	}

	/**
	 * The target as it is shown in a question: a web URL with its host in ASCII (an IDN
	 * in punycode) and everything not plain ASCII in the rest percent-encoded, a path with
	 * control, format (bidi) and line separator characters as \\uXXXX, so that nothing
	 * can make it look like another address.
	 */
	public static String display(String target) {
		if (!isWeb(target)) {
			StringBuilder b = new StringBuilder();
			target.codePoints().forEach(c -> {
				int type = Character.getType(c);
				if (type == Character.CONTROL || type == Character.FORMAT || type == Character.LINE_SEPARATOR
						|| type == Character.PARAGRAPH_SEPARATOR) {
					b.append(String.format("\\u%04X", c)); //$NON-NLS-1$
				} else {
					b.appendCodePoint(c);
				}
			});
			return b.toString();
		}
		int start = target.indexOf("://") + 3; //$NON-NLS-1$
		int end = authorityEnd(target, start);
		String authority = target.substring(start, end);
		int at = authority.lastIndexOf('@');
		int colon = authority.indexOf(':', at + 1);
		String host = authority.substring(at + 1, colon < 0 ? authority.length() : colon);
		try {
			host = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED);
		} catch (IllegalArgumentException e) {
			// shown encoded below
		}
		String shown = target.substring(0, start) + authority.substring(0, at + 1) + host
				+ (colon < 0 ? "" : authority.substring(colon)) + target.substring(end); //$NON-NLS-1$
		StringBuilder b = new StringBuilder();
		for (byte c : shown.getBytes(StandardCharsets.UTF_8)) {
			if (c > 0x20 && c < 0x7f) {
				b.append((char) c);
			} else {
				b.append(String.format("%%%02X", c & 0xff)); //$NON-NLS-1$
			}
		}
		return b.toString();
	}

	/** The id of the editor that hands a file to the program the system has for it. */
	public static final String SYSTEM_EDITOR_ID = "org.eclipse.ui.systemExternalEditor"; //$NON-NLS-1$
	/** What the system program may open from terminal output: documents and images that run nothing. */
	private static final Set<String> SYSTEM_PROGRAM_TYPES = Set.of("pdf", "png", "jpg", "jpeg", "gif", "bmp"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

	/**
	 * The editor for a file named by terminal output: the registered one if it runs inside
	 * Eclipse, else the text editor; never one outside Eclipse (the system program runs
	 * .exe, .bat, .lnk...) or in place (OLE).
	 *
	 * @param id the registered editor, or null
	 * @param internal whether it runs inside Eclipse
	 * @param inPlace whether it is an OLE editor
	 */
	public static String editorId(String id, boolean internal, boolean inPlace) {
		if (id == null || !internal || inPlace || id.equals(SYSTEM_EDITOR_ID)
				|| id.equals("org.eclipse.ui.systemInPlaceEditor")) { //$NON-NLS-1$
			return TEXT_EDITOR_ID;
		}
		return id;
	}

	/**
	 * The file the system program may open in place of an Eclipse editor: on Windows only
	 * (elsewhere xdg-open picks the program by the content, not the name), and only a PDF or
	 * an image by the name of the file a link, junction or symlink leads to.
	 *
	 * @return the real path to hand to the system program, or null to open inside Eclipse
	 */
	public static String systemProgramTarget(String path, boolean windows) {
		if (!windows) {
			return null;
		}
		String real;
		try {
			real = Path.of(path).toRealPath().toString();
		} catch (IOException | RuntimeException e) {
			return null;
		}
		return systemProgramAllowed(real) ? real : null;
	}

	/**
	 * @return whether the file is a PDF or an image as Windows opens it: the name without
	 *         trailing dots and spaces (Windows drops them), no alternate data stream (a ':'
	 *         after the drive), not a UNC path
	 */
	public static boolean systemProgramAllowed(String path) {
		String p = path.replace('\\', '/');
		if (p.startsWith("//") || p.indexOf(':', p.length() > 1 && p.charAt(1) == ':' ? 2 : 0) >= 0) { //$NON-NLS-1$
			return false;
		}
		String name = p.substring(p.lastIndexOf('/') + 1).replaceAll("[. ]+$", ""); //$NON-NLS-1$ //$NON-NLS-2$
		int dot = name.lastIndexOf('.');
		return dot > 0 && SYSTEM_PROGRAM_TYPES.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
	}
}
