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

	/**
	 * @param target what the link opens (see {@link #target})
	 * @param visible the text under the mouse
	 * @return whether to show where the link goes and ask first: unless the text is
	 *         the link, its target, or the target's file name (as ls --hyperlink shows it)
	 */
	public static boolean confirm(String link, String target, String visible) {
		if (visible == null || visible.isEmpty()) {
			return true;
		}
		return !visible.equals(link) && !visible.equals(target) && !target.endsWith('/' + visible)
				&& !target.endsWith('\\' + visible);
	}

	/**
	 * @return the editor to open a linked file in: never one outside Eclipse (the
	 *         system editor runs .exe, .bat, .lnk...) or in place (OLE)
	 */
	public static String editorId(String id, boolean external, boolean inPlace) {
		return id == null || external || inPlace ? TEXT_EDITOR_ID : id;
	}
}
