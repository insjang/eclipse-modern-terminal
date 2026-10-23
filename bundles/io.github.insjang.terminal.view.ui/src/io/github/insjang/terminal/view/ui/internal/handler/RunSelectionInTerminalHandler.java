/*******************************************************************************
 * Copyright (c) 2026 insjang and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package io.github.insjang.terminal.view.ui.internal.handler;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.ui.handlers.HandlerUtil;

import io.github.insjang.terminal.control.ITerminalViewControl;
import io.github.insjang.terminal.view.core.ITerminalsConnectorConstants;
import io.github.insjang.terminal.view.ui.ITerminalsView;
import io.github.insjang.terminal.view.ui.IUIConstants;
import io.github.insjang.terminal.view.ui.TerminalViewId;
import io.github.insjang.terminal.view.ui.internal.Messages;
import io.github.insjang.terminal.view.ui.internal.UIPlugin;
import io.github.insjang.terminal.view.ui.internal.tabs.TabFolderManager;

/**
 * Runs the text selected in an editor in the terminal shown in Modern Terminal
 * (Ctrl+Alt+Enter): typed as pasted, then Enter.
 */
public class RunSelectionInTerminalHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) {
		if (!(HandlerUtil.getCurrentSelection(event) instanceof ITextSelection selection)) {
			return null;
		}
		String text = selection.getText() == null ? "" : selection.getText().stripTrailing(); //$NON-NLS-1$
		if (text.isEmpty()) {
			return null;
		}
		ITerminalsView view = UIPlugin.getConsoleManager()
				.findConsoleView(new TerminalViewId(IUIConstants.ID, ITerminalsConnectorConstants.ANY_ACTIVE_SECONDARY_ID))
				.orElse(null);
		TabFolderManager manager = view != null ? view.getAdapter(TabFolderManager.class) : null;
		CTabItem item = manager != null ? manager.getActiveTabItem() : null;
		if (item == null || !(item.getData() instanceof ITerminalViewControl terminal) || !terminal.isConnected()) {
			MessageDialog.openInformation(HandlerUtil.getActiveShell(event), Messages.RunSelection_title,
					Messages.RunSelection_noTerminal);
			return null;
		}
		// pasted (bracketed where the shell asked for it), then Enter outside the paste runs it
		terminal.pasteString(text);
		terminal.sendKey('\r');
		view.getSite().getPage().bringToTop(view);
		return null;
	}
}
