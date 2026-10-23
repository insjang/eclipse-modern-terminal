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
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.insjang.terminal.connector.ssh.connector.SshAgentClient;

public class SshAgentClientTest {
	private static final Map<String, String> ENV = Map.of("SSH_AUTH_SOCK", "/tmp/agent.sock", "MY", "/run/my.sock");

	@Test
	public void defaultIsSshAuthSockThenTheWindowsPipe() {
		assertEquals("/tmp/agent.sock", SshAgentClient.address(null, ENV, false));
		assertEquals("/tmp/agent.sock", SshAgentClient.address(null, ENV, true));
		assertNull(SshAgentClient.address(null, Map.of(), false));
		assertEquals(SshAgentClient.WINDOWS_PIPE, SshAgentClient.address(null, Map.of(), true));
		assertEquals("\\\\.\\pipe\\openssh-ssh-agent", SshAgentClient.WINDOWS_PIPE);
	}

	@Test
	public void identityAgentAsOpenSshReadsIt() {
		assertNull(SshAgentClient.address("none", ENV, true));
		assertNull(SshAgentClient.address("NONE", ENV, false));
		assertEquals("/tmp/agent.sock", SshAgentClient.address("SSH_AUTH_SOCK", ENV, false));
		assertEquals("/run/my.sock", SshAgentClient.address("$MY", ENV, false));
		assertEquals("/run/my.sock", SshAgentClient.address("${MY}", ENV, false));
		assertNull(SshAgentClient.address("$UNSET", ENV, false));
		assertEquals("\\\\.\\pipe\\pageant.me.abc", SshAgentClient.address("\"\\\\.\\pipe\\pageant.me.abc\"", ENV, true));
		assertEquals(Path.of(System.getProperty("user.home"), ".ssh/agent.sock").toString(),
				SshAgentClient.address("~/.ssh/agent.sock", ENV, false));
	}
}
