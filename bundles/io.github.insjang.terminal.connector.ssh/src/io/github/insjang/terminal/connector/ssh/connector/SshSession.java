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

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;

/**
 * A saved SSH session of the Launch Terminal dialog. No secret in here: a password saved with
 * the session is in the secure storage ({@link SshPasswords}).
 * The sessions are one instance preference of the bundle, a line per session.
 */
public record SshSession(String name, String host, int port, String user, String keyFile, int timeout, int keepalive,
		String encoding, boolean savePassword) {
	private static final String NODE = "io.github.insjang.terminal.connector.ssh"; //$NON-NLS-1$
	private static final String KEY = "sessions"; //$NON-NLS-1$

	/** user@host, with :port when not 22. */
	public static String defaultName(String user, String host, int port) {
		String name = (user == null || user.isEmpty() ? "" : user + '@') + host; //$NON-NLS-1$
		return port == ISshSettings.DEFAULT_SSH_PORT ? name : name + ':' + port;
	}

	/** One line per session, each field URL-encoded (so no space or line break inside), sorted by name. */
	public static String encode(List<SshSession> sessions) {
		return sessions.stream().sorted(Comparator.comparing(SshSession::name, String.CASE_INSENSITIVE_ORDER))
				.map(s -> Arrays.stream(new Object[] { s.name, s.host, s.port, s.user, s.keyFile, s.timeout, s.keepalive,
						s.encoding, s.savePassword }).map(SshSession::enc).collect(Collectors.joining(" "))) //$NON-NLS-1$
				.collect(Collectors.joining("\n")); //$NON-NLS-1$
	}

	/** The sessions of {@link #encode}; a broken line is skipped. */
	public static List<SshSession> decode(String text) {
		List<SshSession> sessions = new ArrayList<>();
		if (text == null) {
			return sessions;
		}
		for (String line : text.split("\n")) { //$NON-NLS-1$
			String[] f = line.split(" ", -1); //$NON-NLS-1$
			if (f.length < 9) {
				continue;
			}
			try {
				for (int i = 0; i < f.length; i++) {
					f[i] = URLDecoder.decode(f[i], StandardCharsets.UTF_8);
				}
				sessions.add(new SshSession(f[0], f[1], Integer.parseInt(f[2]), f[3], nullIfEmpty(f[4]),
						Integer.parseInt(f[5]), Integer.parseInt(f[6]), nullIfEmpty(f[7]), Boolean.parseBoolean(f[8])));
			} catch (IllegalArgumentException e) {
				// a bad escape or number: skip that line
			}
		}
		return sessions;
	}

	private static String enc(Object value) {
		return URLEncoder.encode(value == null ? "" : value.toString(), StandardCharsets.UTF_8); //$NON-NLS-1$
	}

	private static String nullIfEmpty(String value) {
		return value.isEmpty() ? null : value;
	}

	/** True if the session logs in as the same user@host:port, so it shares the saved password. */
	public boolean sameLogin(SshSession other) {
		return host.equals(other.host) && port == other.port && String.valueOf(user).equals(String.valueOf(other.user));
	}

	public static List<SshSession> load() {
		return decode(prefs().get(KEY, null));
	}

	public static void save(List<SshSession> sessions) {
		IEclipsePreferences prefs = prefs();
		prefs.put(KEY, encode(sessions));
		try {
			prefs.flush();
		} catch (BackingStoreException e) {
			// kept in memory, written on exit
		}
	}

	private static IEclipsePreferences prefs() {
		return InstanceScope.INSTANCE.getNode(NODE);
	}
}
