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
		// readable on their background, every one of the 16 colors included (VS Code's terminal colors)
		presets.add(new Preset(TerminalMessages.TerminalColorPresets_ModernDark) //
				.set(FOREGROUND, 204, 204, 204).set(BACKGROUND, 30, 30, 30) //
				.set(SELECTION_FOREGROUND, 255, 255, 255).set(SELECTION_BACKGROUND, 38, 79, 120) //
				.set(BLACK, 0, 0, 0).set(RED, 205, 49, 49).set(GREEN, 13, 188, 121).set(YELLOW, 229, 229, 16) //
				.set(BLUE, 36, 114, 200).set(MAGENTA, 188, 63, 188).set(CYAN, 17, 168, 205).set(WHITE, 229, 229, 229) //
				.set(BRIGHT_BLACK, 102, 102, 102).set(BRIGHT_RED, 241, 76, 76).set(BRIGHT_GREEN, 35, 209, 139) //
				.set(BRIGHT_YELLOW, 245, 245, 67).set(BRIGHT_BLUE, 59, 142, 234).set(BRIGHT_MAGENTA, 214, 112, 214) //
				.set(BRIGHT_CYAN, 41, 184, 219).set(BRIGHT_WHITE, 229, 229, 229));
		presets.add(new Preset(TerminalMessages.TerminalColorPresets_ModernLight) //
				.set(FOREGROUND, 51, 51, 51).set(BACKGROUND, 255, 255, 255) //
				.set(SELECTION_FOREGROUND, 0, 0, 0).set(SELECTION_BACKGROUND, 173, 214, 255) //
				.set(BLACK, 0, 0, 0).set(RED, 205, 49, 49).set(GREEN, 0, 188, 0).set(YELLOW, 148, 152, 0) //
				.set(BLUE, 4, 81, 165).set(MAGENTA, 188, 5, 188).set(CYAN, 5, 152, 188).set(WHITE, 85, 85, 85) //
				.set(BRIGHT_BLACK, 102, 102, 102).set(BRIGHT_RED, 205, 49, 49).set(BRIGHT_GREEN, 20, 206, 20) //
				.set(BRIGHT_YELLOW, 181, 186, 0).set(BRIGHT_BLUE, 4, 81, 165).set(BRIGHT_MAGENTA, 188, 5, 188) //
				.set(BRIGHT_CYAN, 5, 152, 188).set(BRIGHT_WHITE, 165, 165, 165));
		presets.add(new Preset(TerminalMessages.TerminalColorPresets_TerminalDefaults));
		presets.add(new Preset(TerminalMessages.TerminalColorPresets_EclipseLight) //
				.set(FOREGROUND, 0, 0, 0) //
				.set(BACKGROUND, 229, 229, 229));
		presets.add(new Preset(TerminalMessages.TerminalColorPresets_EclipseDark) //
				.set(FOREGROUND, 229, 229, 229) //
				.set(BACKGROUND, 0, 0, 0));
	}

	/** Modern Dark under a dark Eclipse theme, Modern Light otherwise. */
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
