/*******************************************************************************
 * Copyright (c) 2006, 2018 Wind River Systems, Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 * Michael Scharf (Wind River) - initial API and implementation
 * Martin Oberhuber (Wind River) - fixed copyright headers and beautified
 * Martin Oberhuber (Wind River) - [378691][api] push Preferences into the Widget
 *******************************************************************************/
package io.github.insjang.terminal.internal.preferences;

import io.github.insjang.terminal.model.TerminalColor;

/**
 * Constants for Terminal Preferences.
 *
 * @noextend This interface is not intended to be extended by clients.
 * @noimplement This interface is not intended to be implemented by clients.
 */
public interface ITerminalConstants {

	public static final String FONT_DEFINITION = "io.github.insjang.terminal.font"; //$NON-NLS-1$
	/** The id up to 1.1.0, which is the Eclipse Terminal's: a font set there is taken over once. */
	public static final String OLD_FONT_DEFINITION = "terminal.views.view.font.definition"; //$NON-NLS-1$
	public static final String PREF_HAS_MIGRATED = "TerminalPref.migrated"; //$NON-NLS-1$

	public static final String PREF_BUFFERLINES = "TerminalPrefBufferLines"; //$NON-NLS-1$
	public static final String PREF_INVERT_COLORS = "TerminalPrefInvertColors"; //$NON-NLS-1$
	public static final String PREF_BAR_CURSOR = "TerminalPrefBarCursor"; //$NON-NLS-1$
	/**
	 * Put text on the clipboard as soon as the mouse finishes selecting it, the way
	 * xterm does, rather than waiting for an explicit copy.
	 */
	public static final String PREF_AUTO_COPY_SELECTION = "TerminalPrefAutoCopySelection"; //$NON-NLS-1$
	public static final String PREF_RESTORE_TERMINALS = "TerminalPrefRestoreTerminals"; //$NON-NLS-1$
	/**
	 * Show a desktop notification for a bell or OSC 9/777 while the Eclipse window
	 * is not active (the tab is marked either way).
	 */
	public static final String PREF_DESKTOP_NOTIFICATIONS = "TerminalPrefDesktopNotifications"; //$NON-NLS-1$
	/** Close a tab when its shell exits (Ctrl+D, exit, logout) after it was connected. */
	public static final String PREF_CLOSE_ON_EXIT = "TerminalPrefCloseOnExit"; //$NON-NLS-1$
	/** Tab names follow the title a program sets (OSC 0/2), such as bash's user@host: folder. */
	public static final String PREF_PROGRAM_TITLES = "TerminalPrefProgramTitles"; //$NON-NLS-1$
	/** Programs may put text on the clipboard (OSC 52); they can never read it. */
	public static final String PREF_CLIPBOARD_WRITE = "TerminalPrefClipboardWrite"; //$NON-NLS-1$
	/** Ask before pasting text with line breaks where the program has no bracketed paste. */
	public static final String PREF_CONFIRM_MULTILINE_PASTE = "TerminalPrefConfirmMultilinePaste"; //$NON-NLS-1$
	/**
	 * @since 5.0
	 */
	public static final String PREF_FONT_DEFINITION = "TerminalFontDefinition"; //$NON-NLS-1$
	public static final int DEFAULT_BUFFERLINES = 10000;
	public static final boolean DEFAULT_INVERT_COLORS = false;
	public static final boolean DEFAULT_BAR_CURSOR = true;
	public static final boolean DEFAULT_AUTO_COPY_SELECTION = true;
	public static final boolean DEFAULT_PREF_RESTORE_TERMINALS = true;
	public static final boolean DEFAULT_DESKTOP_NOTIFICATIONS = true;
	public static final boolean DEFAULT_PROGRAM_TITLES = true;
	public static final boolean DEFAULT_CLOSE_ON_EXIT = true;
	public static final boolean DEFAULT_CLIPBOARD_WRITE = true;
	public static final boolean DEFAULT_CONFIRM_MULTILINE_PASTE = true;
	/**
	 * @since 5.0
	 */
	public static final String DEFAULT_FONT_DEFINITION = FONT_DEFINITION;

	/**
	 * @since 5.0
	 */
	public static String getPrefForTerminalColor(TerminalColor tc) {
		return tc.toString();
	}

}
