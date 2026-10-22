/*******************************************************************************
 * Copyright (c) 2011, 2018 Wind River Systems, Inc. and others. All rights reserved.
 * This program and the accompanying materials are made available under the terms
 * of the Eclipse Public License 2.0 which accompanies this distribution, and is
 * available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 * Wind River Systems - initial API and implementation
 * Max Weninger (Wind River) - [361352] [TERMINALS][SSH] Add SSH terminal support
 *******************************************************************************/
package io.github.insjang.terminal.connector.ssh.controls;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.Assert;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import io.github.insjang.terminal.connector.AbstractSettingsPage;
import io.github.insjang.terminal.connector.ISettingsPage;
import io.github.insjang.terminal.connector.ssh.connector.ISshSettings;
import io.github.insjang.terminal.connector.ssh.connector.SshConnector;
import io.github.insjang.terminal.connector.ssh.connector.SshPasswords;
import io.github.insjang.terminal.connector.ssh.connector.SshSession;
import io.github.insjang.terminal.connector.ssh.connector.SshSettings;
import io.github.insjang.terminal.connector.ssh.connector.SshSettingsPage;
import io.github.insjang.terminal.connector.ssh.nls.Messages;
import io.github.insjang.terminal.view.core.IContextPropertiesConstants;
import io.github.insjang.terminal.view.core.ITerminalContextPropertiesProvider;
import io.github.insjang.terminal.view.core.ITerminalsConnectorConstants;
import io.github.insjang.terminal.view.core.TerminalContextPropertiesProviderFactory;
import io.github.insjang.terminal.view.ui.launcher.AbstractExtendedConfigurationPanel;
import io.github.insjang.terminal.view.ui.launcher.IConfigurationPanelContainer;

/**
 * SSH wizard configuration panel implementation. Saved sessions (see {@link SshSession}) take the
 * place of the host history of the other panels.
 */
public class SshWizardConfigurationPanel extends AbstractExtendedConfigurationPanel {

	private static final String LAST_SESSION = "lastSession"; //$NON-NLS-1$

	private SshSettings sshSettings;
	private ISettingsPage sshSettingsPage;
	private Button passwordButton;
	// loaded here, not only in doRestoreWidgetValues: that is skipped while the dialog has no settings yet
	private List<SshSession> sessions = new ArrayList<>(SshSession.load());
	private Combo sessionCombo;
	private Button deleteButton;

	/**
	 * Constructor.
	 *
	 * @param container The configuration panel container or <code>null</code>.
	 */
	public SshWizardConfigurationPanel(IConfigurationPanelContainer container) {
		super(container);
	}

	@Override
	public void setupPanel(Composite parent) {
		Composite panel = new Composite(parent, SWT.NONE);
		panel.setLayout(new GridLayout());
		GridData data = new GridData(SWT.FILL, SWT.FILL, true, true);
		panel.setLayoutData(data);

		createSessionsUI(panel);

		SshConnector conn = new SshConnector();
		sshSettings = (SshSettings) conn.getSshSettings();
		sshSettings.setHost(getSelectionHost());
		sshSettings.setUser(getDefaultUser());

		sshSettingsPage = new SshSettingsPage(sshSettings);
		if (sshSettingsPage instanceof AbstractSettingsPage) {
			((AbstractSettingsPage) sshSettingsPage).setHasControlDecoration(true);
		}
		sshSettingsPage.createControl(panel);

		// Add the listener to the settings page
		sshSettingsPage.addListener(new ISettingsPage.Listener() {
			@Override
			public void onSettingsPageChanged(Control control) {
				if (getContainer() != null) {
					getContainer().validate();
				}
			}
		});

		// Create the encoding selection combo
		createEncodingUI(panel, true);

		// if the password is saved with the session or not
		createSaveButtonsUI(panel, true);

		setControl(panel);
	}

	@Override
	public void setupData(Map<String, Object> data) {
		if (data == null || sshSettings == null || sshSettingsPage == null) {
			return;
		}

		String value = (String) data.get(ITerminalsConnectorConstants.PROP_IP_HOST);
		if (value != null) {
			sshSettings.setHost(value);
		}

		Object v = data.get(ITerminalsConnectorConstants.PROP_IP_PORT);
		value = v != null ? v.toString() : null;
		if (value != null) {
			sshSettings.setPort(value);
		}

		v = data.get(ITerminalsConnectorConstants.PROP_TIMEOUT);
		value = v != null ? v.toString() : null;
		if (value != null) {
			sshSettings.setTimeout(value);
		}

		v = data.get(ITerminalsConnectorConstants.PROP_SSH_KEEP_ALIVE);
		value = v != null ? v.toString() : null;
		if (value != null) {
			sshSettings.setKeepalive(value);
		}

		value = (String) data.get(ITerminalsConnectorConstants.PROP_SSH_PASSWORD);
		if (value != null) {
			sshSettings.setPassword(value);
		}

		value = (String) data.get(ITerminalsConnectorConstants.PROP_SSH_USER);
		if (value != null) {
			sshSettings.setUser(value);
		}

		value = (String) data.get(ITerminalsConnectorConstants.PROP_SSH_KEY_FILE);
		if (value != null) {
			sshSettings.setKeyFile(value);
		}

		value = (String) data.get(ITerminalsConnectorConstants.PROP_ENCODING);
		if (value != null) {
			setEncoding(value);
		}

		sshSettingsPage.loadSettings();
	}

	/**
	 * Returns the default user name.
	 *
	 * @return The default user name.
	 */
	private String getDefaultUser() {
		ISelection selection = getSelection();
		if (selection instanceof IStructuredSelection && !selection.isEmpty()) {
			Object element = ((IStructuredSelection) selection).getFirstElement();
			ITerminalContextPropertiesProvider provider = TerminalContextPropertiesProviderFactory.getProvider(element);
			if (provider != null) {
				Object user = provider.getProperty(element, IContextPropertiesConstants.PROP_DEFAULT_USER);
				if (user instanceof String) {
					return ((String) user).trim();
				}
			}
		}

		return System.getProperty("user.name"); //$NON-NLS-1$
	}

	@Override
	public void extractData(Map<String, Object> data) {
		if (data == null) {
			return;
		}

		// set the terminal connector id for ssh
		data.put(ITerminalsConnectorConstants.PROP_TERMINAL_CONNECTOR_ID,
				"io.github.insjang.terminal.connector.ssh.SshConnector"); //$NON-NLS-1$

		sshSettingsPage.saveSettings();
		data.put(ITerminalsConnectorConstants.PROP_IP_HOST, sshSettings.getHost());
		data.put(ITerminalsConnectorConstants.PROP_IP_PORT, Integer.valueOf(sshSettings.getPort()));
		data.put(ITerminalsConnectorConstants.PROP_TIMEOUT, Integer.valueOf(sshSettings.getTimeout()));
		data.put(ITerminalsConnectorConstants.PROP_SSH_KEEP_ALIVE, Integer.valueOf(sshSettings.getKeepalive()));
		data.put(ITerminalsConnectorConstants.PROP_SSH_PASSWORD, sshSettings.getPassword());
		data.put(ITerminalsConnectorConstants.PROP_SSH_USER, sshSettings.getUser());
		data.put(ITerminalsConnectorConstants.PROP_SSH_KEY_FILE, sshSettings.getKeyFile());
		data.put(ITerminalsConnectorConstants.PROP_ENCODING, getEncoding());
	}

	@Override
	public boolean isWithHostList() {
		return false; // the sessions instead
	}

	@Override
	public void doRestoreWidgetValues(IDialogSettings settings, String idPrefix) {
		sessions = new ArrayList<>(SshSession.load()); // before super: it may call fillSettingsForHost
		fillSessionCombo();
		super.doRestoreWidgetValues(settings, idPrefix);
		if (isWithoutSelection()) {
			select(find(settings.get(LAST_SESSION)));
		}
	}

	@Override
	public void doSaveWidgetValues(IDialogSettings settings, String idPrefix) {
		super.doSaveWidgetValues(settings, idPrefix);
		SshSession session = selected();
		settings.put(LAST_SESSION, session != null ? session.name() : ""); //$NON-NLS-1$
	}

	// a selection with a host (no host history here): its first session, if any
	@Override
	protected void fillSettingsForHost(String host) {
		sessions.stream().filter(s -> s.host().equals(host)).findFirst().ifPresent(this::select);
	}

	@Override
	protected void saveSettingsForHost(boolean add) {
		// the sessions are saved by the Save button only
	}

	private SshSession find(String name) {
		return sessions.stream().filter(s -> s.name().equals(name)).findFirst().orElse(null);
	}

	private SshSession selected() {
		int index = sessionCombo != null ? sessionCombo.getSelectionIndex() : -1;
		return index >= 0 ? find(sessionCombo.getItem(index)) : null;
	}

	private void fillSessionCombo() {
		sessionCombo.removeAll();
		sessions.forEach(s -> sessionCombo.add(s.name()));
		sessionCombo.setEnabled(!sessions.isEmpty());
		deleteButton.setEnabled(false);
	}

	// fills the fields from the session, its password from the secure storage
	private void select(SshSession session) {
		if (session == null) {
			return;
		}
		sessionCombo.select(sessionCombo.indexOf(session.name()));
		deleteButton.setEnabled(true);
		sshSettings.setHost(session.host());
		sshSettings.setPort(Integer.toString(session.port()));
		sshSettings.setUser(session.user());
		sshSettings.setKeyFile(session.keyFile());
		sshSettings.setTimeout(Integer.toString(session.timeout()));
		sshSettings.setKeepalive(Integer.toString(session.keepalive()));
		String password = session.savePassword() ? SshPasswords.get(session.user(), session.host(), session.port()) : null;
		sshSettings.setPassword(password != null ? password : ""); //$NON-NLS-1$
		if (session.encoding() != null) {
			setEncoding(session.encoding());
		}
		sshSettingsPage.loadSettings();
		passwordButton.setSelection(session.savePassword());
	}

	private void saveSession() {
		sshSettingsPage.saveSettings();
		if (!sshSettingsPage.validateSettings()) {
			return;
		}
		SshSession current = selected();
		String name = current != null ? current.name()
				: SshSession.defaultName(sshSettings.getUser(), sshSettings.getHost(), sshSettings.getPort());
		InputDialog dialog = new InputDialog(sessionCombo.getShell(), Messages.SshWizardConfigurationPanel_saveTitle,
				Messages.SshWizardConfigurationPanel_saveMessage, name,
				text -> text.trim().isEmpty() ? "" : null); //$NON-NLS-1$
		if (dialog.open() != Window.OK) {
			return;
		}
		SshSession session = new SshSession(dialog.getValue().trim(), sshSettings.getHost().trim(), sshSettings.getPort(),
				sshSettings.getUser().trim(), sshSettings.getKeyFile(), sshSettings.getTimeout(), sshSettings.getKeepalive(),
				getEncoding(), passwordButton.getSelection());
		SshSession old = find(session.name());
		sessions.remove(old);
		sessions.add(session);
		String password = sshSettings.getPassword();
		if (session.savePassword() && password != null && !password.isEmpty()) {
			SshPasswords.put(session.user(), session.host(), session.port(), password);
		} else if (!session.savePassword()) {
			dropPasswordIfUnused(session);
		}
		if (old != null && !old.sameLogin(session)) {
			dropPasswordIfUnused(old);
		}
		SshSession.save(sessions);
		sessions = new ArrayList<>(SshSession.load()); // sorted
		fillSessionCombo();
		select(find(session.name()));
		// a password not saved with the session is still used for this launch
		if (password != null && !password.isEmpty()) {
			sshSettings.setPassword(password);
			sshSettingsPage.loadSettings();
		}
	}

	// empty fields for a new session; Save... then names it user@host
	private void newSession() {
		sessionCombo.deselectAll();
		deleteButton.setEnabled(false);
		sshSettings.setHost(""); //$NON-NLS-1$
		sshSettings.setPort(Integer.toString(ISshSettings.DEFAULT_SSH_PORT));
		sshSettings.setUser(getDefaultUser());
		sshSettings.setKeyFile(""); //$NON-NLS-1$
		sshSettings.setTimeout("0"); //$NON-NLS-1$
		sshSettings.setKeepalive("300"); //$NON-NLS-1$
		sshSettings.setPassword(""); //$NON-NLS-1$
		sshSettingsPage.loadSettings();
		passwordButton.setSelection(false);
	}

	private void deleteSession() {
		SshSession session = selected();
		if (session == null) {
			return;
		}
		sessions.remove(session);
		dropPasswordIfUnused(session);
		SshSession.save(sessions);
		fillSessionCombo();
	}

	// the password of user@host:port goes once no session with "Save password" logs in so
	private void dropPasswordIfUnused(SshSession session) {
		if (sessions.stream().noneMatch(s -> s.savePassword() && s.sameLogin(session))) {
			SshPasswords.remove(session.user(), session.host(), session.port());
		}
	}

	@Override
	public boolean isValid() {
		return isEncodingValid() && sshSettingsPage.validateSettings();
	}

	@Override
	protected String getHostFromSettings() {
		sshSettingsPage.saveSettings();
		return sshSettings.getHost();
	}

	private void createSessionsUI(Composite parent) {
		Composite panel = new Composite(parent, SWT.NONE);
		GridLayout layout = new GridLayout(5, false);
		layout.marginHeight = 0;
		layout.marginWidth = 0;
		panel.setLayout(layout);
		panel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

		Label label = new Label(panel, SWT.HORIZONTAL);
		label.setText(Messages.SshWizardConfigurationPanel_session);
		sessionCombo = new Combo(panel, SWT.READ_ONLY);
		sessionCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		sessionCombo.addListener(SWT.Selection, e -> select(selected()));
		Button newButton = new Button(panel, SWT.PUSH);
		newButton.setText(Messages.SshWizardConfigurationPanel_new);
		newButton.setToolTipText(Messages.SshWizardConfigurationPanel_newTooltip);
		newButton.addListener(SWT.Selection, e -> newSession());
		Button saveButton = new Button(panel, SWT.PUSH);
		saveButton.setText(Messages.SshWizardConfigurationPanel_save);
		saveButton.setToolTipText(Messages.SshWizardConfigurationPanel_saveTooltip);
		saveButton.addListener(SWT.Selection, e -> saveSession());
		deleteButton = new Button(panel, SWT.PUSH);
		deleteButton.setText(Messages.SshWizardConfigurationPanel_delete);
		deleteButton.setToolTipText(Messages.SshWizardConfigurationPanel_deleteTooltip);
		deleteButton.addListener(SWT.Selection, e -> deleteSession());
		fillSessionCombo();

		Label sep = new Label(parent, SWT.SEPARATOR | SWT.HORIZONTAL);
		sep.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
	}

	private void createSaveButtonsUI(final Composite parent, boolean separator) {
		Assert.isNotNull(parent);

		if (separator) {
			Label sep = new Label(parent, SWT.SEPARATOR | SWT.HORIZONTAL);
			sep.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		}

		passwordButton = new Button(parent, SWT.CHECK);
		passwordButton.setLayoutData(new GridData(SWT.LEAD, SWT.CENTER, false, false));
		passwordButton.setText(Messages.SshWizardConfigurationPanel_savePassword);
	}
}
