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
		String encoding, boolean savePassword, String startFolder, String forwards) {
	/** Tab properties: the saved session a terminal was launched from, the folder to start in, the local forwards. */
	public static final String PROP_SESSION = "ssh.session"; //$NON-NLS-1$
	public static final String PROP_START_FOLDER = "ssh.startFolder"; //$NON-NLS-1$
	public static final String PROP_FORWARDS = "ssh.forwards"; //$NON-NLS-1$
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
						s.encoding, s.savePassword, s.startFolder, s.forwards }).map(SshSession::enc).collect(Collectors.joining(" "))) //$NON-NLS-1$
				.collect(Collectors.joining("\n")); //$NON-NLS-1$
	}

	/** The sessions of {@link #encode}; a broken line is skipped. Lines from before Start folder have 9 fields, before Forwards 10. */
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
						Integer.parseInt(f[5]), Integer.parseInt(f[6]), nullIfEmpty(f[7]), Boolean.parseBoolean(f[8]),
						f.length > 9 ? nullIfEmpty(f[9]) : null, f.length > 10 ? nullIfEmpty(f[10]) : null));
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

	/**
	 * The command of an exec channel that starts the login shell in the folder: SSH has no request for
	 * the working folder, so a shell changes to it, then replaces itself with "$SHELL -l" (as in
	 * <code>ssh -t host 'cd dir; exec $SHELL -l'</code>). The login shell may be csh or fish, which run
	 * "cd dir; ..." differently (csh stops at a failed cd), so it only starts sh with the folder as an
	 * argument. A folder that cannot be entered leaves the error and the shell in the home. "~" and "~/"
	 * are the home; a leading "-" gets "./".
	 */
	public static String startCommand(String folder) {
		// csh expands "!" even in single quotes and in "csh -c": a \! outside them is "!" for csh and sh
		String dir = quote(folder.startsWith("-") ? "./" + folder : folder).replace("!", "'\\!'"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		return "exec sh -c " + quote(START) + " sh " + dir; //$NON-NLS-1$ //$NON-NLS-2$
	}

	// no ' or \ (quoted for any login shell), no "!" (csh history)
	private static final String START = "case $1 in \"~\") set -- \"$HOME\";; \"~/\"*) set -- \"$HOME/${1#??}\";; esac; " //$NON-NLS-1$
			+ "cd \"$1\"; exec \"$SHELL\" -l"; //$NON-NLS-1$

	/** A POSIX sh word: in single quotes, a ' as '\''. */
	public static String quote(String text) {
		return '\'' + text.replace("'", "'\\''") + '\''; //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** A local port forward, as ssh -L port:host:hostport; the local port listens on 127.0.0.1 only. */
	public record Forward(int localPort, String host, int port) {
		@Override
		public String toString() {
			return localPort + ":" + (host.indexOf(':') >= 0 ? '[' + host + ']' : host) + ':' + port; //$NON-NLS-1$
		}
	}

	/**
	 * The forwards of the Forwards field: port:host:hostport items, separated by spaces or commas; an IPv6
	 * host in brackets. Empty or null gives none.
	 * @throws IllegalArgumentException naming the first item that is not one
	 */
	public static List<Forward> parseForwards(String text) {
		List<Forward> forwards = new ArrayList<>();
		if (text == null) {
			return forwards;
		}
		for (String item : text.trim().split("[\\s,]+")) { //$NON-NLS-1$
			if (item.isEmpty()) {
				continue;
			}
			int first = item.indexOf(':');
			int last = item.lastIndexOf(':');
			String host = first < 0 || last <= first + 1 ? "" : item.substring(first + 1, last); //$NON-NLS-1$
			if (host.startsWith("[") && host.endsWith("]")) { //$NON-NLS-1$ //$NON-NLS-2$
				host = host.substring(1, host.length() - 1);
			}
			int localPort = first < 0 ? -1 : port(item.substring(0, first));
			int port = first < 0 ? -1 : port(item.substring(last + 1));
			if (localPort < 0 || port < 1 || host.isEmpty() || host.contains("[") || host.contains("]") //$NON-NLS-1$ //$NON-NLS-2$
					|| (host.indexOf(':') >= 0 && !item.contains("["))) { //$NON-NLS-1$
				throw new IllegalArgumentException(item);
			}
			forwards.add(new Forward(localPort, host, port));
		}
		return forwards;
	}

	// 0 to 65535 (0 for the local port: any free one), -1 if not a port
	private static int port(String text) {
		if (text.isEmpty() || text.length() > 5 || !text.chars().allMatch(Character::isDigit)) {
			return -1;
		}
		int port = Integer.parseInt(text);
		return port <= 65535 ? port : -1;
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
