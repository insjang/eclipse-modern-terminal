/*******************************************************************************
 * Copyright (c) 2026 insjang and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package io.github.insjang.terminal.connector.ssh.launcher;

import java.util.Map;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.IMessageProvider;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.actions.CompoundContributionItem;

import io.github.insjang.terminal.connector.ssh.connector.SshConnector;
import io.github.insjang.terminal.connector.ssh.controls.SshWizardConfigurationPanel;
import io.github.insjang.terminal.connector.ssh.nls.Messages;
import io.github.insjang.terminal.control.ITerminalViewControl;
import io.github.insjang.terminal.view.ui.ITerminalsView;
import io.github.insjang.terminal.view.ui.internal.tabs.TabFolderManager;
import io.github.insjang.terminal.view.ui.launcher.IConfigurationPanelContainer;

/**
 * "Edit Session..." in the context menu of an SSH terminal: the settings of the saved session the tab
 * was launched from (else of the tab), saved back as that session for the next connection.
 */
public class EditSessionContribution extends CompoundContributionItem {

	@Override
	protected IContributionItem[] getContributionItems() {
		Map<String, Object> properties = sshTabProperties();
		if (properties == null) {
			return new IContributionItem[0];
		}
		Action action = new Action(Messages.EditSession_menu) {
			@Override
			public void run() {
				new EditSessionDialog(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(), properties).open();
			}
		};
		return new IContributionItem[] { new ActionContributionItem(action) };
	}

	// the properties of the active tab of the terminals view, if it is SSH
	@SuppressWarnings("unchecked")
	private static Map<String, Object> sshTabProperties() {
		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		IWorkbenchPage page = window != null ? window.getActivePage() : null;
		TabFolderManager manager = page != null && page.getActivePart() instanceof ITerminalsView view
				? view.getAdapter(TabFolderManager.class) : null;
		CTabItem item = manager != null ? manager.getActiveTabItem() : null;
		if (item == null || item.isDisposed() || !(item.getData() instanceof ITerminalViewControl t)
				|| t.getTerminalConnector() == null || t.getTerminalConnector().getAdapter(SshConnector.class) == null) {
			return null;
		}
		return item.getData("properties") instanceof Map<?, ?> m ? (Map<String, Object>) m : null; //$NON-NLS-1$
	}

	private static class EditSessionDialog extends TitleAreaDialog implements IConfigurationPanelContainer {
		private final Map<String, Object> properties;
		private SshWizardConfigurationPanel panel;

		EditSessionDialog(Shell shell, Map<String, Object> properties) {
			super(shell);
			this.properties = properties;
		}

		@Override
		protected void configureShell(Shell shell) {
			super.configureShell(shell);
			shell.setText(Messages.EditSession_title);
		}

		@Override
		protected Control createDialogArea(Composite parent) {
			Composite area = (Composite) super.createDialogArea(parent);
			setTitle(Messages.EditSession_title);
			setMessage(Messages.EditSession_message, IMessageProvider.INFORMATION); // the open terminal is not reconnected
			panel = new SshWizardConfigurationPanel(this, true);
			panel.setupPanel(area);
			panel.edit(properties);
			return area;
		}

		@Override
		protected void createButtonsForButtonBar(Composite parent) {
			super.createButtonsForButtonBar(parent);
			getButton(IDialogConstants.OK_ID).setText(Messages.SshWizardConfigurationPanel_save);
			validate();
		}

		@Override
		public void validate() {
			Button ok = getButton(IDialogConstants.OK_ID);
			if (ok == null || panel == null) {
				return;
			}
			ok.setEnabled(panel.isValid()); // the fields that need attention are decorated
		}

		@Override
		protected void okPressed() {
			if (panel.save()) {
				super.okPressed();
			}
		}

		@Override
		public boolean close() {
			if (panel != null) {
				panel.dispose();
			}
			return super.close();
		}

		@Override
		protected boolean isResizable() {
			return true;
		}
	}
}
