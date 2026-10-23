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
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
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
	private SshSession loaded; // remembered as the last session when the dialog launches
	private Text nameText;
	private org.eclipse.swt.widgets.List sessionList; // "Default Settings", then the sessions
	private Button saveButton;
	private Button deleteButton;
	private final boolean editing; // Edit Session of a terminal tab: no saved sessions list

	/**
	 * Constructor.
	 *
	 * @param container The configuration panel container or <code>null</code>.
	 */
	public SshWizardConfigurationPanel(IConfigurationPanelContainer container) {
		this(container, false);
	}

	/** With editing, the panel edits one session: no list, {@link #edit(Map)} and {@link #save()} instead. */
	public SshWizardConfigurationPanel(IConfigurationPanelContainer container, boolean editing) {
		super(container);
		this.editing = editing;
	}

	@Override
	public void setupPanel(Composite parent) {
		Composite panel = new Composite(parent, SWT.NONE);
		panel.setLayout(new GridLayout());
		GridData data = new GridData(SWT.FILL, SWT.FILL, true, true);
		panel.setLayoutData(data);

		SshConnector conn = new SshConnector();
		sshSettings = (SshSettings) conn.getSshSettings();
		sshSettings.setHost(getSelectionHost());
		sshSettings.setUser(getDefaultUser());

		SshSettingsPage page = new SshSettingsPage(sshSettings);
		page.setNameLabel(Messages.SshWizardConfigurationPanel_name); // the name on top, as in PuTTY
		sshSettingsPage = page;
		if (sshSettingsPage instanceof AbstractSettingsPage) {
			((AbstractSettingsPage) sshSettingsPage).setHasControlDecoration(true);
		}
		sshSettingsPage.createControl(panel);
		nameText = page.getNameText();
		nameText.setToolTipText(Messages.SshWizardConfigurationPanel_nameTooltip);
		// "Default Settings" is not a session: it cannot be saved over
		nameText.addListener(SWT.Modify, e -> {
			if (saveButton != null) {
				saveButton.setEnabled(!nameText.getText().trim().equals(Messages.SshWizardConfigurationPanel_defaultSettings));
			}
		});

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

		if (!editing) {
			createSessionsUI(panel);
		}

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

		value = (String) data.get(SshSession.PROP_START_FOLDER);
		if (value != null) {
			sshSettings.setStartFolder(value);
		}

		value = (String) data.get(SshSession.PROP_FORWARDS);
		if (value != null) {
			sshSettings.setForwards(value);
		}

		sshSettingsPage.loadSettings();
	}

	/**
	 * Fills the fields from the saved session the terminal was launched from, else from its properties
	 * (the password from the secure storage then, if saved).
	 */
	public void edit(Map<String, Object> properties) {
		SshSession session = find((String) properties.get(SshSession.PROP_SESSION));
		if (session != null) {
			load(session);
			return;
		}
		setupData(properties);
		// a tab from before sessions were recorded, or typed in: the one saved session with its login
		sshSettingsPage.saveSettings();
		SshSession typed = fields(""); //$NON-NLS-1$
		List<SshSession> same = sessions.stream().filter(s -> s.sameLogin(typed)).toList();
		if (same.size() == 1) {
			load(same.get(0));
			return;
		}
		String password = SshPasswords.get(sshSettings.getUser(), sshSettings.getHost(), sshSettings.getPort());
		if (password != null) {
			sshSettings.setPassword(password);
			sshSettingsPage.loadSettings();
			passwordButton.setSelection(true);
		}
	}

	/** Saves the fields as the session of the name field; false if they are not valid. */
	public boolean save() {
		return saveSession();
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
		data.put(SshSession.PROP_START_FOLDER, sshSettings.getStartFolder());
		data.put(SshSession.PROP_FORWARDS, sshSettings.getForwards());
		// the session the fields came from, for Edit Session; none once the login differs
		if (loaded != null && loaded.sameLogin(fields(loaded.name()))) {
			data.put(SshSession.PROP_SESSION, loaded.name());
		}
	}

	@Override
	public boolean isWithHostList() {
		return false; // the sessions instead
	}

	@Override
	public void doRestoreWidgetValues(IDialogSettings settings, String idPrefix) {
		sessions = new ArrayList<>(SshSession.load()); // before super: it may call fillSettingsForHost
		fillSessionList();
		super.doRestoreWidgetValues(settings, idPrefix);
		if (isWithoutSelection()) {
			load(find(settings.get(LAST_SESSION)));
		}
	}

	@Override
	public void doSaveWidgetValues(IDialogSettings settings, String idPrefix) {
		super.doSaveWidgetValues(settings, idPrefix);
		settings.put(LAST_SESSION, loaded != null ? loaded.name() : ""); //$NON-NLS-1$
	}

	// a selection with a host (no host history here): its first session, if any
	@Override
	protected void fillSettingsForHost(String host) {
		sessions.stream().filter(s -> s.host().equals(host)).findFirst().ifPresent(this::load);
	}

	@Override
	protected void saveSettingsForHost(boolean add) {
		// the sessions are saved by the Save button only
	}

	private SshSession find(String name) {
		return sessions.stream().filter(s -> s.name().equals(name)).findFirst().orElse(null);
	}

	// the session selected in the list; null for none and for "Default Settings"
	private SshSession selected() {
		int index = sessionList != null ? sessionList.getSelectionIndex() : -1;
		return index > 0 ? sessions.get(index - 1) : null;
	}

	private void fillSessionList() {
		if (sessionList == null) {
			return;
		}
		sessionList.removeAll();
		sessionList.add(Messages.SshWizardConfigurationPanel_defaultSettings);
		sessions.forEach(s -> sessionList.add(s.name()));
		deleteButton.setEnabled(false);
	}

	// only shows the session in the list and the name field (Load or a double-click fills the fields)
	private void show(SshSession session) {
		if (sessionList == null) {
			nameText.setText(session.name());
			return;
		}
		sessionList.select(sessions.indexOf(session) + 1);
		sessionList.showSelection();
		onListSelected();
	}

	private void onListSelected() {
		int index = sessionList.getSelectionIndex();
		if (index >= 0) {
			nameText.setText(sessionList.getItem(index));
		}
		deleteButton.setEnabled(index > 0);
	}

	// fills the fields from the list selection: a session, or the defaults for "Default Settings"
	private void loadSelected() {
		if (sessionList.getSelectionIndex() == 0) {
			loadDefaults();
		} else {
			load(selected());
		}
	}

	// fills the fields from the session, its password from the secure storage
	private void load(SshSession session) {
		if (session == null) {
			return;
		}
		loaded = session;
		show(session);
		sshSettings.setHost(session.host());
		sshSettings.setPort(Integer.toString(session.port()));
		sshSettings.setUser(session.user());
		sshSettings.setKeyFile(session.keyFile());
		sshSettings.setTimeout(Integer.toString(session.timeout()));
		sshSettings.setKeepalive(Integer.toString(session.keepalive()));
		sshSettings.setStartFolder(session.startFolder());
		sshSettings.setForwards(session.forwards());
		String password = session.savePassword() ? SshPasswords.get(session.user(), session.host(), session.port()) : null;
		sshSettings.setPassword(password != null ? password : ""); //$NON-NLS-1$
		if (session.encoding() != null) {
			setEncoding(session.encoding());
		}
		sshSettingsPage.loadSettings();
		passwordButton.setSelection(session.savePassword());
	}

	// "Default Settings": empty fields for a new session
	private void loadDefaults() {
		loaded = null;
		sshSettings.setHost(""); //$NON-NLS-1$
		sshSettings.setPort(Integer.toString(ISshSettings.DEFAULT_SSH_PORT));
		sshSettings.setUser(getDefaultUser());
		sshSettings.setKeyFile(""); //$NON-NLS-1$
		sshSettings.setTimeout("0"); //$NON-NLS-1$
		sshSettings.setKeepalive("300"); //$NON-NLS-1$
		sshSettings.setStartFolder(""); //$NON-NLS-1$
		sshSettings.setForwards(""); //$NON-NLS-1$
		sshSettings.setPassword(""); //$NON-NLS-1$
		sshSettingsPage.loadSettings();
		passwordButton.setSelection(false);
	}

	// a double-click (or Enter in the list) loads the session and launches it like OK
	private void launchSelected() {
		int index = sessionList.getSelectionIndex();
		loadSelected();
		if (index <= 0) {
			return;
		}
		if (getContainer() != null) {
			getContainer().validate();
		}
		// no API from the panel to the dialog's OK: press its default button, which is OK
		Button ok = sessionList.getShell().getDefaultButton();
		if (ok != null && ok.isEnabled() && Integer.valueOf(IDialogConstants.OK_ID).equals(ok.getData())) {
			ok.notifyListeners(SWT.Selection, new org.eclipse.swt.widgets.Event());
		}
	}

	// saves the fields under the name in the name field (user@host[:port] if empty), replacing a session of that name
	private boolean saveSession() {
		String name = nameText.getText().trim();
		if (name.equals(Messages.SshWizardConfigurationPanel_defaultSettings)) {
			return false;
		}
		sshSettingsPage.saveSettings();
		if (!sshSettingsPage.validateSettings()) {
			return false;
		}
		if (name.isEmpty()) {
			name = SshSession.defaultName(sshSettings.getUser().trim(), sshSettings.getHost().trim(), sshSettings.getPort());
		}
		SshSession session = fields(name);
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
		fillSessionList();
		loaded = find(session.name()); // the fields are left as they are, a typed password included
		show(loaded);
		return true;
	}

	// the fields as a session of that name (the settings page saved into sshSettings)
	private SshSession fields(String name) {
		return new SshSession(name, sshSettings.getHost().trim(), sshSettings.getPort(), sshSettings.getUser().trim(),
				sshSettings.getKeyFile(), sshSettings.getTimeout(), sshSettings.getKeepalive(), getEncoding(),
				passwordButton.getSelection(), sshSettings.getStartFolder(), sshSettings.getForwards());
	}

	private void deleteSession() {
		SshSession session = selected();
		if (session == null) {
			return;
		}
		sessions.remove(session);
		dropPasswordIfUnused(session);
		SshSession.save(sessions);
		if (session.equals(loaded)) {
			loaded = null;
		}
		fillSessionList();
		nameText.setText(""); //$NON-NLS-1$
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

	// like PuTTY: Load / Save / Delete beside the list; the name field is the first row of the settings
	private void createSessionsUI(Composite parent) {
		Group group = new Group(parent, SWT.NONE);
		group.setText(Messages.SshWizardConfigurationPanel_savedSessions);
		group.setLayout(new GridLayout(2, false));
		group.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

		sessionList = new org.eclipse.swt.widgets.List(group, SWT.SINGLE | SWT.BORDER | SWT.V_SCROLL);
		GridData data = new GridData(SWT.FILL, SWT.FILL, true, true);
		data.widthHint = 100; // fills the width; the hint only keeps a long name from widening the dialog
		data.heightHint = sessionList.getItemHeight() * 5;
		sessionList.setLayoutData(data);
		sessionList.addListener(SWT.Selection, e -> onListSelected());
		sessionList.addListener(SWT.DefaultSelection, e -> launchSelected());

		Composite buttons = new Composite(group, SWT.NONE);
		GridLayout layout = new GridLayout();
		layout.marginHeight = 0;
		layout.marginWidth = 0;
		buttons.setLayout(layout);
		buttons.setLayoutData(new GridData(SWT.FILL, SWT.TOP, false, false));
		Button loadButton = createButton(buttons, Messages.SshWizardConfigurationPanel_load,
				Messages.SshWizardConfigurationPanel_loadTooltip);
		loadButton.addListener(SWT.Selection, e -> loadSelected());
		saveButton = createButton(buttons, Messages.SshWizardConfigurationPanel_save,
				Messages.SshWizardConfigurationPanel_saveTooltip);
		saveButton.addListener(SWT.Selection, e -> saveSession());
		deleteButton = createButton(buttons, Messages.SshWizardConfigurationPanel_delete,
				Messages.SshWizardConfigurationPanel_deleteTooltip);
		deleteButton.addListener(SWT.Selection, e -> deleteSession());
		fillSessionList();
	}

	private static Button createButton(Composite parent, String text, String tooltip) {
		Button button = new Button(parent, SWT.PUSH);
		button.setText(text);
		button.setToolTipText(tooltip);
		button.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		return button;
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
