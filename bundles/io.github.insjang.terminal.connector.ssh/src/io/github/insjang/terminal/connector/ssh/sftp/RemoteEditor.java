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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.apache.sshd.client.session.ClientSession;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.action.IStatusLineManager;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorDescriptor;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorRegistry;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.ide.IDE;

import io.github.insjang.terminal.connector.ssh.activator.UIPlugin;
import io.github.insjang.terminal.connector.ssh.sftp.RemoteFiles.Entry;

/**
 * Edits a remote file in the default Eclipse editor for its name: a local copy under the bundle state
 * location is opened as an EFS local file, and uploaded again when the editor is saved.
 */
public final class RemoteEditor {
	/** Files above this size are only opened after asking. */
	static final long LARGE = 10L * 1024 * 1024;
	// the open edits by local copy
	private static final Map<Path, RemoteEditor> EDITS = new ConcurrentHashMap<>();

	private final ClientSession session;
	private final String remote;
	private final Path local;
	private final Job upload;
	private IEditorPart editor;
	private volatile Entry remoteRead; // the remote file as last read or written
	private volatile long localSynced; // local modification time of what the remote has
	private volatile boolean closed;

	private RemoteEditor(ClientSession session, String remote, Path local) {
		this.session = session;
		this.remote = remote;
		this.local = local;
		// schedule() while running runs it once more afterwards: saves are uploaded in order
		upload = Job.create(NLS.bind(FilesMessages.uploadingFile, remote), this::upload);
	}

	/** The folder of the local copies, emptied at bundle start. */
	public static Path area() {
		return UIPlugin.getDefault().getStateLocation().toFile().toPath().resolve("edit"); //$NON-NLS-1$
	}

	// copies of files of a remote account: only the user may enter the folder (POSIX), so they stay private
	// whatever mode a download gives them
	private static void createArea() throws IOException {
		Path area = Files.createDirectories(area());
		if (Files.getFileStore(area).supportsFileAttributeView(PosixFileAttributeView.class)) {
			Files.setPosixFilePermissions(area, PosixFilePermissions.fromString("rwx------")); //$NON-NLS-1$
		}
	}

	/** Deletes the local copies of an earlier run. */
	public static void cleanArea() {
		deleteTree(area());
	}

	/** Downloads a remote file and opens it in an editor; an editor already open for it is activated. */
	public static void open(IWorkbenchPage page, RemoteFiles files, Entry entry) {
		Path local;
		try {
			local = area().resolve(key(files.getSession(), entry.path())).resolve(RemoteFiles.localName(entry.name()));
		} catch (InvalidPathException e) {
			// localName makes names Windows accepts: this is not expected, but must not end in the event loop
			MessageDialog.openError(page.getWorkbenchWindow().getShell(), FilesMessages.errorTitle,
					entry.path() + ": " + e.getMessage()); //$NON-NLS-1$
			return;
		}
		RemoteEditor open = EDITS.get(local);
		if (open != null && open.editor != null) {
			page.activate(open.editor);
			return;
		}
		if (entry.size() > LARGE && !MessageDialog.openQuestion(page.getWorkbenchWindow().getShell(),
				FilesMessages.errorTitle, NLS.bind(FilesMessages.largeFile, entry.name(), RemoteFiles.formatSize(entry.size())))) {
			return;
		}
		RemoteEditor edit = new RemoteEditor(files.getSession(), entry.path(), local);
		Display display = page.getWorkbenchWindow().getShell().getDisplay();
		Job job = Job.create(NLS.bind(FilesMessages.downloadingFile, entry.path()), monitor -> {
			try {
				createArea();
				Files.createDirectories(local.getParent());
				monitor.beginTask(entry.path(), (int) Math.min(entry.size(), Integer.MAX_VALUE));
				edit.remoteRead = files.get(entry.path(), local, monitor);
				edit.localSynced = Files.getLastModifiedTime(local).toMillis();
			} catch (OperationCanceledException e) {
				return Status.CANCEL_STATUS;
			} catch (IOException e) {
				display.asyncExec(() -> MessageDialog.openError(page.getWorkbenchWindow().getShell(),
						FilesMessages.errorTitle, entry.path() + ": " + e.getMessage())); //$NON-NLS-1$
				return Status.OK_STATUS;
			}
			display.asyncExec(() -> edit.openEditor(page));
			return Status.OK_STATUS;
		});
		job.setUser(true);
		job.schedule();
	}

	private void openEditor(IWorkbenchPage page) {
		try {
			// a text editor would change the bytes on save
			if (binary(local) && new MessageDialog(page.getWorkbenchWindow().getShell(), FilesMessages.errorTitle, null,
					NLS.bind(FilesMessages.binaryFile, remote), MessageDialog.QUESTION, 1, IDialogConstants.YES_LABEL,
					IDialogConstants.NO_LABEL).open() != 0) {
				deleteTree(local.getParent());
				return;
			}
		} catch (IOException e) {
			// the editor shows the error
		}
		try {
			// never the system editor: on Windows that runs a remote .bat, .exe, .lnk, .js ... locally
			IEditorDescriptor d = PlatformUI.getWorkbench().getEditorRegistry()
					.getDefaultEditor(local.getFileName().toString());
			editor = IDE.openEditor(page, local.toUri(),
					editorFor(d != null ? d.getId() : null, d != null && d.isInternal(), d != null && d.isOpenInPlace()),
					true);
		} catch (PartInitException e) {
			MessageDialog.openError(page.getWorkbenchWindow().getShell(), FilesMessages.errorTitle, e.getMessage());
			return;
		}
		if (editor == null) {
			return;
		}
		EDITS.put(local, this);
		// a save leaves the editor clean; the file time tells a save from a revert
		editor.addPropertyListener((source, id) -> {
			if (id == IEditorPart.PROP_DIRTY && !editor.isDirty() && localModified() != localSynced) {
				upload.schedule();
			}
		});
		page.addPartListener(new IPartListener2() {
			@Override
			public void partClosed(IWorkbenchPartReference ref) {
				IWorkbenchPart part = ref.getPart(false);
				if (part == editor) {
					page.removePartListener(this);
					closed = true;
					if (upload.getState() == Job.NONE) {
						finish();
					}
					// else the upload finishes it
				}
			}
		});
	}

	private long localModified() {
		try {
			return Files.getLastModifiedTime(local).toMillis();
		} catch (IOException e) {
			return -1;
		}
	}

	private IStatus upload(IProgressMonitor monitor) {
		long modified = localModified();
		try {
			if (!session.isOpen()) {
				throw new IOException(FilesMessages.sessionClosed);
			}
			RemoteFiles files = RemoteFiles.of(session);
			Entry now = files.stat(remote);
			if (!RemoteFiles.unchanged(remoteRead, now) && !askOverwrite(now)) {
				status(NLS.bind(FilesMessages.notUploaded, remote), true);
				return Status.CANCEL_STATUS;
			}
			remoteRead = files.put(local, remote, monitor);
			localSynced = modified;
			status(NLS.bind(FilesMessages.uploaded, remote), false);
		} catch (OperationCanceledException e) {
			status(NLS.bind(FilesMessages.notUploaded, remote), true);
			return Status.CANCEL_STATUS;
		} catch (IOException e) {
			status(NLS.bind(FilesMessages.uploadFailed, remote, e.getMessage()), true);
		} finally {
			if (closed) {
				Display.getDefault().asyncExec(this::finish);
			}
		}
		return Status.OK_STATUS;
	}

	private boolean askOverwrite(Entry now) {
		boolean[] yes = { false };
		Display.getDefault().syncExec(() -> {
			String message = now == null ? NLS.bind(FilesMessages.remoteDeleted, remote)
					: NLS.bind(FilesMessages.remoteChanged, remote);
			yes[0] = new MessageDialog(Display.getDefault().getActiveShell(), FilesMessages.overwriteTitle, null, message,
					MessageDialog.WARNING, 1, FilesMessages.overwrite, IDialogConstants.CANCEL_LABEL).open() == 0;
		});
		return yes[0];
	}

	private void status(String text, boolean error) {
		Display.getDefault().asyncExec(() -> {
			if (editor == null || closed) {
				return;
			}
			IStatusLineManager line = editor.getEditorSite().getActionBars().getStatusLineManager();
			line.setErrorMessage(error ? text : null);
			line.setMessage(error ? null : text);
		});
	}

	// the editor is gone and no upload runs: drop the copy, unless it holds changes the remote lacks
	private void finish() {
		if (upload.getState() != Job.NONE) {
			Display.getDefault().timerExec(200, this::finish); // still uploading a last save
			return;
		}
		EDITS.remove(local);
		if (Files.exists(local) && localModified() != localSynced) {
			// moved aside, so opening the file again does not overwrite it
			Path kept = local.getParent().resolveSibling(local.getParent().getFileName() + "-" + System.currentTimeMillis()); //$NON-NLS-1$
			try {
				Files.move(local.getParent(), kept);
				kept = kept.resolve(local.getFileName());
			} catch (IOException e) {
				kept = local;
			}
			MessageDialog.openWarning(Display.getDefault().getActiveShell(), FilesMessages.errorTitle,
					NLS.bind(FilesMessages.keptCopy, remote, kept));
		} else {
			deleteTree(local.getParent());
		}
	}

	/** Whether the first 8 KB hold a NUL byte, as git and grep tell binary files. */
	public static boolean binary(Path file) throws IOException {
		try (InputStream in = Files.newInputStream(file)) {
			for (byte b : in.readNBytes(8192)) {
				if (b == 0) {
					return true;
				}
			}
		}
		return false;
	}

	/** The editor registered for the name if it runs inside Eclipse, else the text editor (never external or OLE). */
	public static String editorFor(String id, boolean internal, boolean inPlace) {
		if (id == null || !internal || inPlace || id.equals(IEditorRegistry.SYSTEM_EXTERNAL_EDITOR_ID)
				|| id.equals(IEditorRegistry.SYSTEM_INPLACE_EDITOR_ID)) {
			return EditorsUI.DEFAULT_TEXT_EDITOR_ID;
		}
		return id;
	}

	/** A folder name for user@host:port and the remote path. */
	static String key(ClientSession session, String remote) {
		String id = session.getUsername() + '@' + session.getConnectAddress() + remote;
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
			return HexFormat.of().formatHex(hash, 0, 8);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void deleteTree(Path dir) {
		if (!Files.exists(dir)) {
			return;
		}
		try (Stream<Path> s = Files.walk(dir)) {
			s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		} catch (IOException e) {
			// left for the next start
		}
	}
}
