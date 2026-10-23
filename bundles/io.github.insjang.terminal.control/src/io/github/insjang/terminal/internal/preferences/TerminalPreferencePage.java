/*******************************************************************************
 * Copyright (c) 2003, 2018 Wind River Systems, Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Initial Contributors:
 * The following Wind River employees contributed to the Terminal component
 * that contains this file: Chris Thew, Fran Litterio, Stephen Lamb,
 * Helmut Haigermoser and Ted Williams.
 *
 * Contributors:
 * Michael Scharf (Wind River) - split into core, view and connector plugins
 * Martin Oberhuber (Wind River) - fixed copyright headers and beautified
 * Martin Oberhuber (Wind River) - [378691][api] push Preferences into the Widget
 *******************************************************************************/
package io.github.insjang.terminal.internal.preferences;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.IntegerFieldEditor;
import io.github.insjang.terminal.internal.control.impl.TerminalMessages;
import io.github.insjang.terminal.internal.control.impl.TerminalPlugin;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

/**
 * Terminal Preference Page.
 *
 * @noextend This class is not intended to be subclassed by clients.
 * @noinstantiate This class is not intended to be instantiated by clients.
 * @noreference This class is not intended to be referenced by clients.
 */
public class TerminalPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

	private TerminalColorsFieldEditor terminalColorsFieldEditor;

	public TerminalPreferencePage() {
		super(GRID);
	}

	@Override
	protected void createFieldEditors() {
		setupPage();
	}

	@Override
	public void init(IWorkbench workbench) {
		// do nothing
	}

	protected void setupPage() {
		setupData();
		setupEditors();
	}

	protected void setupData() {
		TerminalPlugin plugin;
		IPreferenceStore preferenceStore;

		plugin = TerminalPlugin.getDefault();
		preferenceStore = plugin.getPreferenceStore();
		setPreferenceStore(preferenceStore);
	}

	protected void setupEditors() {
		addField(new BooleanFieldEditor(ITerminalConstants.PREF_RESTORE_TERMINALS, TerminalMessages.RESTORE_TERMINALS,
				getFieldEditorParent()));
		addField(new BooleanFieldEditor(ITerminalConstants.PREF_INVERT_COLORS, TerminalMessages.INVERT_COLORS,
				getFieldEditorParent()));
		addField(new BooleanFieldEditor(ITerminalConstants.PREF_BAR_CURSOR, TerminalMessages.BAR_CURSOR,
				getFieldEditorParent()));
		addField(new BooleanFieldEditor(ITerminalConstants.PREF_AUTO_COPY_SELECTION,
				TerminalMessages.AUTO_COPY_SELECTION, getFieldEditorParent()));
		addField(new BooleanFieldEditor(ITerminalConstants.PREF_PROGRAM_TITLES, TerminalMessages.PROGRAM_TITLES,
				getFieldEditorParent()));
		addField(new BooleanFieldEditor(ITerminalConstants.PREF_DESKTOP_NOTIFICATIONS,
				TerminalMessages.DESKTOP_NOTIFICATIONS, getFieldEditorParent()));
		addField(new BooleanFieldEditor(ITerminalConstants.PREF_CLIPBOARD_WRITE, TerminalMessages.CLIPBOARD_WRITE,
				getFieldEditorParent()));
		addField(new BooleanFieldEditor(ITerminalConstants.PREF_CONFIRM_MULTILINE_PASTE,
				TerminalMessages.CONFIRM_MULTILINE_PASTE, getFieldEditorParent()));

		addField(new IntegerFieldEditor(ITerminalConstants.PREF_BUFFERLINES, TerminalMessages.BUFFERLINES,
				getFieldEditorParent()));

		terminalColorsFieldEditor = new TerminalColorsFieldEditor(getFieldEditorParent());
		addField(terminalColorsFieldEditor);

	}

	@Override
	public void dispose() {
		if (terminalColorsFieldEditor != null) {
			terminalColorsFieldEditor.dispose();
		}

		super.dispose();
	}
}
