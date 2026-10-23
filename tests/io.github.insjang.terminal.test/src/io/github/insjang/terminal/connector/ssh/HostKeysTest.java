/*******************************************************************************
 * Copyright (c) 2026 insjang and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package io.github.insjang.terminal.connector.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.List;
import java.util.Set;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.junit.jupiter.api.Test;

import io.github.insjang.terminal.connector.ssh.connector.HostKeys;
import io.github.insjang.terminal.connector.ssh.connector.HostKeys.Decision;
import io.github.insjang.terminal.connector.ssh.connector.HostKeys.Known;

public class HostKeysTest {
	/** JUnit removes it after each test: no temporary folders left behind */
	@org.junit.jupiter.api.io.TempDir
	Path tmp;

	private static PublicKey key(String type, int bits) throws Exception {
		return KeyUtils.generateKeyPair(type, bits).getPublic();
	}

	private static Known known(PublicKey... keys) {
		return new Known(List.of(keys), List.of());
	}

	@Test
	public void sameTypeOtherKeyIsRefusedEvenWhenAnotherTypeComesFirst() throws Exception {
		PublicKey ecdsa = key("ecdsa-sha2-nistp256", 256), rsa = key("ssh-rsa", 2048);
		assertEquals(Decision.REFUSE, HostKeys.decide(known(ecdsa, rsa), key("ssh-rsa", 2048)));
	}

	@Test
	public void unknownTypeIsAsked() throws Exception {
		assertEquals(Decision.PROMPT, HostKeys.decide(known(key("ssh-rsa", 2048)), key("ssh-ed25519", 256)));
		assertEquals(Decision.PROMPT, HostKeys.decide(known(), key("ssh-ed25519", 256)));
	}

	@Test
	public void exactMatchIsAccepted() throws Exception {
		PublicKey ecdsa = key("ecdsa-sha2-nistp256", 256), rsa = key("ssh-rsa", 2048);
		assertEquals(Decision.ACCEPT, HostKeys.decide(known(ecdsa, rsa), rsa));
	}

	@Test
	public void revokedKeyIsRefused() throws Exception {
		PublicKey rsa = key("ssh-rsa", 2048);
		assertEquals(Decision.REFUSE, HostKeys.decide(new Known(List.of(rsa), List.of(rsa)), rsa));
	}

	@Test
	public void knownTypesComeFirst() {
		String proposal = "ssh-ed25519,ecdsa-sha2-nistp256,rsa-sha2-512,rsa-sha2-256,ssh-rsa";
		assertEquals("rsa-sha2-512,rsa-sha2-256,ssh-rsa,ssh-ed25519,ecdsa-sha2-nistp256",
				HostKeys.orderAlgorithms(proposal, Set.of("ssh-rsa")));
		assertEquals(proposal, HostKeys.orderAlgorithms(proposal, Set.of()));
	}

	@Test
	public void loadMatchesHostAndPortAndAddAppends() throws Exception {
		Path dir = Files.createTempDirectory(tmp, "kh");
		Path file = dir.resolve("missing/.ssh/known_hosts"); // directory created on add
		PublicKey rsa = key("ssh-rsa", 2048), ed = key("ssh-ed25519", 256);
		HostKeys.add(file, "example.org", 2222, rsa);
		Files.writeString(file, Files.readString(file) + "other.org " + PublicKeyEntry.toString(ed)); // no final newline
		HostKeys.add(file, "example.org", 2222, ed);
		List<PublicKey> keys = HostKeys.load(file, "example.org", 2222).keys();
		assertEquals(2, keys.size());
		assertTrue(KeyUtils.compareKeys(rsa, keys.get(0)) && KeyUtils.compareKeys(ed, keys.get(1)));
		assertEquals(List.of(), HostKeys.load(file, "example.org", 22).keys());
		assertEquals(3, Files.readAllLines(file).size());
	}

	@Test
	public void wildcardsCoverOtherPorts() throws Exception {
		Path file = Files.createTempDirectory(tmp, "kh").resolve("known_hosts");
		PublicKey rsa = key("ssh-rsa", 2048);
		Files.writeString(file, "@revoked * " + PublicKeyEntry.toString(rsa) + "\n[example.org]:2222 "
				+ PublicKeyEntry.toString(rsa) + "\n");
		assertEquals(Decision.REFUSE, HostKeys.decide(HostKeys.load(file, "example.org", 2222), rsa));
	}

	@Test
	public void revokedWithoutPortCoversOtherPorts() throws Exception {
		Path file = Files.createTempDirectory(tmp, "kh").resolve("known_hosts");
		PublicKey rsa = key("ssh-rsa", 2048);
		// as OpenSSH: the entry without a port is the fallback; a revoked key there is refused on any port
		Files.writeString(file, "@revoked example.org " + PublicKeyEntry.toString(rsa) + "\nexample.org "
				+ PublicKeyEntry.toString(rsa) + "\n");
		assertEquals(Decision.REFUSE, HostKeys.decide(HostKeys.load(file, "example.org", 2222), rsa));
		assertEquals(List.of(), HostKeys.load(file, "example.org", 2222).keys()); // a plain one is asked about
		assertEquals(List.of(), HostKeys.load(file, "other.org", 2222).revoked());
	}

	@Test
	public void hostNamesMatchInLowerCase() throws Exception {
		Path file = Files.createTempDirectory(tmp, "kh").resolve("known_hosts");
		PublicKey ed = key("ssh-ed25519", 256);
		HostKeys.add(file, "LocalHost", 2222, ed);
		assertEquals("[localhost]:2222", Files.readString(file).split(" ")[0]);
		assertEquals(Decision.ACCEPT, HostKeys.decide(HostKeys.load(file, "LOCALHOST", 2222), ed));
	}
}
