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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.sshd.client.config.hosts.KnownHostEntry;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver;

/**
 * known_hosts handling the OpenSSH way: a host known under some key type must show
 * that very key; only a type not known yet for the host may be asked about.
 */
public final class HostKeys {
	public enum Decision {
		ACCEPT, REFUSE, PROMPT
	}

	/** Keys of one host from known_hosts; revoked ones (@revoked) apart. */
	public record Known(List<PublicKey> keys, List<PublicKey> revoked) {
	}

	private static final int DEFAULT_PORT = 22;

	private HostKeys() {
	}

	/**
	 * The server key against the keys known for the host: the same key is accepted, a revoked key
	 * or another key of a known type is refused, a type not known for the host is asked about.
	 */
	public static Decision decide(Known known, PublicKey server) {
		if (known.revoked().stream().anyMatch(k -> KeyUtils.compareKeys(k, server))) {
			return Decision.REFUSE;
		}
		if (known.keys().stream().anyMatch(k -> KeyUtils.compareKeys(k, server))) {
			return Decision.ACCEPT;
		}
		String type = KeyUtils.getKeyType(server);
		return known.keys().stream().anyMatch(k -> type.equals(KeyUtils.getKeyType(k))) ? Decision.REFUSE
				: Decision.PROMPT;
	}

	/** Key types known for the host, e.g. "ssh-ed25519", "ssh-rsa". */
	public static Set<String> types(Known known) {
		return known.keys().stream().map(KeyUtils::getKeyType).collect(Collectors.toSet());
	}

	/**
	 * Puts the host key algorithms of the known types first (stable), so that a server is
	 * verified by the key we already have, as OpenSSH does.
	 */
	public static String orderAlgorithms(String proposal, Collection<String> knownTypes) {
		if (proposal == null || knownTypes.isEmpty()) {
			return proposal;
		}
		List<String> algorithms = new ArrayList<>(List.of(proposal.split(","))); //$NON-NLS-1$
		// rsa-sha2-256 and rsa-sha2-512 are ssh-rsa keys
		algorithms.sort(Comparator.comparing(a -> knownTypes.contains(KeyUtils.getCanonicalKeyType(a)) ? 0 : 1));
		return String.join(",", algorithms); //$NON-NLS-1$
	}

	/**
	 * The entries of known_hosts for host:port; nothing if the file does not exist. The host name is
	 * matched in lower case, as OpenSSH does.
	 */
	public static Known load(Path file, String hostName, int port) throws IOException {
		String host = hostName.toLowerCase(Locale.ROOT);
		List<PublicKey> keys = new ArrayList<>();
		List<PublicKey> revoked = new ArrayList<>();
		if (!Files.isRegularFile(file)) {
			return new Known(keys, revoked);
		}
		for (KnownHostEntry entry : KnownHostEntry.readKnownHostEntries(file)) {
			String marker = entry.getMarker();
			// @cert-authority lines hold CA keys, not host keys
			if (entry.getKeyEntry() == null || "cert-authority".equals(marker)) { //$NON-NLS-1$
				continue;
			}
			// OpenSSH falls back to the entries without a port; for revoked keys only here (the others are asked about)
			if (!matches(entry, host, port)
					&& !("revoked".equals(marker) && port != DEFAULT_PORT && entry.isHostMatch(host, DEFAULT_PORT))) { //$NON-NLS-1$
				continue;
			}
			try {
				PublicKey key = entry.getKeyEntry().resolvePublicKey(null, PublicKeyEntryResolver.IGNORING);
				if (key != null) {
					("revoked".equals(marker) ? revoked : keys).add(key); //$NON-NLS-1$
				}
			} catch (GeneralSecurityException e) {
				// a key type we cannot read: skip it, like OpenSSH skips unknown types
			}
		}
		return new Known(keys, revoked);
	}

	/**
	 * MINA applies patterns without a port (such as "*") to port 22 only; OpenSSH matches them
	 * against "[host]:port" on other ports, so "@revoked *" covers every port.
	 */
	private static boolean matches(KnownHostEntry entry, String host, int port) {
		return entry.isHostMatch(host, port)
				|| (port != DEFAULT_PORT && entry.isHostMatch("[" + host + "]:" + port, DEFAULT_PORT)); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** Appends host:port with the key to known_hosts, creating its directory if needed. */
	public static void add(Path file, String hostName, int port, PublicKey key) throws IOException {
		String host = hostName.toLowerCase(Locale.ROOT);
		String line = (port == DEFAULT_PORT ? host : "[" + host + "]:" + port) + " " + PublicKeyEntry.toString(key) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				+ "\n"; //$NON-NLS-1$
		Files.createDirectories(file.toAbsolutePath().getParent());
		if (Files.exists(file)) {
			byte[] bytes = Files.readAllBytes(file);
			if (bytes.length > 0 && bytes[bytes.length - 1] != '\n') {
				line = "\n" + line; //$NON-NLS-1$
			}
		}
		Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}
}
