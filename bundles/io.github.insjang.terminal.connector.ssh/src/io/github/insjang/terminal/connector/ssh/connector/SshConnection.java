/*******************************************************************************
 * Copyright (c) 2006, 2015 Wind River Systems, Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * Contributors:
 * Michael Scharf (Wind River) - initial API and implementation
 * Martin Oberhuber (Wind River) - fixed copyright headers and beautified
 * Martin Oberhuber (Wind River) - [175686] Adapted to new IJSchService API
 *    - copied code from org.eclipse.team.cvs.ssh2/JSchSession (Copyright IBM)
 * Martin Oberhuber (Wind River) - [198790] make SSH createSession() protected
 * Mikhail Kalugin <fourdman@xored.com> - [201864] Fix Terminal SSH keyboard interactive authentication
 * Martin Oberhuber (Wind River) - [155026] Add keepalives for SSH connection
 * Johnson Ma (Wind River) - [218880] Add UI setting for ssh keepalives
 * Martin Oberhuber (Wind River) - [225792] Rename SshConnector.getTelnetSettings() to getSshSettings()
 * Martin Oberhuber (Wind River) - [168197] Replace JFace MessagDialog by SWT MessageBox
 * Martin Oberhuber (Wind River) - [205674][ssh] Terminal remains "connecting" when authentication is cancelled
 * Michael Scharf (Wind River) - 240420: [terminal][ssh]Channel is not closed when the connection is closed with the close button
 * Martin Oberhuber (Wind River) - [206919] Improve SSH Terminal Error Reporting
 * Andrei Sobolev (Xored) - [250456] Ssh banner message causes IllegalArgumentException popup
 * Anton Leherbauer (Wind River) - [453393] Add support for copying wrapped lines without line break
 * insjang - Apache MINA SSHD instead of JSch
 *******************************************************************************/
package io.github.insjang.terminal.connector.ssh.connector;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.keyboard.UserInteraction;
import org.apache.sshd.client.channel.PtyCapableChannelSession;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.ClientFactoryManager;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.session.ClientSessionImpl;
import org.apache.sshd.client.session.SessionFactory;
import org.apache.sshd.common.FactoryManager;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.io.IoSession;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.common.AttributeRepository.AttributeKey;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.apache.sshd.common.session.SessionContext;
import org.apache.sshd.core.CoreModuleProperties;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.MessageBox;
import io.github.insjang.terminal.connector.ITerminalControl;
import io.github.insjang.terminal.connector.Logger;
import io.github.insjang.terminal.connector.TerminalState;
import io.github.insjang.terminal.connector.ssh.activator.UIPlugin;

public class SshConnection extends Thread {
	// what the emulator understands, 256 colors included
	private static final String TERM = "xterm-256color"; //$NON-NLS-1$
	private static final Path SSH_DIR = Path.of(System.getProperty("user.home"), ".ssh"); //$NON-NLS-1$ //$NON-NLS-2$
	private static final Path KNOWN_HOSTS = SSH_DIR.resolve("known_hosts"); //$NON-NLS-1$
	private static final List<String> DEFAULT_IDENTITIES = List.of("id_ed25519", "id_ecdsa", "id_rsa"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	// lets the client-wide callbacks find the connection a session belongs to
	private static final AttributeKey<SshConnection> CONNECTION = new AttributeKey<>();

	private static int fgNo;
	/* default */ final ITerminalControl fControl;
	private final SshConnector fConn;
	private ClientSession fSession;
	private boolean fDisconnectHasBeenCalled;
	private volatile boolean fAuthCancelled;

	protected SshConnection(SshConnector conn, ITerminalControl control) {
		super("SshConnection-" + fgNo++); //$NON-NLS-1$
		fControl = control;
		fConn = conn;
		fControl.setState(TerminalState.CONNECTING);
	}

	/**
	 * Creates the SSH client shared by all connections: ~/.ssh/known_hosts, the key file of the
	 * connection and the default ~/.ssh identities (plus IdentityFile of ~/.ssh/config), prompts
	 * through the connection.
	 */
	public static SshClient createClient() {
		SshClient client = SshClient.setUpDefaultClient();
		// a terminal may sit idle for hours; keepalives are set per session
		CoreModuleProperties.IDLE_TIMEOUT.set(client, Duration.ZERO);
		CoreModuleProperties.NIO2_READ_TIMEOUT.set(client, Duration.ZERO);
		client.setSessionFactory(new SessionFactory(client) {
			@Override
			protected ClientSessionImpl doCreateSession(IoSession ioSession) throws Exception {
				return new KnownTypesFirstSession(client, ioSession);
			}
		});
		client.setServerKeyVerifier((session, address, key) -> {
			SshConnection connection = of(session);
			return connection != null && connection.verifyHostKey(session, key);
		});
		FilePasswordProvider passphrases = new PassphrasePrompt();
		client.setFilePasswordProvider(passphrases);
		// looked up on every login, so a key created later is found without a restart
		client.setKeyIdentityProvider(session -> {
			SshConnection connection = of(session);
			FileKeyPairProvider keys = new FileKeyPairProvider(identities(
					connection != null ? connection.fConn.getSshSettings().getKeyFile() : null,
					message -> connection.fControl.displayTextInTerminal(message)));
			keys.setPasswordFinder(passphrases);
			return keys.loadKeys(session);
		});
		return client;
	}

	/**
	 * The key file of the settings (if any, ~ for the home), then the default ~/.ssh identities that exist.
	 * A key file that is not a valid path is skipped and reported.
	 */
	/* default */ static List<Path> identities(String keyFile, Consumer<String> skipped) {
		Stream<Path> explicit = Stream.empty();
		if (keyFile != null) {
			try {
				explicit = Stream.of(keyFile.startsWith("~/") || keyFile.startsWith("~\\") //$NON-NLS-1$ //$NON-NLS-2$
						? Path.of(System.getProperty("user.home"), keyFile.substring(2)) : Path.of(keyFile)); //$NON-NLS-1$
			} catch (InvalidPathException e) {
				skipped.accept(NLS.bind(SshMessages.SSH_KEY_FILE_INVALID, keyFile, e.getReason()));
			}
		}
		return Stream.concat(explicit, DEFAULT_IDENTITIES.stream().map(SSH_DIR::resolve)).filter(Files::isRegularFile)
				.distinct().toList();
	}

	// offers the host key types known for the host first, as OpenSSH and JGit do
	private static class KnownTypesFirstSession extends ClientSessionImpl {
		KnownTypesFirstSession(ClientFactoryManager client, IoSession ioSession) throws Exception {
			super(client, ioSession);
		}

		@Override
		protected String resolveAvailableSignaturesProposal(FactoryManager manager) {
			String proposal = super.resolveAvailableSignaturesProposal(manager);
			SshdSocketAddress target = target(getConnectAddress());
			if (target == null) {
				return proposal;
			}
			try {
				return HostKeys.orderAlgorithms(proposal,
						HostKeys.types(HostKeys.load(KNOWN_HOSTS, target.getHostName(), target.getPort())));
			} catch (IOException e) {
				return proposal;
			}
		}
	}

	// the host as connected to (HostName of ~/.ssh/config, as OpenSSH) and its port
	private static SshdSocketAddress target(SocketAddress address) {
		if (address instanceof SshdSocketAddress target) {
			return target;
		}
		return address instanceof InetSocketAddress inet ? new SshdSocketAddress(inet.getHostString(), inet.getPort()) : null;
	}

	/* default */ static SshConnection of(SessionContext session) {
		AttributeRepository context = session instanceof ClientSession s ? s.getConnectionContext() : null;
		return context != null ? context.getAttribute(CONNECTION) : null;
	}

	@Override
	public void run() {
		boolean connectSucceeded = false;
		boolean authenticating = false;
		ISshSettings settings = fConn.getSshSettings();
		String host = settings.getHost();
		int port = settings.getPort();
		try {
			Duration timeout = settings.getTimeout() > 0 ? Duration.ofSeconds(settings.getTimeout()) : null;
			ConnectFuture future = UIPlugin.getDefault().getClient().connect(settings.getUser(), host, port,
					AttributeRepository.ofKeyValuePair(CONNECTION, this), null);
			ClientSession session = (timeout != null ? future.verify(timeout) : future.verify()).getSession();
			synchronized (this) {
				fSession = session;
				// dont continue if disconnect has been requested already
				if (fDisconnectHasBeenCalled) {
					return;
				}
			}
			int keepalive = settings.getKeepalive();
			if (keepalive > 0) {
				CoreModuleProperties.HEARTBEAT_INTERVAL.set(session, Duration.ofSeconds(keepalive));
				CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX.set(session, 6); //give up after 6 tries
			}
			session.setUserInteraction(new Prompts());
			String password = settings.getPassword();
			if (password != null && !password.isEmpty()) {
				session.addPasswordIdentity(password);
			}
			authenticating = true;
			session.auth().verify(); // no timeout: the user may be typing into a dialog
			authenticating = false;
			// if we got disconnected, do not continue
			if (!isSessionConnected()) {
				return;
			}
			// a start folder: the login shell from an exec channel that changes to it first (SSH has no request for the folder)
			String startFolder = settings.getStartFolder();
			PtyCapableChannelSession channel = startFolder == null ? session.createShellChannel()
					: session.createExecChannel(SshSession.startCommand(startFolder));
			channel.setUsePty(true); // an exec channel has none by default
			channel.setPtyType(TERM);
			int[] size = fConn.getTerminalSize();
			if (size[0] > 0 && size[1] > 0) {
				channel.setPtyColumns(size[0]);
				channel.setPtyLines(size[1]);
			}
			channel.setRedirectErrorStream(true);
			// TERM=xterm implies VT100 line wrapping mode
			fControl.setVT100LineWrapping(true);
			if (timeout != null) {
				channel.open().verify(timeout);
			} else {
				channel.open().verify();
			}

			// maybe the terminal was disconnected while we were connecting
			if (isSessionConnected() && channel.isOpen()) {
				connectSucceeded = true;
				fConn.setInputStream(channel.getInvertedOut());
				fConn.setOutputStream(channel.getInvertedIn());
				fConn.setChannel(channel);
				fControl.setState(TerminalState.CONNECTED);
				try {
					// read data until the connection gets terminated
					readDataForever(fConn.getInputStream());
				} catch (InterruptedIOException e) {
					// we got interrupted: we are done...
				}
			}
		} catch (Exception e) {
			synchronized (this) {
				if (fDisconnectHasBeenCalled) {
					return; // closed by the user: nothing to report
				}
			}
			Throwable cause = e;
			while (cause.getCause() != null) {
				cause = cause.getCause();
			}
			String msg = SshMessages.getMessageFor(cause);
			if (authenticating) {
				msg = fAuthCancelled ? SshMessages.SSH_AUTH_CANCEL : SshMessages.SSH_AUTH_FAIL;
			}
			if (!connectSucceeded) {
				msg = NLS.bind(SshMessages.ERROR_CONNECTING, hostPort(), msg);
			}
			connectFailed(msg, msg);
		} finally {
			// make sure the terminal is disconnected when the thread ends
			try {
				disconnect();
			} finally {
				// when reading is done, we set the state to closed
				fControl.setState(TerminalState.CLOSED);
			}
		}
	}

	/* default */ synchronized boolean isSessionConnected() {
		return !fDisconnectHasBeenCalled && fSession != null && fSession.isOpen();
	}

	/* default */ synchronized boolean isDisconnected() {
		return fDisconnectHasBeenCalled;
	}

	/**
	 * disconnect the ssh session
	 */
	void disconnect() {
		interrupt();
		ClientSession session;
		synchronized (this) {
			fDisconnectHasBeenCalled = true;
			session = fSession;
			fSession = null;
		}
		if (session != null) {
			try {
				// SSH_MSG_DISCONNECT, then the session closes once it is written
				session.disconnect(SshConstants.SSH2_DISCONNECT_BY_APPLICATION, "terminal closed"); //$NON-NLS-1$
			} catch (IOException e) {
				// closed already or cannot write: close below
			}
			// a peer that does not let us write must not keep the session (no-op once closed)
			CompletableFuture.delayedExecutor(2, TimeUnit.SECONDS).execute(() -> session.close(true));
		}
	}

	/**
	 * Read the data from the ssh connection and display it in the terminal.
	 * @param in
	 * @throws IOException
	 */
	private void readDataForever(InputStream in) throws IOException {
		// read the data
		byte bytes[] = new byte[32 * 1024];
		int n;
		// read until the thread gets interrupted....
		while ((n = in.read(bytes)) != -1) {
			fControl.getRemoteToTerminalOutputStream().write(bytes, 0, n);
		}
	}

	protected static Display getStandardDisplay() {
		Display display = Display.getCurrent();
		if (display == null) {
			display = Display.getDefault();
		}
		return display;
	}

	private String hostPort() {
		ISshSettings settings = fConn.getSshSettings();
		String hostPort = settings.getHost();
		if (settings.getPort() != ISshSettings.DEFAULT_SSH_PORT) {
			hostPort = hostPort + ':' + settings.getPort();
		}
		return hostPort;
	}

	/** Asks a yes/no question on the UI thread, No being the default; false if the terminal is gone. */
	private boolean askYesNo(String message) {
		boolean[] result = { false };
		getStandardDisplay().syncExec(() -> {
			if (!isDisconnected()) {
				MessageDialog dialog = new MessageDialog(fControl.getShell(), SshMessages.WARNING, null, message,
						MessageDialog.WARNING, 1, IDialogConstants.YES_LABEL, IDialogConstants.NO_LABEL);
				result[0] = dialog.open() == 0;
			}
		});
		return result[0];
	}

	/**
	 * Checks the server key against ~/.ssh/known_hosts (see {@link HostKeys#decide}); asks about a key
	 * type not known for the host and adds the key if the user agrees.
	 */
	/* default */ boolean verifyHostKey(ClientSession session, PublicKey key) {
		String host = fConn.getSshSettings().getHost();
		int port = fConn.getSshSettings().getPort();
		SshdSocketAddress target = target(session.getConnectAddress());
		if (target != null) {
			host = target.getHostName();
			port = target.getPort();
		}
		HostKeys.Known known;
		try {
			known = HostKeys.load(KNOWN_HOSTS, host, port);
		} catch (IOException e) {
			fControl.displayTextInTerminal(NLS.bind(SshMessages.SSH_KNOWN_HOSTS_READ_FAILED, KNOWN_HOSTS, e.getMessage()));
			return false;
		}
		switch (HostKeys.decide(known, key)) {
		case ACCEPT:
			return true;
		case REFUSE:
			warnRefusedKey(known, key);
			return false;
		default:
			if (!(known.keys().isEmpty() ? acceptUnknownKey(key) : acceptOtherKeyType(known, key))) {
				return false;
			}
			try {
				HostKeys.add(KNOWN_HOSTS, host, port, key);
			} catch (IOException e) {
				// connect anyway, the user said yes; the key is asked for again next time
				fControl.displayTextInTerminal(NLS.bind(SshMessages.SSH_KNOWN_HOSTS_WRITE_FAILED, KNOWN_HOSTS, e.getMessage()));
			}
			return true;
		}
	}

	/** Opens a message box on the UI thread; SWT.NO if the terminal is gone. */
	private int showMessage(int style, String title, String message) {
		int[] result = { SWT.NO };
		getStandardDisplay().syncExec(() -> {
			if (!isDisconnected()) {
				MessageBox mb = new MessageBox(fControl.getShell(), style);
				mb.setText(title);
				mb.setMessage(message);
				result[0] = mb.open();
			}
		});
		return result[0];
	}

	private boolean acceptUnknownKey(PublicKey key) {
		return askYesNo(NLS.bind(SshMessages.SSH_UNKNOWN_HOST,
				new Object[] { hostPort(), KeyUtils.getKeyType(key), KeyUtils.getFingerPrint(key), KNOWN_HOSTS }));
	}

	private boolean acceptOtherKeyType(HostKeys.Known known, PublicKey key) {
		return askYesNo(NLS.bind(SshMessages.SSH_OTHER_KEY_TYPE, new Object[] { hostPort(),
				String.join(", ", HostKeys.types(known)), KeyUtils.getKeyType(key), KeyUtils.getFingerPrint(key), KNOWN_HOSTS })); //$NON-NLS-1$
	}

	private void warnRefusedKey(HostKeys.Known known, PublicKey key) {
		String type = KeyUtils.getKeyType(key);
		PublicKey expected = known.keys().stream().filter(k -> type.equals(KeyUtils.getKeyType(k))).findFirst().orElse(null);
		String msg = expected == null // no known key of the type: the server key is @revoked
				? NLS.bind(SshMessages.SSH_REVOKED_HOST, new Object[] { hostPort(), type, KeyUtils.getFingerPrint(key), KNOWN_HOSTS })
				: NLS.bind(SshMessages.SSH_CHANGED_HOST, new Object[] { hostPort(), type, KeyUtils.getFingerPrint(expected),
						type, KeyUtils.getFingerPrint(key), KNOWN_HOSTS });
		showMessage(SWT.ICON_WARNING | SWT.OK, SshMessages.WARNING, msg);
	}

	/** Asks for a password or passphrase on the UI thread; <code>null</code> if cancelled. */
	/* default */ String promptSecret(String message) {
		final String[] retval = new String[1];
		getStandardDisplay().syncExec(() -> {
			if (!isDisconnected()) {
				UserValidationDialog uvd = new UserValidationDialog(null, null, fConn.getSshSettings().getUser(), message);
				uvd.setUsernameMutable(false);
				if (uvd.open() == Window.OK) {
					retval[0] = uvd.getPassword();
				}
			}
		});
		return retval[0];
	}

	// password and keyboard-interactive prompts of one session
	private class Prompts implements UserInteraction {
		@Override
		public void welcome(ClientSession session, String banner, String lang) {
			fControl.displayTextInTerminal(banner.strip().replace("\r\n", "\n").replace("\n", "\r\n")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		}

		@Override
		public String resolveAuthPasswordAttempt(ClientSession session) {
			// the password of the settings has been tried (or there is none)
			if (fAuthCancelled) {
				return null;
			}
			String password = promptSecret(NLS.bind(SshMessages.SSH_PASSWORD, fConn.getSshSettings().getUser(), hostPort()));
			fAuthCancelled = password == null;
			return password;
		}

		@Override
		public String[] interactive(ClientSession session, String name, String instruction, String lang,
				String[] prompt, boolean[] echo) {
			if (prompt.length == 0) {
				// No need to prompt, just return an empty String array
				return new String[0];
			}
			if (fAuthCancelled) {
				return null;
			}
			final String[][] finResult = new String[1][];
			getStandardDisplay().syncExec(() -> {
				if (!isDisconnected()) {
					KeyboardInteractiveDialog dialog = new KeyboardInteractiveDialog(null, null, hostPort(), name,
							instruction, prompt, echo);
					dialog.open();
					finResult[0] = dialog.getResult();
				}
			});
			fAuthCancelled = finResult[0] == null;
			return finResult[0];
		}

		@Override
		public String getUpdatedPassword(ClientSession session, String prompt, String lang) {
			return null; // changing an expired password is not supported
		}
	}

	// passphrases of encrypted keys
	private static class PassphrasePrompt implements FilePasswordProvider {
		@Override
		public String getPassword(SessionContext session, NamedResource resource, int retryIndex) {
			SshConnection connection = of(session);
			return connection != null && !connection.fAuthCancelled
					? connection.promptSecret(NLS.bind(SshMessages.SSH_PASSPHRASE, resource.getName()))
					: null;
		}

		@Override
		public ResourceDecodeResult handleDecodeAttemptResult(SessionContext session, NamedResource resource,
				int retryIndex, String password, Exception err) {
			// a wrong passphrase is asked again (3 tries); cancelled: go on without this key
			return password != null && retryIndex < 2 ? ResourceDecodeResult.RETRY : ResourceDecodeResult.IGNORE;
		}
	}

	private void connectFailed(String terminalText, String msg) {
		Logger.log(terminalText);
		fControl.displayTextInTerminal(terminalText);
	}

}
