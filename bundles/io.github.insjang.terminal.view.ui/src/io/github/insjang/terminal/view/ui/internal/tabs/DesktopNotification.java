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

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolTip;
import org.eclipse.swt.widgets.Tray;
import org.eclipse.swt.widgets.TrayItem;

/**
 * A desktop notification from a tray icon balloon (a toast on Windows 10/11). The
 * icon stays until the window is active again.
 */
final class DesktopNotification {
	private static TrayItem trayItem;
	private static ToolTip tip;
	private static Runnable onClick;

	private DesktopNotification() {
	}

	/**
	 * Shows the notification, replacing the one before. Does nothing where the
	 * desktop has no system tray (some Linux desktops). UI thread only.
	 */
	static void show(Shell shell, Image fallbackImage, String title, String message, Runnable clicked) {
		Tray tray = shell.getDisplay().getSystemTray();
		if (tray == null) {
			return;
		}
		if (trayItem == null || trayItem.isDisposed()) {
			Image image = shell.getImage() != null ? shell.getImage() : fallbackImage;
			if (image == null) {
				return;
			}
			trayItem = new TrayItem(tray, SWT.NONE);
			trayItem.setImage(image);
			trayItem.addListener(SWT.Selection, e -> click());
			trayItem.addListener(SWT.DefaultSelection, e -> click());
			// the window is back: the icon has done its job
			Listener activate = new Listener() {
				@Override
				public void handleEvent(Event e) {
					shell.removeListener(SWT.Activate, this);
					hide();
				}
			};
			shell.addListener(SWT.Activate, activate);
		}
		if (tip != null && !tip.isDisposed()) {
			tip.dispose();
		}
		onClick = clicked;
		tip = new ToolTip(shell, SWT.BALLOON | SWT.ICON_INFORMATION);
		tip.setText(title);
		tip.setMessage(message);
		tip.addListener(SWT.Selection, e -> click());
		trayItem.setToolTip(tip);
		trayItem.setToolTipText(title);
		tip.setVisible(true);
	}

	private static void click() {
		Runnable r = onClick;
		hide();
		if (r != null) {
			r.run();
		}
	}

	private static void hide() {
		onClick = null;
		if (tip != null && !tip.isDisposed()) {
			tip.dispose();
		}
		if (trayItem != null && !trayItem.isDisposed()) {
			trayItem.dispose();
		}
		tip = null;
		trayItem = null;
	}
}
