/*******************************************************************************
 * Copyright (c) 2012, 2018 Wind River Systems, Inc. and others. All rights reserved.
 * This program and the accompanying materials are made available under the terms
 * of the Eclipse Public License 2.0 which accompanies this distribution, and is
 * available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 * Wind River Systems - initial API and implementation
 *******************************************************************************/
package io.github.insjang.terminal.connector.ssh.launcher;

import java.util.Map;

import org.eclipse.core.runtime.Assert;
import io.github.insjang.terminal.connector.ssh.connector.ISshSettings;
import io.github.insjang.terminal.connector.ssh.connector.SshPasswords;
import io.github.insjang.terminal.connector.ssh.connector.SshSession;
import io.github.insjang.terminal.view.core.ITerminalsConnectorConstants;
import io.github.insjang.terminal.view.ui.IMementoHandler;
import org.eclipse.ui.IMemento;

/**
 * SSH terminal connection memento handler implementation.
 */
public class SshMementoHandler implements IMementoHandler {

	@Override
	public void saveState(IMemento memento, Map<String, Object> properties) {
		Assert.isNotNull(memento);
		Assert.isNotNull(properties);

		// Do not write the terminal title to the memento -> needs to
		// be recreated at the time of restoration.
		memento.putString(ITerminalsConnectorConstants.PROP_IP_HOST,
				(String) properties.get(ITerminalsConnectorConstants.PROP_IP_HOST));
		Object value = properties.get(ITerminalsConnectorConstants.PROP_IP_PORT);
		memento.putInteger(ITerminalsConnectorConstants.PROP_IP_PORT,
				value instanceof Integer i ? i.intValue() : -1);
		value = properties.get(ITerminalsConnectorConstants.PROP_TIMEOUT);
		memento.putInteger(ITerminalsConnectorConstants.PROP_TIMEOUT,
				value instanceof Integer i ? i.intValue() : -1);
		value = properties.get(ITerminalsConnectorConstants.PROP_SSH_KEEP_ALIVE);
		memento.putInteger(ITerminalsConnectorConstants.PROP_SSH_KEEP_ALIVE,
				value instanceof Integer i ? i.intValue() : -1);
		memento.putString(ITerminalsConnectorConstants.PROP_SSH_USER,
				(String) properties.get(ITerminalsConnectorConstants.PROP_SSH_USER));
		memento.putString(ITerminalsConnectorConstants.PROP_ENCODING,
				(String) properties.get(ITerminalsConnectorConstants.PROP_ENCODING));
		memento.putString(ITerminalsConnectorConstants.PROP_SSH_KEY_FILE,
				(String) properties.get(ITerminalsConnectorConstants.PROP_SSH_KEY_FILE));
		memento.putString(SshSession.PROP_SESSION, (String) properties.get(SshSession.PROP_SESSION));
		memento.putString(SshSession.PROP_START_FOLDER, (String) properties.get(SshSession.PROP_START_FOLDER));
		memento.putString(SshSession.PROP_FORWARDS, (String) properties.get(SshSession.PROP_FORWARDS));

		// The password is stored within the Eclipse secure preferences -> no need to store it to the memento
		//
		// If ever needed, this is an example on how to encrypt the password using 3DES. Do not remove!

		/*
		String password = properties.getStringProperty(ITerminalsConnectorConstants.PROP_SSH_PASSWORD);
		if (password != null) {
			try {
				// Generate a temporary key. In practice, you would save this key.
				// See also Encrypting with DES Using a Pass Phrase.
				// SecretKey key = KeyGenerator.getInstance("DESede").generateKey();

				SecretKeyFactory factory = SecretKeyFactory.getInstance("DESede"); //$NON-NLS-1$
				SecretKey key = factory.generateSecret(new DESKeySpec((ITerminalsConnectorConstants.PROP_SSH_PASSWORD + ".SshMementoHandler").getBytes("UTF-8"))); //$NON-NLS-1$ //$NON-NLS-2$

		        Cipher cipher = Cipher.getInstance("DESede/CBC/PKCS5Padding"); //$NON-NLS-1$
		        cipher.init(Cipher.ENCRYPT_MODE, key);

		        String encrypedPwd = new String(Base64.encode(cipher.doFinal(password.getBytes("UTF-8")))); //$NON-NLS-1$
		        memento.putString(ITerminalsConnectorConstants.PROP_SSH_PASSWORD, encrypedPwd);
		    }
		    catch (Exception e) {
		    	if (Platform.inDebugMode()) e.printStackTrace();
		    }
		}
		*/
	}

	@Override
	public void restoreState(IMemento memento, Map<String, Object> properties) {
		Assert.isNotNull(memento);
		Assert.isNotNull(properties);

		// Restore the terminal properties from the memento
		properties.put(ITerminalsConnectorConstants.PROP_IP_HOST,
				memento.getString(ITerminalsConnectorConstants.PROP_IP_HOST));
		properties.put(ITerminalsConnectorConstants.PROP_IP_PORT,
				memento.getInteger(ITerminalsConnectorConstants.PROP_IP_PORT));
		properties.put(ITerminalsConnectorConstants.PROP_TIMEOUT,
				memento.getInteger(ITerminalsConnectorConstants.PROP_TIMEOUT));
		properties.put(ITerminalsConnectorConstants.PROP_SSH_KEEP_ALIVE,
				memento.getInteger(ITerminalsConnectorConstants.PROP_SSH_KEEP_ALIVE));
		properties.put(ITerminalsConnectorConstants.PROP_SSH_USER,
				memento.getString(ITerminalsConnectorConstants.PROP_SSH_USER));
		properties.put(ITerminalsConnectorConstants.PROP_ENCODING,
				memento.getString(ITerminalsConnectorConstants.PROP_ENCODING));
		properties.put(ITerminalsConnectorConstants.PROP_SSH_KEY_FILE,
				memento.getString(ITerminalsConnectorConstants.PROP_SSH_KEY_FILE));
		properties.put(SshSession.PROP_SESSION, memento.getString(SshSession.PROP_SESSION));
		properties.put(SshSession.PROP_START_FOLDER, memento.getString(SshSession.PROP_START_FOLDER));
		properties.put(SshSession.PROP_FORWARDS, memento.getString(SshSession.PROP_FORWARDS));

		// The password is in the secure storage, saved for exactly this user@host:port only while
		// "Save password" is on for it (see SshPasswords)
		Integer port = memento.getInteger(ITerminalsConnectorConstants.PROP_IP_PORT);
		String password = SshPasswords.get(memento.getString(ITerminalsConnectorConstants.PROP_SSH_USER),
				memento.getString(ITerminalsConnectorConstants.PROP_IP_HOST),
				port != null ? port.intValue() : ISshSettings.DEFAULT_SSH_PORT);

		// Example of restoring the password from an 3DES encrypted string. Do not remove!
		/*
		String encrypedPwd = memento.getString(ITerminalsConnectorConstants.PROP_SSH_PASSWORD);
		if (encrypedPwd != null) {
			try {
				SecretKeyFactory factory = SecretKeyFactory.getInstance("DESede"); //$NON-NLS-1$
				SecretKey key = factory.generateSecret(new DESKeySpec((ITerminalsConnectorConstants.PROP_SSH_PASSWORD + ".SshMementoHandler").getBytes("UTF-8"))); //$NON-NLS-1$ //$NON-NLS-2$

				Cipher cipher = Cipher.getInstance("DESede/CBC/PKCS5Padding"); //$NON-NLS-1$
				cipher.init(Cipher.DECRYPT_MODE, key);

				byte[] encBytes = Base64.decode(encrypedPwd.getBytes("UTF-8")); //$NON-NLS-1$
				byte[] decBytes = cipher.doFinal(encBytes);

				password = new String(decBytes);
			}
			catch (Exception e) {
				if (Platform.inDebugMode()) e.printStackTrace();
			}
		}
		*/

		properties.put(ITerminalsConnectorConstants.PROP_SSH_PASSWORD, password);
	}

}
