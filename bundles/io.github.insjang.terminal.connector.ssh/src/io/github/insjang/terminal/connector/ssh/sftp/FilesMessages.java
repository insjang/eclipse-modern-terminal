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

import org.eclipse.osgi.util.NLS;

public class FilesMessages extends NLS {
	static {
		NLS.initializeMessages("io.github.insjang.terminal.connector.ssh.sftp.FilesMessages", FilesMessages.class); //$NON-NLS-1$
	}

	public static String notSsh;
	public static String notConnected;
	public static String connecting;
	public static String sessionClosed;
	public static String path;
	public static String colName;
	public static String colSize;
	public static String colModified;
	public static String colPermissions;
	public static String up;
	public static String home;
	public static String refresh;
	public static String download;
	public static String upload;
	public static String newFolder;
	public static String rename;
	public static String delete;
	public static String items;
	public static String listFailed;
	public static String errorTitle;
	public static String openSftp;
	public static String downloading;
	public static String uploading;
	public static String deleting;
	public static String downloadTo;
	public static String downloaded;
	public static String uploaded;
	public static String newFolderMessage;
	public static String renameMessage;
	public static String invalidName;
	public static String deleteTitle;
	public static String deleteOne;
	public static String deleteMany;
	public static String overwriteTitle;
	public static String overwriteMessage;
	public static String overwrite;
	public static String overwriteAll;
	public static String skip;
	public static String open;
	public static String downloadingFile;
	public static String uploadingFile;
	public static String largeFile;
	public static String notUploaded;
	public static String uploadFailed;
	public static String remoteChanged;
	public static String remoteDeleted;
	public static String keptCopy;
}
