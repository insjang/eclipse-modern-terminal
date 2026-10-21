/*******************************************************************************
 * Copyright (c) 2020 Kichwa Coders Canada Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package io.github.insjang.terminal.internal.preferences;

import static io.github.insjang.terminal.model.TerminalColor.BACKGROUND;
import static io.github.insjang.terminal.model.TerminalColor.BLACK;
import static io.github.insjang.terminal.model.TerminalColor.BLUE;
import static io.github.insjang.terminal.model.TerminalColor.BRIGHT_BLACK;
import static io.github.insjang.terminal.model.TerminalColor.BRIGHT_BLUE;
import static io.github.insjang.terminal.model.TerminalColor.BRIGHT_CYAN;
import static io.github.insjang.terminal.model.TerminalColor.BRIGHT_GREEN;
import static io.github.insjang.terminal.model.TerminalColor.BRIGHT_MAGENTA;
import static io.github.insjang.terminal.model.TerminalColor.BRIGHT_RED;
import static io.github.insjang.terminal.model.TerminalColor.BRIGHT_WHITE;
import static io.github.insjang.terminal.model.TerminalColor.BRIGHT_YELLOW;
import static io.github.insjang.terminal.model.TerminalColor.CYAN;
import static io.github.insjang.terminal.model.TerminalColor.FOREGROUND;
import static io.github.insjang.terminal.model.TerminalColor.GREEN;
import static io.github.insjang.terminal.model.TerminalColor.MAGENTA;
import static io.github.insjang.terminal.model.TerminalColor.RED;
import static io.github.insjang.terminal.model.TerminalColor.SELECTION_BACKGROUND;
import static io.github.insjang.terminal.model.TerminalColor.SELECTION_FOREGROUND;
import static io.github.insjang.terminal.model.TerminalColor.WHITE;
import static io.github.insjang.terminal.model.TerminalColor.YELLOW;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.Platform;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;
import io.github.insjang.terminal.internal.control.impl.TerminalMessages;
import io.github.insjang.terminal.internal.model.SystemDefaultColors;
import io.github.insjang.terminal.model.TerminalColor;

/**
 * @since 5.0
 */
public enum TerminalColorPresets {

	INSTANCE;

	private final List<Preset> presets = new ArrayList<>();

	public List<String> getPresets() {
		return presets.stream().map(Preset::getName).collect(Collectors.toList());
	}

	public Preset getPreset(int index) {
		return presets.get(index);
	}

	public static class Preset {
		private final String name;
		private final Map<TerminalColor, Supplier<RGB>> map = new EnumMap<>(TerminalColor.class);

		Preset(String name) {
			this.name = name;
			set(BLACK, 0, 0, 0);
			set(RED, 205, 0, 0);
			set(GREEN, 0, 205, 0);
			set(YELLOW, 205, 205, 0);
			set(BLUE, 0, 0, 238);
			set(MAGENTA, 205, 0, 205);
			set(CYAN, 0, 205, 205);
			set(WHITE, 229, 229, 229);

			set(BRIGHT_BLACK, 0, 0, 0);
			set(BRIGHT_RED, 255, 0, 0);
			set(BRIGHT_GREEN, 0, 255, 0);
			set(BRIGHT_YELLOW, 255, 255, 0);
			set(BRIGHT_BLUE, 92, 92, 255);
			set(BRIGHT_MAGENTA, 255, 0, 255);
			set(BRIGHT_CYAN, 0, 255, 255);
			set(BRIGHT_WHITE, 255, 255, 255);

			set(FOREGROUND, SystemDefaultColors.FOREGROUND);
			set(BACKGROUND, SystemDefaultColors.BACKGROUND);
			set(SELECTION_FOREGROUND, SystemDefaultColors.SELECTION_FOREGROUND);
			set(SELECTION_BACKGROUND, SystemDefaultColors.SELECTION_BACKGROUND);
		}

		Preset set(TerminalColor color, RGB rgb) {
			return set(color, () -> rgb);
		}

		Preset set(TerminalColor color, int r, int g, int b) {
			return set(color, new RGB(r, g, b));
		}

		Preset set(TerminalColor color, Supplier<RGB> rgbSupplier) {
			map.put(color, rgbSupplier);
			return this;
		}

		public String getName() {
			return name;
		}

		/**
		 * Returns the preset value for the given color. Will never return null
		 * because each color must be defined in the map.
		 *
		 * @param terminalColor to get RGB value for
		 * @return non-<code>null</code> color
		 */
		public RGB getRGB(TerminalColor terminalColor) {
			return map.getOrDefault(terminalColor, () -> new RGB(0, 0, 0)).get();
		}
	}

	TerminalColorPresets() {
		// the Tango palette, as in Windows Terminal's Tango Dark and Tango Light; on black, blue, red
		// and magenta are lighter and green darker, so that each reads (blue on green: ls, other-writable folders)
		presets.add(new Preset(TerminalMessages.TerminalColorPresets_TangoDark) //
				.set(FOREGROUND, 211, 215, 207).set(BACKGROUND, 0, 0, 0) //
				.set(SELECTION_FOREGROUND, 255, 255, 255).set(SELECTION_BACKGROUND, 52, 101, 164) //
				.set(BLACK, 0, 0, 0).set(RED, 255, 77, 77).set(GREEN, 0, 79, 39).set(YELLOW, 196, 160, 0) //
				.set(BLUE, 0, 158, 234).set(MAGENTA, 171, 87, 255).set(CYAN, 6, 152, 154).set(WHITE, 211, 215, 207) //
				.set(BRIGHT_BLACK, 85, 87, 83).set(BRIGHT_RED, 255, 128, 128).set(BRIGHT_GREEN, 138, 226, 52) //
				.set(BRIGHT_YELLOW, 252, 233, 79).set(BRIGHT_BLUE, 147, 220, 255).set(BRIGHT_MAGENTA, 255, 168, 255) //
				.set(BRIGHT_CYAN, 52, 226, 226).set(BRIGHT_WHITE, 249, 249, 249));
		presets.add(new Preset(TerminalMessages.TerminalColorPresets_TangoLight) //
				.set(FOREGROUND, 85, 87, 83).set(BACKGROUND, 255, 255, 255) //
				.set(SELECTION_FOREGROUND, 0, 0, 0).set(SELECTION_BACKGROUND, 187, 214, 244) //
				.set(BLACK, 0, 0, 0).set(RED, 204, 0, 0).set(GREEN, 78, 154, 6).set(YELLOW, 196, 160, 0) //
				.set(BLUE, 52, 101, 164).set(MAGENTA, 117, 80, 123).set(CYAN, 6, 152, 154).set(WHITE, 211, 215, 207) //
				.set(BRIGHT_BLACK, 85, 87, 83).set(BRIGHT_RED, 239, 41, 41).set(BRIGHT_GREEN, 138, 226, 52) //
				.set(BRIGHT_YELLOW, 252, 233, 79).set(BRIGHT_BLUE, 114, 159, 207).set(BRIGHT_MAGENTA, 173, 127, 168) //
				.set(BRIGHT_CYAN, 52, 226, 226).set(BRIGHT_WHITE, 238, 238, 236));
		presets.add(new Preset(TerminalMessages.TerminalColorPresets_TerminalDefaults));
		presets.add(new Preset(TerminalMessages.TerminalColorPresets_EclipseLight) //
				.set(FOREGROUND, 0, 0, 0) //
				.set(BACKGROUND, 229, 229, 229));
		presets.add(new Preset(TerminalMessages.TerminalColorPresets_EclipseDark) //
				.set(FOREGROUND, 229, 229, 229) //
				.set(BACKGROUND, 0, 0, 0));
	}

	/** Tango Dark under a dark Eclipse theme, Tango Light otherwise. */
	public Preset getDefaultPreset() {
		return presets.get(isDarkTheme() ? 0 : 1);
	}

	private static boolean isDarkTheme() {
		// the theme chosen in Preferences > General > Appearance, else the system's (the default theme follows it)
		String theme = Platform.getPreferencesService().getString("org.eclipse.e4.ui.css.swt.theme", "themeid", //$NON-NLS-1$ //$NON-NLS-2$
				null, null);
		return theme != null ? theme.contains("dark") : Display.isSystemDarkTheme(); //$NON-NLS-1$
	}
}
