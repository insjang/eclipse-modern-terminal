/*******************************************************************************
 * Copyright (c) 2026 insjang and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package io.github.insjang.terminal.connector.ssh.connector;

import org.eclipse.equinox.security.storage.EncodingUtils;
import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;

/**
 * Saved SSH passwords in the Eclipse secure storage, one node per user@host:port.
 * A host keeps at most one node: the one of its saved settings with "Save password" on.
 */
public final class SshPasswords {
	private static final String ROOT = "/io.github.insjang.terminal.ssh/passwords"; //$NON-NLS-1$
	private static final String PASSWORD = "password"; //$NON-NLS-1$
	private static final String HOST = "host"; //$NON-NLS-1$

	private SshPasswords() {
	}

	// '/' would make nested nodes
	private static String path(String user, String host, int port) {
		return ROOT + '/' + EncodingUtils.encodeSlashes((user == null ? "" : user) + '@' + host + ':' + port); //$NON-NLS-1$
	}

	/** The password saved for exactly user@host:port, else <code>null</code>. */
	public static String get(String user, String host, int port) {
		ISecurePreferences preferences = SecurePreferencesFactory.getDefault();
		if (preferences == null || host == null || !preferences.nodeExists(path(user, host, port))) {
			return null;
		}
		try {
			return preferences.node(path(user, host, port)).get(PASSWORD, null);
		} catch (StorageException e) {
			return null;
		}
	}

	/** Saves the password for user@host:port, dropping any other saved for the host. */
	public static void put(String user, String host, int port, String password) {
		ISecurePreferences preferences = SecurePreferencesFactory.getDefault();
		if (preferences == null || host == null) {
			return;
		}
		remove(host);
		ISecurePreferences node = preferences.node(path(user, host, port));
		try {
			node.put(PASSWORD, password, true);
			node.put(HOST, host, false);
		} catch (StorageException e) {
			node.removeNode(); // never keep it unencrypted
		}
	}

	/** Drops the passwords saved for the host, any user and port. */
	public static void remove(String host) {
		ISecurePreferences preferences = SecurePreferencesFactory.getDefault();
		if (preferences == null || host == null || !preferences.nodeExists(ROOT)) {
			return;
		}
		ISecurePreferences root = preferences.node(ROOT);
		for (String name : root.childrenNames()) {
			ISecurePreferences node = root.node(name);
			try {
				if (host.equals(node.get(HOST, null))) {
					node.removeNode();
				}
			} catch (StorageException e) {
				// unreadable: leave it
			}
		}
	}
}
