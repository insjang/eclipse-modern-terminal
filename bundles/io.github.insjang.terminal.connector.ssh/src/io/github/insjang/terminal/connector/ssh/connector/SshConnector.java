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
 * Martin Oberhuber (Wind River) - [225792] Rename SshConnector.getTelnetSettings() to getSshSettings()
 * Martin Oberhuber (Wind River) - [225853][api] Provide more default functionality in TerminalConnectorImpl
 *******************************************************************************/
package io.github.insjang.terminal.connector.ssh.connector;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.session.ClientSession;

import io.github.insjang.terminal.connector.ISettingsStore;
import io.github.insjang.terminal.connector.ITerminalControl;
import io.github.insjang.terminal.connector.Logger;
import io.github.insjang.terminal.connector.NullSettingsStore;
import io.github.insjang.terminal.connector.provider.AbstractTerminalConnector;

public class SshConnector extends AbstractTerminalConnector {
	private OutputStream fOutputStream;
	private InputStream fInputStream;
	private ChannelShell fChannel;
	private SshConnection fConnection;
	private final SshSettings fSettings;
	private int fWidth;
	private int fHeight;

	public SshConnector() {
		this(new SshSettings());
	}

	public SshConnector(SshSettings settings) {
		fSettings = settings;
	}

	@Override
	public void connect(ITerminalControl control) {
		super.connect(control);
		fConnection = new SshConnection(this, control);
		fConnection.start();
	}

	@Override
	synchronized public void doDisconnect() {
		fConnection.disconnect();
		if (getInputStream() != null) {
			try {
				getInputStream().close();
			} catch (Exception exception) {
				Logger.logException(exception);
			}
		}

		if (getTerminalToRemoteStream() != null) {
			try {
				getTerminalToRemoteStream().close();
			} catch (Exception exception) {
				Logger.logException(exception);
			}
		}
	}

	@Override
	public void setTerminalSize(int newWidth, int newHeight) {
		ChannelShell channel;
		synchronized (this) {
			fWidth = newWidth;
			fHeight = newHeight;
			channel = fChannel;
		}
		sendWindowChange(channel, newWidth, newHeight);
	}

	// the remote only hears about a size it does not have yet
	private static void sendWindowChange(ChannelShell channel, int width, int height) {
		if (channel == null || !channel.isOpen() || width <= 0 || height <= 0
				|| (channel.getPtyColumns() == width && channel.getPtyLines() == height)) {
			return;
		}
		try {
			channel.sendWindowChange(width, height);
		} catch (IOException e) {
			Logger.logException(e);
		}
	}

	/** Last terminal size asked for, {columns, lines}; non-positive while unknown. */
	synchronized int[] getTerminalSize() {
		return new int[] { fWidth, fHeight };
	}

	public InputStream getInputStream() {
		return fInputStream;
	}

	@Override
	public OutputStream getTerminalToRemoteStream() {
		return fOutputStream;
	}

	void setInputStream(InputStream inputStream) {
		fInputStream = inputStream;
	}

	void setOutputStream(OutputStream outputStream) {
		fOutputStream = outputStream;
	}

	/**
	 * Return the SSH Settings.
	 *
	 * @return the settings for a concrete connection.
	 * @since io.github.insjang.terminal.ssh 2.0 renamed from getTelnetSettings()
	 */
	public ISshSettings getSshSettings() {
		return fSettings;
	}

	@Override
	public void setDefaultSettings() {
		fSettings.load(new NullSettingsStore());
	}

	@Override
	public String getSettingsSummary() {
		return fSettings.getSummary();
	}

	@Override
	public void load(ISettingsStore store) {
		fSettings.load(store);
	}

	@Override
	public void save(ISettingsStore store) {
		fSettings.save(store);
	}

	/** The shell channel while connected, else <code>null</code>. */
	public synchronized ChannelShell getChannel() {
		return fChannel;
	}

	/** The SSH session while connected, else <code>null</code> (e.g. to open an SFTP channel on it). */
	public ClientSession getSession() {
		ChannelShell channel = getChannel();
		return channel != null ? channel.getClientSession() : null;
	}

	void setChannel(ChannelShell channel) {
		int width, height;
		synchronized (this) {
			fChannel = channel;
			width = fWidth;
			height = fHeight;
		}
		// the size may have changed while the channel was opening
		sendWindowChange(channel, width, height);
	}
}
