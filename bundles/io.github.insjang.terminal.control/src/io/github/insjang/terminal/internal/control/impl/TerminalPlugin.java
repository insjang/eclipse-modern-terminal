/*******************************************************************************
 * Copyright (c) 2003, 2025 Wind River Systems, Inc. and others.
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
 * Anna Dushistova (MontaVista) - [227537] moved actions from terminal.view to terminal plugin
 * Alexander Fedorov (ArSysOp) - further evolution
 *******************************************************************************/
package io.github.insjang.terminal.internal.control.impl;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.PreferenceConverter;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

import io.github.insjang.terminal.internal.preferences.ITerminalConstants;

public class TerminalPlugin extends AbstractUIPlugin {
	private static TerminalPlugin plugin;
	public static final String PLUGIN_ID = "io.github.insjang.terminal.control"; //$NON-NLS-1$
	public static final String HELP_VIEW = PLUGIN_ID + ".terminal_view"; //$NON-NLS-1$

	/**
	 * The constructor.
	 */
	public TerminalPlugin() {
	}

	/**
	 * Returns the shared instance.
	 */
	public static TerminalPlugin getDefault() {
		return plugin;
	}

	@Override
	public void start(BundleContext context) throws Exception {
		super.start(context);
		plugin = this;
		takeOverFont();
	}

	// up to 1.1.0 the font had the id of the Eclipse Terminal's font: one chosen then is kept, once
	private static void takeOverFont() {
		IEclipsePreferences own = InstanceScope.INSTANCE.getNode(PLUGIN_ID);
		if (own.getBoolean(FONT_TAKEN_OVER, false)) {
			return;
		}
		own.putBoolean(FONT_TAKEN_OVER, true);
		IEclipsePreferences workbench = InstanceScope.INSTANCE.getNode("org.eclipse.ui.workbench"); //$NON-NLS-1$
		String font = workbench.get(ITerminalConstants.OLD_FONT_DEFINITION, null);
		if (font == null || workbench.get(ITerminalConstants.FONT_DEFINITION, null) != null) {
			return;
		}
		workbench.put(ITerminalConstants.FONT_DEFINITION, font);
		// the font registry read the preferences when the workbench started
		FontData[] data = PreferenceConverter.basicGetFontData(font);
		if (PlatformUI.isWorkbenchRunning()) {
			PlatformUI.getWorkbench().getDisplay()
					.asyncExec(() -> JFaceResources.getFontRegistry().put(ITerminalConstants.FONT_DEFINITION, data));
		}
	}

	private static final String FONT_TAKEN_OVER = "fontTakenOver"; //$NON-NLS-1$

	@Override
	public void stop(BundleContext context) throws Exception {
		plugin = null;
		super.stop(context);
	}

}
