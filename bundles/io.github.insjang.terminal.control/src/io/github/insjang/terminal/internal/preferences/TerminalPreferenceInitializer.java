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
 * Martin Oberhuber (Wind River) - [436612] Restore Eclipse 3.4 compatibility
 *******************************************************************************/
package io.github.insjang.terminal.internal.preferences;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.jface.resource.StringConverter;
import io.github.insjang.terminal.internal.control.impl.TerminalPlugin;
import io.github.insjang.terminal.internal.preferences.TerminalColorPresets.Preset;
import io.github.insjang.terminal.model.TerminalColor;

/**
 * Terminal Preference Initializer.
 *
 * @noextend This class is not intended to be subclassed by clients.
 * @noinstantiate This class is not intended to be instantiated by clients.
 * @noreference This class is not intended to be referenced by clients.
 */
public class TerminalPreferenceInitializer extends AbstractPreferenceInitializer {

	public TerminalPreferenceInitializer() {
	}

	@Override
	public void initializeDefaultPreferences() {
		//DefaultScope.INSTANCE was added in Eclipse 3.7
		IEclipsePreferences defaultPrefs = DefaultScope.INSTANCE.getNode(TerminalPlugin.PLUGIN_ID);
		defaultPrefs.putBoolean(ITerminalConstants.PREF_INVERT_COLORS, ITerminalConstants.DEFAULT_INVERT_COLORS);
		defaultPrefs.putBoolean(ITerminalConstants.PREF_BAR_CURSOR, ITerminalConstants.DEFAULT_BAR_CURSOR);
		defaultPrefs.putBoolean(ITerminalConstants.PREF_AUTO_COPY_SELECTION,
				ITerminalConstants.DEFAULT_AUTO_COPY_SELECTION);
		defaultPrefs.putBoolean(ITerminalConstants.PREF_DESKTOP_NOTIFICATIONS,
				ITerminalConstants.DEFAULT_DESKTOP_NOTIFICATIONS);
		defaultPrefs.putBoolean(ITerminalConstants.PREF_CLIPBOARD_WRITE, ITerminalConstants.DEFAULT_CLIPBOARD_WRITE);
		defaultPrefs.putBoolean(ITerminalConstants.PREF_CONFIRM_MULTILINE_PASTE,
				ITerminalConstants.DEFAULT_CONFIRM_MULTILINE_PASTE);
		defaultPrefs.putInt(ITerminalConstants.PREF_BUFFERLINES, ITerminalConstants.DEFAULT_BUFFERLINES);
		defaultPrefs.putBoolean(ITerminalConstants.PREF_RESTORE_TERMINALS, ITerminalConstants.DEFAULT_PREF_RESTORE_TERMINALS);
		defaultPrefs.put(ITerminalConstants.PREF_FONT_DEFINITION, ITerminalConstants.DEFAULT_FONT_DEFINITION);

		Preset defaultPresets = TerminalColorPresets.INSTANCE.getDefaultPreset();
		TerminalColor[] colors = TerminalColor.values();
		for (TerminalColor color : colors) {
			defaultPrefs.put(ITerminalConstants.getPrefForTerminalColor(color),
					StringConverter.asString(defaultPresets.getRGB(color)));
		}
	}
}
