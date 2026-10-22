/*******************************************************************************
 * Copyright (c) 2026 insjang and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package io.github.insjang.terminal.connector.ssh.sftp;

import java.io.IOException;
import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;

import io.github.insjang.terminal.connector.ssh.sftp.RemoteFiles.Entry;

/**
 * What the Files view browses: the files of an SSH session over SFTP ({@link RemoteFiles}), or the local
 * file system for a local terminal ({@link LocalFiles}). Paths are strings in the form of that system.
 */
public interface FileAccess {
	/** The entries of a folder (no "." and ".."), folders first, then by name. */
	List<Entry> list(String dir) throws IOException;

	void mkdir(String path) throws IOException;

	void rename(String from, String to) throws IOException;

	/** Deletes a file, a link or a folder with all its content; links are never followed. */
	void delete(String path, IProgressMonitor monitor) throws IOException;

	boolean isOpen();

	/** The path of a name in a folder. */
	String child(String dir, String name);

	/** The parent folder, or null for a root. */
	String parentOf(String path);

	/** What the user typed in the path field, made absolute; "~" is the home. */
	String typed(String current, String home, String text);

	/** The folder a shell reported (OSC 7) as a path here, or null if it is none. */
	String fromShell(String path);

	/** A path as the shell of the terminal writes it. */
	String shown(String path);
}
