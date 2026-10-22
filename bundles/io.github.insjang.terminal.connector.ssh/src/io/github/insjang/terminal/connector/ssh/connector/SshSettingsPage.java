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
 * Johnson Ma (Wind River) - [218880] Add UI setting for ssh keepalives
 * Martin Oberhuber (Wind River) - [206917] Add validation for Terminal Settings
 *******************************************************************************/
package io.github.insjang.terminal.connector.ssh.connector;

import org.eclipse.jface.dialogs.IMessageProvider;
import org.eclipse.jface.fieldassist.FieldDecorationRegistry;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import io.github.insjang.terminal.connector.AbstractSettingsPage;

public class SshSettingsPage extends AbstractSettingsPage {
	private Text fHostText;
	private Text fUser;
	private Text fTimeout;
	private Text fKeepalive;
	private final SshSettings fTerminalSettings;
	private Text fPort;
	private Text fPassword;
	private Text fKeyFile;

	public SshSettingsPage(SshSettings settings) {
		fTerminalSettings = settings;
	}

	@Override
	public void saveSettings() {
		fTerminalSettings.setHost(fHostText.getText());
		fTerminalSettings.setUser(fUser.getText());
		fTerminalSettings.setPassword(fPassword.getText());
		fTerminalSettings.setPort(fPort.getText());
		fTerminalSettings.setTimeout(fTimeout.getText());
		fTerminalSettings.setKeepalive(fKeepalive.getText());
		fTerminalSettings.setKeyFile(fKeyFile.getText());
	}

	@Override
	public void loadSettings() {
		if (fTerminalSettings != null) {
			fHostText.setText(get(fTerminalSettings.getHost(), ""));//$NON-NLS-1$
			fTimeout.setText(get(fTerminalSettings.getTimeoutString(), "0"));//$NON-NLS-1$
			fKeepalive.setText(get(fTerminalSettings.getKeepaliveString(), "300"));//$NON-NLS-1$
			fUser.setText(get(fTerminalSettings.getUser(), ""));//$NON-NLS-1$
			fPort.setText(get(fTerminalSettings.getPortString(), String.valueOf(ISshSettings.DEFAULT_SSH_PORT)));
			fPassword.setText(get(fTerminalSettings.getPassword(), ""));//$NON-NLS-1$
			fKeyFile.setText(get(fTerminalSettings.getKeyFile(), ""));//$NON-NLS-1$
		}
	}

	String get(String value, String def) {
		if (value == null || value.length() == 0) {
			return def;
		}
		return value;
	}

	@Override
	public boolean validateSettings() {
		String message = null;
		int messageType = IMessageProvider.NONE;
		boolean valid = true;

		if (fHostText.getText().trim().length() == 0) {
			String m = "Please enter a host IP or name."; //$NON-NLS-1$
			int mt = IMessageProvider.INFORMATION;
			updateControlDecoration(fHostText, m, mt);
			if (mt > messageType) {
				message = m;
				messageType = mt;
			}

			valid = false;
		} else {
			updateControlDecoration(fHostText, null, IMessageProvider.NONE);
		}
		if (fUser.getText().trim().length() == 0) {
			String m = "Please enter a username."; //$NON-NLS-1$
			int mt = IMessageProvider.INFORMATION;
			updateControlDecoration(fUser, m, mt);
			if (mt > messageType) {
				message = m;
				messageType = mt;
			}

			valid = false;
		} else {
			updateControlDecoration(fUser, null, IMessageProvider.NONE);
		}
		try {
			int p = Integer.parseInt(fPort.getText().trim());
			if (p <= 0 || p > 65535) {
				String m = "Invalid network port. Must be between 0 and 65535."; //$NON-NLS-1$
				int mt = IMessageProvider.ERROR;
				updateControlDecoration(fPort, m, mt);
				if (mt > messageType) {
					message = m;
					messageType = mt;
				}

				valid = false;
			} else {
				updateControlDecoration(fPort, null, IMessageProvider.NONE);
			}
			p = Integer.parseInt(fTimeout.getText().trim());
			if (p < 0) {
				String m = "Invalid timeout. Must be greater than 0."; //$NON-NLS-1$
				int mt = IMessageProvider.ERROR;
				updateControlDecoration(fTimeout, m, mt);
				if (mt > messageType) {
					message = m;
					messageType = mt;
				}

				valid = false;
			} else {
				updateControlDecoration(fTimeout, null, IMessageProvider.NONE);
			}
			p = Integer.parseInt(fKeepalive.getText().trim());
			if (p < 0) {
				String m = "Invalid keep alive. Must be greater than 0."; //$NON-NLS-1$
				int mt = IMessageProvider.ERROR;
				updateControlDecoration(fKeepalive, m, mt);
				if (mt > messageType) {
					message = m;
					messageType = mt;
				}

				valid = false;
			} else {
				updateControlDecoration(fKeepalive, null, IMessageProvider.NONE);
			}
		} catch (Exception e) {
			valid = false;
		}
		// a missing key file is only skipped at login: warn, but let it launch
		String keyFile = fKeyFile.getText().trim();
		if (!keyFile.isEmpty() && !keyFile.startsWith("~") && !isFile(keyFile)) { //$NON-NLS-1$
			String m = SshMessages.KEY_FILE_NOT_FOUND;
			int mt = IMessageProvider.WARNING;
			updateControlDecoration(fKeyFile, m, mt);
			if (mt > messageType) {
				message = m;
				messageType = mt;
			}
		} else {
			updateControlDecoration(fKeyFile, null, IMessageProvider.NONE);
		}

		setMessage(message, messageType);
		return valid;
	}

	@Override
	public void createControl(Composite parent) {
		Composite composite = new Composite(parent, SWT.NONE);
		GridLayout gridLayout = new GridLayout(4, false); // Host and Port share the first row
		GridData gridData = new GridData(GridData.FILL_HORIZONTAL);
		gridData.horizontalIndent = FieldDecorationRegistry.getDefault().getMaximumDecorationWidth();

		composite.setLayout(gridLayout);
		composite.setLayoutData(gridData);

		fHostText = createTextField(composite, SshMessages.HOST);
		fPort = createTextField(composite, SshMessages.PORT);
		GridData portData = (GridData) fPort.getLayoutData();
		portData.grabExcessHorizontalSpace = false;
		GC gc = new GC(fPort);
		portData.widthHint = gc.stringExtent("000000").x; //$NON-NLS-1$
		gc.dispose();
		fUser = span(createTextField(composite, SshMessages.USER));
		createKeyFileField(composite);
		fPassword = span(createTextField(composite, SshMessages.PASSWORD, SWT.PASSWORD));
		fTimeout = span(createTextField(composite, SshMessages.TIMEOUT));
		fKeepalive = span(createTextField(composite, SshMessages.KEEPALIVE));
		fKeepalive.setToolTipText(SshMessages.KEEPALIVE_Tooltip);
		loadSettings();
	}

	// over the field columns of the Host row
	private static Text span(Text text) {
		((GridData) text.getLayoutData()).horizontalSpan = 3;
		return text;
	}

	private static boolean isFile(String path) {
		try {
			return Files.isRegularFile(Path.of(path));
		} catch (InvalidPathException e) {
			return false;
		}
	}

	// text and Browse... in the second column
	private void createKeyFileField(Composite composite) {
		Label label = new Label(composite, SWT.RIGHT);
		label.setText(SshMessages.KEY_FILE + ":"); //$NON-NLS-1$
		Composite row = new Composite(composite, SWT.NONE);
		GridLayout layout = new GridLayout(2, false);
		layout.marginHeight = 0;
		layout.marginWidth = 0;
		row.setLayout(layout);
		GridData rowData = new GridData(GridData.FILL_HORIZONTAL);
		rowData.horizontalSpan = 3;
		row.setLayoutData(rowData);
		fKeyFile = new Text(row, SWT.BORDER);
		fKeyFile.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		fKeyFile.setToolTipText(SshMessages.KEY_FILE_Tooltip);
		fKeyFile.addModifyListener(e -> fireListeners(fKeyFile));
		createControlDecoration(fKeyFile);
		Button browse = new Button(row, SWT.PUSH);
		browse.setText(SshMessages.BROWSE);
		browse.addListener(SWT.Selection, e -> {
			FileDialog dialog = new FileDialog(composite.getShell(), SWT.OPEN);
			dialog.setFilterPath(Path.of(System.getProperty("user.home"), ".ssh").toString()); //$NON-NLS-1$ //$NON-NLS-2$
			String file = dialog.open();
			if (file != null) {
				fKeyFile.setText(file);
			}
		});
	}

	private Text createTextField(Composite composite, String labelTxt, int textOptions) {
		GridData gridData;
		// Add label
		Label ctlLabel = new Label(composite, SWT.RIGHT);
		ctlLabel.setText(labelTxt + ":"); //$NON-NLS-1$

		// Add control
		gridData = new GridData(GridData.FILL_HORIZONTAL);
		final Text text = new Text(composite, SWT.BORDER | textOptions);
		text.setLayoutData(gridData);
		text.addModifyListener(new ModifyListener() {
			@Override
			public void modifyText(ModifyEvent e) {
				fireListeners(text);
			}
		});
		createControlDecoration(text);
		return text;
	}

	private Text createTextField(Composite composite, String labelTxt) {
		return createTextField(composite, labelTxt, 0);
	}

}
