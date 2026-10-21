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
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.apache.sshd.client.session.ClientSession;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IStatusLineManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.ResourceLocator;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.window.Window;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DropTarget;
import org.eclipse.swt.dnd.DropTargetAdapter;
import org.eclipse.swt.dnd.DropTargetEvent;
import org.eclipse.swt.dnd.FileTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Widget;
import org.eclipse.ui.ISelectionListener;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.ViewPart;

import io.github.insjang.terminal.connector.TerminalState;
import io.github.insjang.terminal.connector.ssh.connector.SshConnector;
import io.github.insjang.terminal.connector.ssh.sftp.RemoteFiles.Entry;
import io.github.insjang.terminal.control.ITerminalViewControl;
import io.github.insjang.terminal.view.ui.ITerminalsView;

/**
 * Files of the SSH session of the selected Modern Terminal tab, over SFTP on the same session (no
 * second login). All SFTP calls run in jobs.
 */
public class FilesView extends ViewPart {
	public static final String ID = "io.github.insjang.terminal.connector.ssh.FilesView"; //$NON-NLS-1$
	private static final String TERMINALS_VIEW = "io.github.insjang.terminal.view.ui.TerminalsView"; //$NON-NLS-1$
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
			.withZone(ZoneId.systemDefault());

	private Composite stack;
	private Label message;
	private Composite browser;
	private Text pathText;
	private TableViewer viewer;
	private final List<Action> selectionActions = new ArrayList<>();
	private Action upAction, homeAction, refreshAction, uploadAction, newFolderAction;

	// the terminal followed, and its SFTP client once open (UI thread only)
	private ITerminalViewControl terminal;
	private boolean connecting; // waiting for the shell or opening SFTP
	private RemoteFiles files;
	private String home = "/"; //$NON-NLS-1$
	private String cwd;
	private final Set<RemoteFiles> opened = new HashSet<>();

	private final ISelectionListener selectionListener = (part, selection) -> {
		if (part instanceof ITerminalsView) {
			follow(selection);
		}
	};

	@Override
	public void createPartControl(Composite parent) {
		stack = new Composite(parent, SWT.NONE);
		stack.setLayout(new StackLayout());
		message = new Label(stack, SWT.WRAP);

		browser = new Composite(stack, SWT.NONE);
		GridLayout layout = new GridLayout(2, false);
		layout.marginWidth = layout.marginHeight = 0;
		browser.setLayout(layout);
		Label label = new Label(browser, SWT.NONE);
		label.setText(FilesMessages.path);
		pathText = new Text(browser, SWT.BORDER | SWT.SINGLE);
		pathText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		pathText.addListener(SWT.DefaultSelection, e -> {
			if (files != null) {
				navigate(RemoteFiles.resolve(cwd != null ? cwd : home, home, pathText.getText()));
			}
		});

		viewer = new TableViewer(browser, SWT.MULTI | SWT.FULL_SELECTION | SWT.BORDER);
		Table table = viewer.getTable();
		table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1));
		table.setHeaderVisible(true);
		viewer.setContentProvider(ArrayContentProvider.getInstance());
		ISharedImages images = PlatformUI.getWorkbench().getSharedImages();
		column(FilesMessages.colName, 220, SWT.LEFT, new ColumnLabelProvider() {
			@Override
			public String getText(Object o) {
				return ((Entry) o).name();
			}

			@Override
			public Image getImage(Object o) {
				return images.getImage(((Entry) o).dir() ? ISharedImages.IMG_OBJ_FOLDER : ISharedImages.IMG_OBJ_FILE);
			}
		});
		column(FilesMessages.colSize, 80, SWT.RIGHT, text(e -> e.dir() ? "" : RemoteFiles.formatSize(e.size()))); //$NON-NLS-1$
		column(FilesMessages.colModified, 130, SWT.LEFT,
				text(e -> e.modified() > 0 ? DATE.format(Instant.ofEpochMilli(e.modified())) : "")); //$NON-NLS-1$
		column(FilesMessages.colPermissions, 90, SWT.LEFT, text(e -> RemoteFiles.permissions(e.permissions())));
		viewer.addDoubleClickListener(e -> {
			Entry entry = first();
			if (entry != null && entry.dir()) {
				navigate(entry.path());
			} else if (entry != null) {
				open(entry);
			}
		});
		viewer.addSelectionChangedListener(e -> updateActions());
		table.addKeyListener(new KeyAdapter() {
			@Override
			public void keyPressed(KeyEvent e) {
				IAction action = switch (e.keyCode) {
				case SWT.DEL -> selectionActions.get(selectionActions.size() - 1);
				case SWT.F2 -> selectionActions.get(1);
				case SWT.F5 -> refreshAction;
				case SWT.BS -> upAction;
				default -> null;
				};
				if (action != null && action.isEnabled()) {
					action.run();
				}
			}
		});

		createActions();
		createDropTarget(table);

		getSite().getPage().addSelectionListener(selectionListener);
		follow(getSite().getPage().getSelection(TERMINALS_VIEW));
	}

	private void column(String title, int width, int style, ColumnLabelProvider provider) {
		TableViewerColumn c = new TableViewerColumn(viewer, style);
		c.getColumn().setText(title);
		c.getColumn().setWidth(width);
		c.setLabelProvider(provider);
	}

	private static ColumnLabelProvider text(java.util.function.Function<Entry, String> f) {
		return new ColumnLabelProvider() {
			@Override
			public String getText(Object o) {
				return f.apply((Entry) o);
			}
		};
	}

	private void createActions() {
		ISharedImages images = PlatformUI.getWorkbench().getSharedImages();
		upAction = action(FilesMessages.up, images.getImageDescriptor(ISharedImages.IMG_TOOL_UP),
				() -> navigate(RemoteFiles.parent(cwd)));
		homeAction = action(FilesMessages.home, images.getImageDescriptor(ISharedImages.IMG_ETOOL_HOME_NAV),
				() -> navigate(home));
		refreshAction = action(FilesMessages.refresh, ResourceLocator
				.imageDescriptorFromBundle("org.eclipse.ui", "icons/full/elcl16/refresh_nav.png").orElse(null), //$NON-NLS-1$ //$NON-NLS-2$
				this::refresh);
		uploadAction = action(FilesMessages.upload, null, this::uploadFromDialog);
		newFolderAction = action(FilesMessages.newFolder, images.getImageDescriptor(ISharedImages.IMG_OBJ_FOLDER),
				this::newFolder);
		// selection actions: rename second (F2), delete last (Del)
		selectionActions.add(action(FilesMessages.download, null, this::download));
		selectionActions.add(action(FilesMessages.rename, null, this::rename));
		selectionActions.add(action(FilesMessages.delete, images.getImageDescriptor(ISharedImages.IMG_TOOL_DELETE),
				this::delete));

		IToolBarManager toolbar = getViewSite().getActionBars().getToolBarManager();
		toolbar.add(upAction);
		toolbar.add(homeAction);
		toolbar.add(refreshAction);

		MenuManager menu = new MenuManager();
		selectionActions.forEach(menu::add);
		menu.add(new Separator());
		menu.add(uploadAction);
		menu.add(newFolderAction);
		menu.add(refreshAction);
		viewer.getTable().setMenu(menu.createContextMenu(viewer.getTable()));
		updateActions();
	}

	private static Action action(String text, ImageDescriptor image, Runnable run) {
		Action a = new Action(text, image) {
			@Override
			public void run() {
				run.run();
			}
		};
		a.setToolTipText(text.replace("...", "")); //$NON-NLS-1$ //$NON-NLS-2$
		return a;
	}

	private void updateActions() {
		boolean ready = files != null && cwd != null;
		int n = selection().size();
		upAction.setEnabled(ready && !cwd.equals("/")); //$NON-NLS-1$
		homeAction.setEnabled(ready);
		refreshAction.setEnabled(ready);
		uploadAction.setEnabled(ready);
		newFolderAction.setEnabled(ready);
		selectionActions.get(0).setEnabled(ready && n > 0);
		selectionActions.get(1).setEnabled(ready && n == 1);
		selectionActions.get(2).setEnabled(ready && n > 0);
	}

	private void createDropTarget(Table table) {
		DropTarget target = new DropTarget(table, DND.DROP_COPY | DND.DROP_DEFAULT);
		target.setTransfer(new Transfer[] { FileTransfer.getInstance() });
		target.addDropListener(new DropTargetAdapter() {
			@Override
			public void dragEnter(DropTargetEvent event) {
				check(event);
			}

			@Override
			public void dragOperationChanged(DropTargetEvent event) {
				check(event);
			}

			private void check(DropTargetEvent event) {
				// copy only, never move the user's files
				event.detail = files != null && cwd != null && (event.operations & DND.DROP_COPY) != 0 ? DND.DROP_COPY
						: DND.DROP_NONE;
			}

			@Override
			public void drop(DropTargetEvent event) {
				if (!(event.data instanceof String[] paths) || files == null || cwd == null) {
					return;
				}
				// onto a folder: into it, else into the folder shown
				String dir = event.item != null && event.item.getData() instanceof Entry e && e.dir() ? e.path() : cwd;
				upload(paths, dir);
			}
		});
	}

	@Override
	public void setFocus() {
		(files != null ? viewer.getControl() : message).setFocus();
	}

	@Override
	public void dispose() {
		getSite().getPage().removeSelectionListener(selectionListener);
		opened.forEach(RemoteFiles::close);
		super.dispose();
	}

	// ---- following the terminal ----

	private void follow(ISelection selection) {
		Object first = selection instanceof IStructuredSelection s ? s.getFirstElement() : null;
		if (first instanceof CTabItem item) {
			if (!item.isDisposed()) {
				follow(item.getData() instanceof ITerminalViewControl t ? t : null);
			}
		} else if (first == null) {
			follow((ITerminalViewControl) null); // no tab, or no terminal view
		}
		// else: text selected in a terminal, the tab is the same
	}

	private void follow(ITerminalViewControl t) {
		if (t == terminal && (connecting || files != null && files.isOpen())) {
			return;
		}
		terminal = t;
		connecting = false;
		files = null;
		cwd = null;
		viewer.setInput(null);
		updateActions();
		connect();
	}

	private SshConnector connector() {
		if (terminal == null || terminal.isDisposed() || terminal.getTerminalConnector() == null) {
			return null;
		}
		return terminal.getTerminalConnector().getAdapter(SshConnector.class);
	}

	private void connect() {
		SshConnector connector = connector();
		if (connector == null) {
			show(FilesMessages.notSsh);
			return;
		}
		ClientSession session = connector.getSession();
		if (session == null || !session.isOpen()) {
			if (terminal.getState() == TerminalState.CONNECTING) {
				// no event tells when the shell is up: look again shortly
				show(FilesMessages.connecting);
				connecting = true;
				ITerminalViewControl t = terminal;
				Display.getCurrent().timerExec(1000, () -> {
					if (!stack.isDisposed() && t == terminal && files == null) {
						connecting = false;
						connect();
					}
				});
			} else {
				show(FilesMessages.notConnected);
			}
			return;
		}
		ITerminalViewControl t = terminal;
		connecting = true;
		background(FilesMessages.openSftp, false, monitor -> {
			RemoteFiles f;
			String h;
			try {
				f = RemoteFiles.of(session);
				h = f.home();
			} catch (IOException e) {
				// e.g. no SFTP subsystem on the server
				ui(() -> {
					if (t == terminal) {
						connecting = false;
						show(FilesMessages.openSftp + ": " + e.getMessage()); //$NON-NLS-1$
					}
				});
				return;
			}
			ui(() -> {
				if (t != terminal) {
					return;
				}
				connecting = false;
				opened.add(f);
				files = f;
				home = h;
				session.addCloseFutureListener(future -> ui(() -> {
					if (files == f) {
						files = null;
						cwd = null;
						viewer.setInput(null);
						updateActions();
						show(FilesMessages.sessionClosed);
					}
				}));
				navigate(home);
			});
		});
	}

	private void show(String text) {
		message.setText(text);
		top(message);
	}

	private void top(Control c) {
		((StackLayout) stack.getLayout()).topControl = c;
		stack.layout();
	}

	// ---- browsing ----

	private void navigate(String dir) {
		RemoteFiles f = files;
		if (f == null) {
			return;
		}
		background(FilesMessages.openSftp, false, monitor -> {
			List<Entry> entries;
			try {
				entries = f.list(dir);
			} catch (IOException e) {
				ui(() -> {
					statusLine().setErrorMessage(NLS.bind(FilesMessages.listFailed, dir, e.getMessage()));
					pathText.setText(cwd != null ? cwd : dir);
				});
				return;
			}
			ui(() -> {
				if (files != f) {
					return;
				}
				boolean same = dir.equals(cwd);
				cwd = dir;
				pathText.setText(dir);
				ISelection keep = same ? viewer.getSelection() : null;
				viewer.setInput(entries);
				if (keep != null) {
					viewer.setSelection(keep);
				}
				statusLine().setErrorMessage(null);
				statusLine().setMessage(NLS.bind(FilesMessages.items, entries.size()));
				top(browser);
				updateActions();
			});
		});
	}

	private void refresh() {
		if (cwd != null) {
			navigate(cwd);
		}
	}

	private IStatusLineManager statusLine() {
		return getViewSite().getActionBars().getStatusLineManager();
	}

	private Entry first() {
		Object o = selection().getFirstElement();
		return o instanceof Entry e ? e : null;
	}

	private IStructuredSelection selection() {
		return viewer.getStructuredSelection();
	}

	private List<Entry> selected() {
		List<Entry> list = new ArrayList<>();
		for (Object o : selection()) {
			list.add((Entry) o);
		}
		return list;
	}

	/** Opens a remote file in an editor; nothing yet. */
	private void open(Entry entry) {
	}

	// ---- actions ----

	private void download() {
		List<Entry> entries = selected();
		RemoteFiles f = files;
		DirectoryDialog dialog = new DirectoryDialog(getSite().getShell());
		dialog.setText(FilesMessages.downloadTo);
		String target = dialog.open();
		if (target == null || entries.isEmpty()) {
			return;
		}
		Path dir = Path.of(target);
		boolean folders = entries.stream().anyMatch(Entry::dir);
		long total = entries.stream().mapToLong(Entry::size).sum();
		Predicate<Path> overwrite = askOverwrite();
		background(FilesMessages.downloading, true, monitor -> {
			// folder sizes are not known before walking them
			monitor.beginTask(FilesMessages.downloading, folders ? IProgressMonitor.UNKNOWN : (int) Math.min(total, Integer.MAX_VALUE));
			for (Entry e : entries) {
				f.download(e.path(), dir, overwrite, bytes(monitor, folders));
			}
			ui(() -> statusLine().setMessage(NLS.bind(FilesMessages.downloaded, dir)));
		});
	}

	private void uploadFromDialog() {
		FileDialog dialog = new FileDialog(getSite().getShell(), SWT.OPEN | SWT.MULTI);
		dialog.setText(FilesMessages.upload.replace("...", "")); //$NON-NLS-1$ //$NON-NLS-2$
		if (dialog.open() == null) {
			return;
		}
		String[] names = dialog.getFileNames();
		String[] paths = new String[names.length];
		for (int i = 0; i < names.length; i++) {
			paths[i] = Path.of(dialog.getFilterPath(), names[i]).toString();
		}
		upload(paths, cwd);
	}

	private void upload(String[] paths, String dir) {
		RemoteFiles f = files;
		Predicate<String> overwrite = askOverwrite();
		background(FilesMessages.uploading, true, monitor -> {
			long total = 0;
			for (String p : paths) {
				total += RemoteFiles.localSize(Path.of(p));
			}
			monitor.beginTask(FilesMessages.uploading, (int) Math.min(total, Integer.MAX_VALUE));
			try {
				for (String p : paths) {
					f.upload(Path.of(p), dir, overwrite, bytes(monitor, total > Integer.MAX_VALUE));
				}
			} finally {
				ui(this::refresh);
			}
			ui(() -> statusLine().setMessage(NLS.bind(FilesMessages.uploaded, dir)));
		});
	}

	private void newFolder() {
		String name = askName(FilesMessages.newFolder.replace("...", ""), FilesMessages.newFolderMessage, ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		if (name != null) {
			RemoteFiles f = files;
			String path = RemoteFiles.join(cwd, name);
			background(FilesMessages.newFolder, false, monitor -> {
				f.mkdir(path);
				ui(this::refresh);
			});
		}
	}

	private void rename() {
		Entry e = first();
		String name = e != null ? askName(FilesMessages.rename.replace("...", ""), FilesMessages.renameMessage, e.name()) //$NON-NLS-1$ //$NON-NLS-2$
				: null;
		if (name != null && !name.equals(e.name())) {
			RemoteFiles f = files;
			background(FilesMessages.rename, false, monitor -> {
				f.rename(e.path(), RemoteFiles.join(RemoteFiles.parent(e.path()), name));
				ui(this::refresh);
			});
		}
	}

	private void delete() {
		List<Entry> entries = selected();
		String question = entries.size() == 1 ? NLS.bind(FilesMessages.deleteOne, entries.get(0).path())
				: NLS.bind(FilesMessages.deleteMany, entries.size());
		if (entries.isEmpty() || !MessageDialog.openConfirm(getSite().getShell(), FilesMessages.deleteTitle, question)) {
			return;
		}
		RemoteFiles f = files;
		background(FilesMessages.deleting, true, monitor -> {
			monitor.beginTask(FilesMessages.deleting, IProgressMonitor.UNKNOWN);
			try {
				for (Entry e : entries) {
					f.delete(e.path(), monitor);
				}
			} finally {
				ui(this::refresh);
			}
		});
	}

	private String askName(String title, String label, String initial) {
		InputDialog dialog = new InputDialog(getSite().getShell(), title, label, initial,
				s -> s.isBlank() || s.contains("/") || s.equals(".") || s.equals("..") ? FilesMessages.invalidName : null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		return dialog.open() == Window.OK ? dialog.getValue() : null;
	}

	/** Asks on the UI thread whether to replace a file; "Overwrite All" stops asking for this transfer. */
	private <T> Predicate<T> askOverwrite() {
		boolean[] all = { false };
		return target -> {
			if (all[0]) {
				return true;
			}
			int[] answer = { 3 };
			Display display = viewer.getControl().getDisplay();
			display.syncExec(() -> answer[0] = new MessageDialog(display.getActiveShell(), FilesMessages.overwriteTitle,
					null, NLS.bind(FilesMessages.overwriteMessage, target), MessageDialog.QUESTION, 0,
					FilesMessages.overwrite, FilesMessages.overwriteAll, FilesMessages.skip, IDialogConstants.CANCEL_LABEL)
					.open());
			switch (answer[0]) {
			case 0:
				return true;
			case 1:
				all[0] = true;
				return true;
			case 2:
				return false;
			default:
				throw new OperationCanceledException(); // Cancel or Esc
			}
		};
	}

	// progress in bytes, or just the file names when the total is not known
	private static IProgressMonitor bytes(IProgressMonitor monitor, boolean unknown) {
		return unknown ? new org.eclipse.core.runtime.ProgressMonitorWrapper(monitor) {
			@Override
			public void worked(int work) {
				// no total
			}
		} : monitor;
	}

	// ---- threads ----

	interface IoWork {
		void run(IProgressMonitor monitor) throws IOException;
	}

	/** Runs SFTP work in a job; an I/O error is shown in a dialog, a cancel quietly. */
	private void background(String name, boolean user, IoWork work) {
		Job job = Job.create(name, monitor -> {
			try {
				work.run(monitor);
			} catch (OperationCanceledException e) {
				return Status.CANCEL_STATUS;
			} catch (IOException e) {
				ui(() -> MessageDialog.openError(getSite().getShell(), FilesMessages.errorTitle,
						name + ": " + e.getMessage())); //$NON-NLS-1$
			} finally {
				monitor.done();
			}
			return Status.OK_STATUS;
		});
		job.setUser(user);
		job.setSystem(!user);
		job.schedule();
	}

	private void ui(Runnable r) {
		Widget w = stack;
		if (w != null && !w.isDisposed()) {
			w.getDisplay().asyncExec(() -> {
				if (!stack.isDisposed()) {
					r.run();
				}
			});
		}
	}
}
