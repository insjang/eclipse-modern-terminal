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
import java.io.RandomAccessFile;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.apache.sshd.agent.SshAgent;
import org.apache.sshd.agent.SshAgentFactory;
import org.apache.sshd.agent.SshAgentServer;
import org.apache.sshd.agent.common.AbstractAgentProxy;
import org.apache.sshd.common.FactoryManager;
import org.apache.sshd.common.channel.ChannelFactory;
import org.apache.sshd.common.session.ConnectionService;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;

/**
 * Keys from a running SSH agent, as OpenSSH finds it: a Unix domain socket
 * (SSH_AUTH_SOCK, IdentityAgent) or a Windows named pipe (the Windows OpenSSH
 * agent, Pageant through IdentityAgent). The agent is only asked to list
 * and sign; it is never forwarded to the server.
 */
public final class SshAgentClient extends AbstractAgentProxy {
	/** The Windows OpenSSH agent, used when nothing else is set. */
	public static final String WINDOWS_PIPE = "\\\\.\\pipe\\openssh-ssh-agent"; //$NON-NLS-1$
	// an answer longer than this is not an agent talking
	private static final int MAX_ANSWER = 256 * 1024;
	private final ByteChannel channel;

	private SshAgentClient(ByteChannel channel) {
		super(null);
		this.channel = channel;
	}

	/**
	 * The agent of a connection.
	 *
	 * @param address the session's agent socket or pipe, null for none (see {@link #address})
	 */
	public static SshAgentFactory factory(Function<Session, String> address) {
		return new SshAgentFactory() {
			@Override
			public List<ChannelFactory> getChannelForwardingFactories(FactoryManager manager) {
				return List.of(); // no agent forwarding
			}

			@Override
			public SshAgent createClient(Session session, FactoryManager manager) {
				String a = address.apply(session);
				if (a == null) {
					return null;
				}
				try {
					return open(a);
				} catch (IOException | RuntimeException e) {
					return null; // no agent running: the key files are tried as before
				}
			}

			@Override
			public SshAgentServer createServer(ConnectionService service) throws IOException {
				throw new IOException("no agent forwarding"); //$NON-NLS-1$
			}
		};
	}

	public static SshAgentClient open(String address) throws IOException {
		if (address.startsWith("\\\\.\\pipe\\") || address.startsWith("//./pipe/")) { //$NON-NLS-1$ //$NON-NLS-2$
			@SuppressWarnings("resource") // closed with the channel
			RandomAccessFile pipe = new RandomAccessFile(address, "rw"); //$NON-NLS-1$
			return new SshAgentClient(pipe.getChannel());
		}
		return new SshAgentClient(SocketChannel.open(UnixDomainSocketAddress.of(Path.of(address))));
	}

	/**
	 * Where the agent is, from IdentityAgent of ~/.ssh/config as OpenSSH reads it, else
	 * SSH_AUTH_SOCK, else (Windows) the OpenSSH agent pipe; null for none.
	 *
	 * @param identityAgent the IdentityAgent value, or null
	 * @param env the environment
	 * @param windows whether this is Windows
	 */
	public static String address(String identityAgent, Map<String, String> env, boolean windows) {
		String a = identityAgent != null ? identityAgent.strip() : null;
		if (a != null && a.length() > 1 && a.startsWith("\"") && a.endsWith("\"")) { //$NON-NLS-1$ //$NON-NLS-2$
			a = a.substring(1, a.length() - 1);
		}
		if (a == null || a.isEmpty()) {
			a = env.get("SSH_AUTH_SOCK"); //$NON-NLS-1$
			return a != null && !a.isEmpty() ? a : windows ? WINDOWS_PIPE : null;
		}
		if (a.equalsIgnoreCase("none")) { //$NON-NLS-1$
			return null;
		}
		if (a.equals("SSH_AUTH_SOCK")) { //$NON-NLS-1$
			a = "$SSH_AUTH_SOCK"; //$NON-NLS-1$
		}
		if (a.startsWith("$")) { //$NON-NLS-1$
			String name = a.startsWith("${") && a.endsWith("}") ? a.substring(2, a.length() - 1) : a.substring(1); //$NON-NLS-1$ //$NON-NLS-2$
			String value = env.get(name);
			return value != null && !value.isEmpty() ? value : null;
		}
		if (a.startsWith("~/") || a.startsWith("~\\")) { //$NON-NLS-1$ //$NON-NLS-2$
			a = Path.of(System.getProperty("user.home"), a.substring(2)).toString(); //$NON-NLS-1$
		}
		return a;
	}

	@Override
	protected synchronized Buffer request(Buffer buffer) throws IOException {
		channel.write(ByteBuffer.wrap(buffer.array(), buffer.rpos(), buffer.available()));
		ByteBuffer length = read(4);
		int n = length.getInt();
		if (n <= 0 || n > MAX_ANSWER) {
			throw new IOException("bad agent answer length " + n); //$NON-NLS-1$
		}
		return new ByteArrayBuffer(read(n).array());
	}

	private ByteBuffer read(int n) throws IOException {
		ByteBuffer b = ByteBuffer.allocate(n);
		while (b.hasRemaining()) {
			if (channel.read(b) < 0) {
				throw new IOException("agent closed the connection"); //$NON-NLS-1$
			}
		}
		return b.flip();
	}

	@Override
	public boolean isOpen() {
		return channel.isOpen();
	}

	@Override
	public void close() throws IOException {
		try {
			channel.close();
		} finally {
			super.close();
		}
	}
}
