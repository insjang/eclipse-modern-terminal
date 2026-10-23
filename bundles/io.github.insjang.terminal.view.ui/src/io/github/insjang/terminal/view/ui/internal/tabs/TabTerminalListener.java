/*******************************************************************************
 * Copyright (c) 2011, 2026 Wind River Systems, Inc. and others. All rights reserved.
 * This program and the accompanying materials are made available under the terms
 * of the Eclipse Public License 2.0 which accompanies this distribution, and is
 * available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 * Wind River Systems - initial API and implementation
 *******************************************************************************/
package io.github.insjang.terminal.view.ui.internal.tabs;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.runtime.Assert;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.osgi.util.NLS;
import org.eclipse.jface.fieldassist.FieldDecorationRegistry;
import org.eclipse.jface.viewers.DecorationOverlayIcon;
import org.eclipse.jface.viewers.IDecoration;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.progress.IWorkbenchSiteProgressService;
import io.github.insjang.terminal.connector.TerminalState;
import io.github.insjang.terminal.control.ITerminalListener;
import io.github.insjang.terminal.control.TerminalTitleRequestor;
import io.github.insjang.terminal.internal.control.impl.TerminalPlugin;
import io.github.insjang.terminal.internal.emulator.VT100Emulator;
import io.github.insjang.terminal.internal.preferences.ITerminalConstants;
import io.github.insjang.terminal.view.core.ITerminalsConnectorConstants;
import io.github.insjang.terminal.view.ui.internal.Messages;

/**
 * Terminal tab default terminal listener implementation.
 */
public class TabTerminalListener implements ITerminalListener {
	private static final String TAB_TERMINAL_LISTENER = "TabTerminalListener"; //$NON-NLS-1$
	public static final String PROP_CUSTOM_TITLE = "customTitle"; //$NON-NLS-1$
	/* default */ final TabFolderManager tabFolderManager;
	private CTabItem tabItem;
	private String tabItemTitle;
	private TerminalState state;
	/** Least time between two desktop notifications of one tab. */
	private static final long NOTIFY_INTERVAL_MS = 5000;
	private long lastDesktopNotification;
	// the latest notification not yet handled in the UI thread: a flood of bells posts one runnable
	private final AtomicReference<String[]> pendingNotification = new AtomicReference<>();
	// the tab's image while marked, and the marked image (disposed when cleared)
	private Image unmarkedImage;
	private Image markedImage;

	/**
	 * Move a TabTerminalListener instance to another item (for DnD).
	 *
	 * @param fromItem  item to detach the listener from
	 * @param toItem    item to attach listener to
	 */
	static void move(CTabItem fromItem, CTabItem toItem) {
		TabTerminalListener listener = (TabTerminalListener) fromItem.getData(TAB_TERMINAL_LISTENER);
		if (listener != null) {
			listener.attachTo(toItem);
		}
	}

	/**
	 * Constructor.
	 *
	 * @param tabFolderManager The parent tab folder manager. Must not be <code>null</code>.
	 * @param tabItem The parent tab item. Must not be <code>null</code>.
	 */
	public TabTerminalListener(TabFolderManager tabFolderManager, CTabItem tabItem) {
		super();
		Assert.isNotNull(tabFolderManager);
		Assert.isNotNull(tabItem);
		this.tabFolderManager = tabFolderManager;
		// Remember the tab item title
		tabItemTitle = tabItem.getText();

		attachTo(tabItem);
	}

	private void attachTo(CTabItem item) {
		if (tabItem != null) {
			tabItem.setData(TAB_TERMINAL_LISTENER, null);
		}
		item.setData(TAB_TERMINAL_LISTENER, this);
		item.addDisposeListener(e -> unmark(null));
		tabItem = item;
	}

	/**
	 * Returns the associated parent tab item.
	 *
	 * @return The parent tab item.
	 */
	protected final CTabItem getTabItem() {
		return tabItem;
	}

	@Override
	public void setState(final TerminalState state) {
		this.state = state;
		updateTitle(null, TerminalTitleRequestor.OTHER);

		// The tab item must have been not yet disposed
		final CTabItem item = getTabItem();
		if (item == null || item.isDisposed()) {
			return;
		}

		// Run asynchronously in the display thread
		item.getDisplay().asyncExec(() -> {
			// Turn off the command field (if necessary)
			TabCommandFieldHandler handler = tabFolderManager.getTabCommandFieldHandler(item);
			if (TerminalState.CLOSED.equals(state) && handler != null && handler.hasCommandInputField()) {
				handler.setCommandInputField(false);
				// Trigger a selection changed event to update the action enablements
				// and the status line
				ISelectionProvider provider = tabFolderManager.getParentView().getViewSite().getSelectionProvider();
				Assert.isNotNull(provider);
				provider.setSelection(provider.getSelection());
			} else {
				// Update the status line
				tabFolderManager.updateStatusLine();
			}
		});
	}

	private void updateTitle(final String title, final TerminalTitleRequestor requestor) {
		if (state == null) {
			// first setState hasn't happened yet, it will
			// soon and the title will be update then.
			return;
		}
		final CTabItem item = getTabItem();
		if (item == null || item.isDisposed()) {
			return;
		}
		// Run asynchronously in the display thread
		item.getDisplay().asyncExec(() -> {
			if (item.isDisposed()) {
				// tab has been closed
				return;
			}

			// Get the original terminal properties associated with the tab item
			@SuppressWarnings({ "unchecked" })
			final Map<String, Object> properties = (Map<String, Object>) item.getData("properties"); //$NON-NLS-1$
			if (properties != null && properties
					.get(ITerminalsConnectorConstants.PROP_TITLE_DISABLE_ANSI_TITLE) instanceof Boolean disableAnsi) {
				// Check if terminal title can be updated from ANSI escape sequence
				if (disableAnsi && requestor == TerminalTitleRequestor.ANSI) {
					return;
				}
			}

			// the preference: tabs keep their own names, whatever programs set
			if (requestor == TerminalTitleRequestor.ANSI && !TerminalPlugin.getDefault().getPreferenceStore()
					.getBoolean(ITerminalConstants.PREF_PROGRAM_TITLES)) {
				return;
			}

			// New title must have value.
			if (title != null) {
				if (requestor == TerminalTitleRequestor.MENU) {
					tabItemTitle = title;
					if (properties != null) {
						// Remember the user chosen title
						properties.put(PROP_CUSTOM_TITLE, title);
					}
				} else if (properties != null && properties.get(PROP_CUSTOM_TITLE) != null) {
					return;
				} else {
					tabItemTitle = title;
				}
			}

			// Update the tab item title
			final String newTitle = getTerminalConsoleTabTitle(state);
			if (newTitle != null) {
				item.setText(newTitle);
			}
		});
	}

	/**
	 * Returns the title to set to the terminal console tab for the given state.
	 * <p>
	 * <b>Note:</b> This method is called from {@link #setState(TerminalState)} and
	 *              is expected to by called within the UI thread.
	 *
	 * @param state The terminal state. Must not be <code>null</code>.
	 * @return The terminal console tab title to set or <code>null</code> to leave the title unchanged.
	 */
	protected String getTerminalConsoleTabTitle(TerminalState state) {
		Assert.isNotNull(state);
		Assert.isNotNull(Display.findDisplay(Thread.currentThread()));

		// The tab item must have been not yet disposed
		CTabItem item = getTabItem();
		if (item == null || item.isDisposed()) {
			return null;
		}

		// Get the current tab title
		String oldTitle = item.getText();

		// Construct the new title
		String newTitle = null;

		if (TerminalState.CLOSED.equals(state)) {
			newTitle = NLS.bind(Messages.TabTerminalListener_consoleClosed, tabItemTitle,
					tabFolderManager.state2msg(item, state));
		} else if (TerminalState.CONNECTING.equals(state)) {
			newTitle = NLS.bind(Messages.TabTerminalListener_consoleConnecting, tabItemTitle,
					tabFolderManager.state2msg(item, state));
		} else if (TerminalState.CONNECTED.equals(state)) {
			newTitle = tabItemTitle;
		}

		return newTitle != null && !newTitle.equals(oldTitle) ? newTitle : null;
	}

	/**
	 * Sets Terminal title and checks if originator is ANSI command.
	 * If originator is ANSI command in terminal and user does not want to use
	 * ANSI command to update terminal then return else update title.
	 * @param title Title to update.
	 * @param requestor Item that requests terminal title update.
	 */
	@Override
	public void setTerminalTitle(final String title, final TerminalTitleRequestor requestor) {
		updateTitle(title, requestor);
	}

	/**
	 * Marks the tab when it is not the one shown, and shows a desktop notification
	 * while the Eclipse window is not active. Never a sound.
	 */
	@Override
	public void showNotification(String newTitle, String newMessage) {
		CTabItem first = getTabItem();
		if (first == null || first.isDisposed()) {
			return;
		}
		if (pendingNotification.getAndSet(new String[] { newTitle, newMessage }) != null) {
			return; // a runnable is already posted and takes the latest
		}
		first.getDisplay().asyncExec(() -> {
			String[] latest = pendingNotification.getAndSet(null);
			String title = latest[0], message = latest[1];
			CTabItem item = getTabItem();
			if (item == null || item.isDisposed()) {
				return;
			}
			if (item.getParent().getSelection() != item) {
				mark(item);
			}
			var view = tabFolderManager.getParentView();
			if (!view.getSite().getPage().isPartVisible(view)) {
				// the view's own tab title turns bold, as for a console with new output
				IWorkbenchSiteProgressService progress = view.getSite().getService(IWorkbenchSiteProgressService.class);
				if (progress != null) {
					progress.warnOfContentChange();
				}
			}
			long now = System.currentTimeMillis();
			if (item.getDisplay().getActiveShell() == null && now - lastDesktopNotification >= NOTIFY_INTERVAL_MS
					&& TerminalPlugin.getDefault().getPreferenceStore()
							.getBoolean(ITerminalConstants.PREF_DESKTOP_NOTIFICATIONS)) {
				lastDesktopNotification = now;
				Shell shell = item.getParent().getShell();
				// the tab title is the program's too (OSC 0): cleaned as the notification is
				String tabTitle = VT100Emulator.notificationText(tabItemTitle);
				DesktopNotification.show(shell, item.getImage(),
						title != null ? title : tabTitle != null ? tabTitle : view.getTitle(),
						message != null ? message : Messages.TabTerminalListener_bell, () -> {
							CTabItem target = getTabItem();
							shell.setMinimized(false);
							shell.forceActive();
							if (target != null && !target.isDisposed()) {
								view.getSite().getPage().activate(view);
								tabFolderManager.bringToTop(target);
							}
						});
			}
		});
	}

	private void mark(CTabItem item) {
		if (markedImage != null && item.getImage() == markedImage) {
			return;
		}
		unmark(item);
		Image base = item.getImage();
		ImageDescriptor badge = ImageDescriptor.createFromImage(FieldDecorationRegistry.getDefault()
				.getFieldDecoration(FieldDecorationRegistry.DEC_INFORMATION).getImage());
		markedImage = base != null ? new DecorationOverlayIcon(base, badge, IDecoration.TOP_RIGHT).createImage()
				: badge.createImage();
		unmarkedImage = base;
		item.setImage(markedImage);
	}

	private void unmark(CTabItem item) {
		if (markedImage == null) {
			return;
		}
		if (item != null && !item.isDisposed() && item.getImage() == markedImage) {
			item.setImage(unmarkedImage);
		}
		markedImage.dispose();
		markedImage = null;
		unmarkedImage = null;
	}

	/**
	 * The tab is shown: its notification marker goes. UI thread.
	 *
	 * @param item the tab item
	 */
	static void shown(CTabItem item) {
		if (item != null && !item.isDisposed() && item.getData(TAB_TERMINAL_LISTENER) instanceof TabTerminalListener l) {
			l.unmark(item);
		}
	}

	@Override
	public void setTerminalSelectionChanged() {
		tabFolderManager.fireTerminalSelectionChanged();
	}
}
