/*******************************************************************************
 * Copyright (c) 2011, 2018 Wind River Systems, Inc. and others. All rights reserved.
 * This program and the accompanying materials are made available under the terms
 * of the Eclipse Public License 2.0 which accompanies this distribution, and is
 * available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 * Wind River Systems - initial API and implementation
 * Max Weninger (Wind River) - [361363] [TERMINALS] Implement "Pin&Clone" for the "Terminals" view
 *******************************************************************************/
package io.github.insjang.terminal.view.ui.internal.view;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.core.expressions.EvaluationContext;
import org.eclipse.core.expressions.IEvaluationContext;
import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.dnd.ByteArrayTransfer;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DragSource;
import org.eclipse.swt.dnd.DragSourceEvent;
import org.eclipse.swt.dnd.DragSourceListener;
import org.eclipse.swt.dnd.DropTarget;
import org.eclipse.swt.dnd.DropTargetEvent;
import org.eclipse.swt.dnd.DropTargetListener;
import org.eclipse.swt.dnd.FileTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.dnd.TransferData;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Sash;
import org.eclipse.swt.widgets.Widget;
import io.github.insjang.terminal.control.ITerminalViewControl;
import io.github.insjang.terminal.internal.control.impl.TerminalPlugin;
import io.github.insjang.terminal.internal.preferences.ITerminalConstants;
import io.github.insjang.terminal.view.ui.ITerminalsView;
import io.github.insjang.terminal.view.core.ITerminalsConnectorConstants;
import io.github.insjang.terminal.view.ui.internal.Messages;
import io.github.insjang.terminal.view.ui.internal.tabs.DroppedFiles;
import io.github.insjang.terminal.view.ui.internal.tabs.TabFolderManager;
import io.github.insjang.terminal.view.ui.internal.tabs.TabFolderMenuHandler;
import io.github.insjang.terminal.view.ui.internal.tabs.TabFolderToolbarHandler;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.ISources;
import org.eclipse.ui.IViewSite;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.part.IShowInTarget;
import org.eclipse.ui.part.PageBook;
import org.eclipse.ui.part.ShowInContext;
import org.eclipse.ui.part.ViewPart;
import org.osgi.framework.Bundle;

/**
 * Terminal view.
 */
public class TerminalsView extends ViewPart implements ITerminalsView, IShowInTarget {

	// Reference to the main page book control
	private PageBook pageBookControl;
	// Reference to the active tab folder maintaining the consoles
	/* default */ CTabFolder tabFolderControl;
	// The page holding the tab folders, nested in sash forms when the view is split
	private Composite splitRoot;
	// All tab folders of the view, the first one created first
	private final List<CTabFolder> tabFolders = new ArrayList<>();
	// Makes the tab folder holding the focused terminal the active one
	private Listener activationFilter;
	/** WidgetElement.CSS_CLASS_NAME_KEY of the Eclipse CSS engine, and the classes of a view's tab stack */
	private static final String CSS_CLASS = "org.eclipse.e4.ui.css.CssClassName", STACK = "MPartStack", //$NON-NLS-1$ //$NON-NLS-2$
			ACTIVE_STACK = "MPartStack active"; //$NON-NLS-1$
	private boolean viewActive;
	private final IPartListener2 activeListener = new IPartListener2() {
		@Override
		public void partActivated(IWorkbenchPartReference ref) {
			if (ref.getPart(false) == TerminalsView.this) {
				viewActive = true;
				styleTabFolders();
			}
		}

		@Override
		public void partDeactivated(IWorkbenchPartReference ref) {
			if (ref.getPart(false) == TerminalsView.this) {
				viewActive = false;
				styleTabFolders();
			}
		}
	};

	/** As Eclipse marks the active view's tabs: the folder in use, while the view is active. */
	private void styleTabFolders() {
		for (CTabFolder folder : tabFolders) {
			String style = viewActive && folder == tabFolderControl ? ACTIVE_STACK : STACK;
			if (!folder.isDisposed() && !style.equals(folder.getData(CSS_CLASS))) {
				folder.setData(CSS_CLASS, style);
				folder.reskin(SWT.NONE);
			}
		}
	}
	// Frame shown over the drop target while a terminal tab is dragged: four thin bars
	private final List<Control> dropFeedback = new ArrayList<>();
	// Reference to the tab folder manager
	/* default */ TabFolderManager tabFolderManager;
	// Reference to the tab folder menu handler
	private TabFolderMenuHandler tabFolderMenuHandler;
	// Reference to the tab folder toolbar handler
	private TabFolderToolbarHandler tabFolderToolbarHandler;
	// Reference to the empty page control (to be show if no console is open)
	private Control emptyPageControl;
	// The view's memento handler
	private final TerminalsViewMementoHandler mementoHandler = new TerminalsViewMementoHandler();

	/**
	 * Drop zone of the tab strip: the dragged tab is reordered or moved into the tab folder. The
	 * other drop zones are {@link SWT#CENTER} (moved into the tab folder), {@link SWT#LEFT},
	 * {@link SWT#RIGHT}, {@link SWT#TOP} and {@link SWT#BOTTOM} (the tab folder is split) and
	 * {@link SWT#NONE} (nothing to do).
	 */
	public static final int DROP_TABS = -1;

	/**
	 * Property of a restored terminal: the index of its tab folder, in the order of {@link #getLayout()}.
	 */
	public static final String PROP_TAB_FOLDER = "tabFolder"; //$NON-NLS-1$

	// The split layout read from the memento, built when the part control is created
	private String restoredLayout;

	// The properties of the terminals being restored and their tab folders. While they open,
	// a tab folder of the layout is not dropped for being empty.
	private Map<Object, Integer> restoringTabFolders;

	@SuppressWarnings("restriction")
	private IPreferenceStore preferenceStore = TerminalPlugin.getDefault().getPreferenceStore();

	/**
	 * "dummy" transfer just to store the information needed for the DnD
	 *
	 */
	private static class TerminalTransfer extends ByteArrayTransfer {
		// The static terminal transfer type name. Unique per terminals view instance.
		private static final String TYPE_NAME = "terminal-transfer-format:" + UUID.randomUUID().toString(); //$NON-NLS-1$
		// Register the type name and remember the associated unique type id.
		private static final int TYPEID = registerType(TYPE_NAME);

		private CTabItem draggedFolderItem;
		private TabFolderManager draggedTabFolderManager;
		private TerminalsView draggedView;

		/*
		 * Thread save singleton instance creation.
		 */
		private static class LazyInstanceHolder {
			public static TerminalTransfer instance = new TerminalTransfer();
		}

		/**
		 * Constructor.
		 */
		TerminalTransfer() {
		}

		/**
		 * Returns the singleton terminal transfer instance.
		 * @return
		 */
		public static TerminalTransfer getInstance() {
			return LazyInstanceHolder.instance;
		}

		/**
		 * Sets the dragged folder item.
		 *
		 * @param tabFolderItem The dragged folder item or <code>null</code>.
		 */
		public void setDraggedFolderItem(CTabItem tabFolderItem) {
			draggedFolderItem = tabFolderItem;
		}

		/**
		 * Returns the dragged folder item.
		 *
		 * @return The dragged folder item or <code>null</code>.
		 */
		public CTabItem getDraggedFolderItem() {
			return draggedFolderItem;
		}

		/**
		 * Sets the tab folder manager the associated folder item is dragged from.
		 *
		 * @param tabFolderManager The tab folder manager or <code>null</code>.
		 */
		public void setTabFolderManager(TabFolderManager tabFolderManager) {
			draggedTabFolderManager = tabFolderManager;
		}

		/**
		 * Returns the tab folder manager the associated folder item is dragged from.
		 *
		 * @return The tab folder manager or <code>null</code>.
		 */
		public TabFolderManager getTabFolderManager() {
			return draggedTabFolderManager;
		}

		/**
		 * Sets the terminals view the associated folder item is dragged from.
		 *
		 * @param view The terminals view or <code>null</code>.
		 */
		public void setView(TerminalsView view) {
			draggedView = view;
		}

		/**
		 * Returns the terminals view the associated folder item is dragged from.
		 *
		 * @return The terminals view or <code>null</code>.
		 */
		public TerminalsView getView() {
			return draggedView;
		}

		@Override
		protected int[] getTypeIds() {
			return new int[] { TYPEID };
		}

		@Override
		protected String[] getTypeNames() {
			return new String[] { TYPE_NAME };
		}

		@Override
		public void javaToNative(Object data, TransferData transferData) {
		}

		@Override
		public Object nativeToJava(TransferData transferData) {
			return null;
		}
	}

	/**
	 * Constructor.
	 */
	public TerminalsView() {
		super();
	}

	/**
	 * Initialize the drag support.
	 */
	private void addDragSupport() {
		// The event listener is registered as filter. It will receive events from all widgets.
		PlatformUI.getWorkbench().getDisplay().addFilter(SWT.DragDetect, event -> {
			// Only handle events where a CTabFolder is the source
			if (!(event.widget instanceof CTabFolder)) {
				return;
			}
			// TabFolderManager must be created
			if (tabFolderManager == null) {
				return;
			}

			// only for own tab folders
			if (!tabFolders.contains(event.widget)) {
				return;
			}
			final CTabFolder draggedFolder = (CTabFolder) event.widget;

			// Skip drag if DnD is still ongoing (bug 443787)
			if (draggedFolder.getData(DND.DRAG_SOURCE_KEY) != null) {
				return;
			}

			int operations = DND.DROP_MOVE | DND.DROP_DEFAULT;
			final DragSource dragSource = new DragSource(draggedFolder, operations);

			// Initialize the terminal transfer type data
			TerminalTransfer.getInstance().setDraggedFolderItem(draggedFolder.getSelection());
			TerminalTransfer.getInstance().setTabFolderManager(tabFolderManager);
			TerminalTransfer.getInstance().setView(this);

			Transfer[] transferTypes = new Transfer[] { TerminalTransfer.getInstance() };
			dragSource.setTransfer(transferTypes);

			// Add a drag source listener to cleanup after the drag operation finished
			dragSource.addDragListener(new DragSourceListener() {
				@Override
				public void dragStart(DragSourceEvent event) {
				}

				@Override
				public void dragSetData(DragSourceEvent event) {
				}

				@Override
				public void dragFinished(DragSourceEvent event) {
					// dispose this drag-source-listener by disposing its drag-source
					dragSource.dispose();

					// Clear the transfer state so the dragged tab item is not kept
					// referenced after the drag and drop operation finished.
					TerminalTransfer.getInstance().setDraggedFolderItem(null);
					TerminalTransfer.getInstance().setTabFolderManager(null);
					TerminalTransfer.getInstance().setView(null);
					hideDropFeedback();

					// Inhibit the action of CTabFolder's default DragDetect-listeners,
					// fire a mouse-click event on the widget that was dragged.
					draggedFolder.notifyListeners(SWT.MouseUp, null);
				}
			});
		});
	}

	/**
	 * Initialize the drop support on the terminals page book control. The tab folders and the
	 * terminals get their own drop targets when they are created.
	 */
	private void addDropSupport() {
		// The tab folders and terminals need their own drop targets: depending on the platform,
		// drops over a child control are not delivered to the drop target of its parent.
		// The page book drop target covers the empty view.
		addDropSupport(pageBookControl, false);
	}

	/**
	 * Lets the given terminal control take dropped terminal tabs, like its tab folder, and dropped
	 * files, whose paths are typed into the terminal.
	 *
	 * @param terminalControl The control of a terminal in this view. Must not be <code>null</code>.
	 */
	public void addTerminalDropSupport(Control terminalControl) {
		addDropSupport(terminalControl, true);
	}

	private void addDropSupport(Control dropControl, boolean files) {
		int operations = DND.DROP_MOVE | DND.DROP_COPY | DND.DROP_LINK | DND.DROP_DEFAULT;
		final DropTarget target = new DropTarget(dropControl, operations);

		Transfer[] transferTypes = files ? new Transfer[] { TerminalTransfer.getInstance(), FileTransfer.getInstance() }
				: new Transfer[] { TerminalTransfer.getInstance() };
		target.setTransfer(transferTypes);

		target.addDropListener(new DropTargetListener() {
			@Override
			public void dragEnter(DropTargetEvent event) {
				if (FileTransfer.getInstance().isSupportedType(event.currentDataType)) {
					event.detail = fileDropOperation(event);
					return;
				}
				// Accept the move both for a different terminals view (the terminal is moved
				// to the other view) and for the same view (the tab is reordered, moved to
				// another tab folder or split off).
				event.detail = DND.DROP_MOVE;
			}

			@Override
			public void dragOver(DropTargetEvent event) {
				if (FileTransfer.getInstance().isSupportedType(event.currentDataType)) {
					event.detail = fileDropOperation(event);
					showDropFeedback(targetTabFolder(dropControl), SWT.CENTER);
					return;
				}
				CTabItem draggedItem = TerminalTransfer.getInstance().getDraggedFolderItem();
				int zone = dropZone(dropControl, draggedItem);
				event.detail = zone == SWT.NONE ? DND.DROP_NONE : DND.DROP_MOVE;
				// Reordering within the tab strip needs no frame
				boolean reorder = zone == DROP_TABS && draggedItem.getParent() == targetTabFolder(dropControl);
				showDropFeedback(targetTabFolder(dropControl), reorder ? SWT.NONE : zone);
			}

			@Override
			public void dragOperationChanged(DropTargetEvent event) {
				if (FileTransfer.getInstance().isSupportedType(event.currentDataType)) {
					event.detail = fileDropOperation(event);
				}
			}

			@Override
			public void dragLeave(DropTargetEvent event) {
				hideDropFeedback();
			}

			@Override
			public void dropAccept(DropTargetEvent event) {
			}

			@Override
			public void drop(DropTargetEvent event) {
				hideDropFeedback();
				if (event.data instanceof String[] paths) {
					typeDroppedFiles(targetTabFolder(dropControl), paths);
					return;
				}
				CTabItem draggedItem = TerminalTransfer.getInstance().getDraggedFolderItem();
				TerminalsView sourceView = TerminalTransfer.getInstance().getView();
				int zone = dropZone(dropControl, draggedItem);
				if (zone == SWT.NONE) {
					return;
				}
				CTabFolder folder = targetTabFolder(dropControl);

				// Drop on the tab strip the tab is in: reorder the dragged tab in place.
				if (zone == DROP_TABS && draggedItem.getParent() == folder) {
					reorderTabItem(folder, draggedItem, event.x, event.y);
					return;
				}

				// Moving the tab replaces the terminal control, which may be this drop target:
				// do it once the drop event is over.
				dropControl.getDisplay().asyncExec(() -> {
					if (draggedItem.isDisposed() || folder.isDisposed() || tabFolderManager == null) {
						return;
					}
					// Drop on an edge: split the tab folder and move the tab into the new half
					CTabFolder target = zone != DROP_TABS && zone != SWT.CENTER ? splitTabFolder(folder, zone) : folder;
					moveTabItem(draggedItem, target, sourceView);
				});
			}
		});
	}

	/**
	 * Returns the operation for dropped files: copy, or link if the source only offers that. Never
	 * move, the source would delete the files.
	 */
	private static int fileDropOperation(DropTargetEvent event) {
		if ((event.operations & DND.DROP_COPY) != 0) {
			return DND.DROP_COPY;
		}
		return (event.operations & DND.DROP_LINK) != 0 ? DND.DROP_LINK : DND.DROP_NONE;
	}

	/**
	 * Types the paths of the dropped files into the visible terminal of the given tab folder, in the
	 * form its shell understands.
	 */
	@SuppressWarnings("unchecked")
	private void typeDroppedFiles(CTabFolder folder, String[] paths) {
		CTabItem item = folder != null && !folder.isDisposed() ? folder.getSelection() : null;
		if (item == null || !(item.getData() instanceof ITerminalViewControl terminal) || paths.length == 0) {
			return;
		}
		Map<String, Object> properties = (Map<String, Object>) item.getData("properties"); //$NON-NLS-1$
		Object processPath = properties == null ? null
				: properties.get(ITerminalsConnectorConstants.PROP_PROCESS_PATH) instanceof String path ? path
						: properties.get(ITerminalsConnectorConstants.PROP_SHELL);
		terminal.pasteString(DroppedFiles.format(paths, processPath instanceof String s ? s : null,
				Platform.OS_WIN32.equals(Platform.getOS())));
		getSite().getPage().activate(this);
		tabFolderManager.bringToTop(item);
		terminal.setFocus();
	}

	/**
	 * Returns the tab folder a drop on the given drop target control goes to.
	 */
	private CTabFolder targetTabFolder(Control dropControl) {
		CTabFolder folder = findTabFolder(dropControl);
		return folder != null ? folder : tabFolderControl;
	}

	/**
	 * Returns the drop zone below the cursor for the given drop target control and dragged tab, or
	 * {@link SWT#NONE} if the drop would change nothing.
	 */
	private int dropZone(Control dropControl, CTabItem draggedItem) {
		if (draggedItem == null || draggedItem.isDisposed() || tabFolderManager == null) {
			return SWT.NONE;
		}
		CTabFolder folder = findTabFolder(dropControl);
		if (folder == null) {
			// The page book only receives drops while the view is empty
			return draggedItem.getParent() == tabFolderControl ? SWT.NONE : SWT.CENTER;
		}
		// The drop event coordinates are not display-relative on every platform, on Wayland for
		// example they are relative to the shell. The cursor location uses the same coordinate
		// base as toControl.
		Point point = folder.toControl(folder.getDisplay().getCursorLocation());
		int zone = isOnTabStrip(folder, point) ? DROP_TABS : computeDropZone(folder.getClientArea(), point);
		// Moving a tab into its own tab folder, or splitting off the only tab, changes nothing
		if (draggedItem.getParent() == folder
				&& (zone == SWT.CENTER || zone != DROP_TABS && folder.getItemCount() == 1)) {
			return SWT.NONE;
		}
		return zone;
	}

	/**
	 * Computes the drop zone of a point over the terminal area of a tab folder: one of the four
	 * edges, each a quarter of the width or height, or the center.
	 * <p>
	 * This method is internal and only exposed for testing.
	 * </p>
	 *
	 * @param client the terminal area of the tab folder.
	 * @param point the drop location, in the same coordinates as <code>client</code>.
	 * @return {@link #DROP_TABS} above the terminal area, {@link SWT#LEFT}, {@link SWT#RIGHT},
	 *         {@link SWT#TOP}, {@link SWT#BOTTOM} or {@link SWT#CENTER}.
	 */
	public static int computeDropZone(Rectangle client, Point point) {
		if (point.y < client.y) {
			return DROP_TABS;
		}
		double x = (point.x - client.x) / (double) Math.max(1, client.width);
		double y = (point.y - client.y) / (double) Math.max(1, client.height);
		double edge = Math.min(Math.min(x, 1 - x), Math.min(y, 1 - y));
		if (edge >= 0.25) {
			return SWT.CENTER;
		}
		return edge == x ? SWT.LEFT : edge == 1 - x ? SWT.RIGHT : edge == y ? SWT.TOP : SWT.BOTTOM;
	}

	/**
	 * Shows a frame over the part of the tab folder the dragged tab would go to. The frame is made of
	 * child controls of the tab folder, a separate shell is misplaced or left behind by some window
	 * managers.
	 */
	private void showDropFeedback(CTabFolder folder, int zone) {
		if (zone == SWT.NONE || folder == null || folder.isDisposed()) {
			hideDropFeedback();
			return;
		}
		Rectangle area = folder.getClientArea();
		switch (zone) {
		case SWT.LEFT -> area.width /= 2;
		case SWT.RIGHT -> {
			area.x += area.width / 2;
			area.width -= area.width / 2;
		}
		case SWT.TOP -> area.height /= 2;
		case SWT.BOTTOM -> {
			area.y += area.height / 2;
			area.height -= area.height / 2;
		}
		default -> area = new Rectangle(0, 0, folder.getSize().x, folder.getSize().y);
		}
		if (!dropFeedback.isEmpty() && dropFeedback.get(0).getParent() != folder) {
			hideDropFeedback();
		}
		if (dropFeedback.isEmpty()) {
			for (int i = 0; i < 4; i++) {
				Composite bar = new Composite(folder, SWT.NONE);
				bar.setBackground(folder.getDisplay().getSystemColor(SWT.COLOR_LIST_SELECTION));
				dropFeedback.add(bar);
			}
		}
		int width = 3;
		Rectangle[] bars = { new Rectangle(area.x, area.y, area.width, width),
				new Rectangle(area.x, area.y + area.height - width, area.width, width),
				new Rectangle(area.x, area.y, width, area.height),
				new Rectangle(area.x + area.width - width, area.y, width, area.height) };
		for (int i = 0; i < 4; i++) {
			Control bar = dropFeedback.get(i);
			bar.setBounds(bars[i]);
			bar.moveAbove(null);
			bar.setVisible(true);
		}
	}

	private void hideDropFeedback() {
		dropFeedback.forEach(Control::dispose);
		dropFeedback.clear();
	}

	/**
	 * Moves the dragged tab, from this or another terminals view, into the given tab folder.
	 */
	private void moveTabItem(CTabItem draggedItem, CTabFolder folder, TerminalsView sourceView) {
		// The tab folder manager creates the tab in the active tab folder
		tabFolderControl = folder;
		CTabItem item = tabFolderManager.cloneTabItemAfterDrop(draggedItem);
		tabFolderManager.bringToTop(item);
		switchToTabFolderControl();

		// dispose tab item control
		final Control control = draggedItem.getControl();
		draggedItem.setControl(null);
		if (control != null) {
			control.dispose();
		}

		// need to remove the dispose listener first
		DisposeListener disposeListener = (DisposeListener) draggedItem.getData("disposeListener"); //$NON-NLS-1$
		draggedItem.removeDisposeListener(disposeListener);
		draggedItem.dispose();

		// The tab may have left an empty tab folder behind
		if (sourceView != null) {
			sourceView.removeEmptyTabFolders();
		}

		// make sure the "new" terminals view has the focus after dragging a terminal
		setFocus();
	}

	/**
	 * Splits the given tab folder and returns the new, empty tab folder placed at the given side.
	 */
	private CTabFolder splitTabFolder(CTabFolder folder, int side) {
		Composite parent = folder.getParent();
		int[] weights = parent instanceof SashForm sashForm ? sashForm.getWeights() : null;

		// Put a sash form in the place of the tab folder, holding the tab folder and a new one
		SashForm sash = new SashForm(parent, side == SWT.LEFT || side == SWT.RIGHT ? SWT.HORIZONTAL : SWT.VERTICAL);
		sash.moveAbove(folder);
		folder.setParent(sash);
		CTabFolder newFolder = createTabFolder(sash);
		if (side == SWT.LEFT || side == SWT.TOP) {
			newFolder.moveAbove(folder);
		}
		sash.setWeights(new int[] { 1, 1 });
		if (weights != null) {
			((SashForm) parent).setWeights(weights);
		}
		parent.layout(true, true);
		return newFolder;
	}

	/**
	 * Removes the tab folders left without tabs, their neighbors take their space. The last tab
	 * folder is kept, the view shows the empty page instead once it has no tabs.
	 */
	public void removeEmptyTabFolders() {
		if (pageBookControl == null || pageBookControl.isDisposed()) {
			return;
		}
		// Runs later: a tab folder must not be disposed from within the disposal of its last tab
		pageBookControl.getDisplay().asyncExec(() -> {
			if (pageBookControl.isDisposed() || tabFolderManager == null || restoringTabFolders != null) {
				return;
			}
			for (CTabFolder folder : new ArrayList<>(tabFolders)) {
				if (folder.getItemCount() == 0 && tabFolders.size() > 1) {
					removeTabFolder(folder);
				}
			}
			if (!tabFolders.contains(tabFolderControl)) {
				tabFolderControl = tabFolders.stream().filter(f -> f.getItemCount() > 0).findFirst()
						.orElse(tabFolders.get(0));
				CTabItem selection = tabFolderControl.getSelection();
				if (selection != null) {
					tabFolderManager.bringToTop(selection);
				}
			}
			if (tabFolders.size() == 1 && tabFolderControl.getItemCount() == 0) {
				switchToEmptyPageControl();
			}
		});
	}

	private void removeTabFolder(CTabFolder folder) {
		Composite parent = folder.getParent();
		folder.dispose();
		if (parent instanceof SashForm sash) {
			List<Control> rest = sashChildren(sash);
			if (rest.size() == 1) {
				// The sash form is left with a single child: put the child in its place
				Composite grandParent = sash.getParent();
				int[] weights = grandParent instanceof SashForm sashForm ? sashForm.getWeights() : null;
				rest.get(0).setParent(grandParent);
				rest.get(0).moveAbove(sash);
				sash.dispose();
				if (weights != null) {
					((SashForm) grandParent).setWeights(weights);
				}
			}
		}
		splitRoot.layout(true, true);
	}

	/**
	 * Creates and configures a tab folder of this view.
	 */
	private CTabFolder createTabFolder(Composite parent) {
		CTabFolder folder = doCreateTabFolderControl(parent);
		Assert.isNotNull(folder);
		doConfigureTabFolderControl(folder);
		tabFolders.add(folder);
		folder.addDisposeListener(e -> tabFolders.remove(folder));
		if (tabFolderManager != null) {
			tabFolderManager.addTabFolder(folder);
		}
		addDropSupport(folder, false);
		return folder;
	}

	/**
	 * Returns the tab folder of this view containing the given widget, or <code>null</code>.
	 */
	private CTabFolder findTabFolder(Widget widget) {
		for (Control c = widget instanceof Control control ? control : null; c != null; c = c.getParent()) {
			if (c instanceof CTabFolder folder && tabFolders.contains(folder)) {
				return folder;
			}
		}
		return null;
	}

	/**
	 * Returns the split layout of the tab folders, for the memento: <code>T</code> for a tab folder,
	 * <code>H</code> (side by side) or <code>V</code> (one above the other) followed by the number of
	 * children and a weight and a layout for each child. Tokens are separated by spaces.
	 *
	 * @return The layout, <code>T</code> for a view that is not split.
	 */
	public String getLayout() {
		StringBuilder layout = new StringBuilder();
		Control[] children = splitRoot != null && !splitRoot.isDisposed() ? splitRoot.getChildren() : new Control[0];
		if (children.length == 1) {
			describeLayout(children[0], layout);
		} else {
			layout.append("T"); //$NON-NLS-1$
		}
		return layout.toString().trim();
	}

	private static void describeLayout(Control control, StringBuilder layout) {
		if (control instanceof SashForm sash) {
			List<Control> children = sashChildren(sash);
			int[] weights = sash.getWeights();
			layout.append(sash.getOrientation() == SWT.HORIZONTAL ? "H " : "V ").append(children.size()).append(' '); //$NON-NLS-1$ //$NON-NLS-2$
			for (int i = 0; i < children.size(); i++) {
				layout.append(weights[i]).append(' ');
				describeLayout(children.get(i), layout);
			}
		} else {
			layout.append("T "); //$NON-NLS-1$
		}
	}

	private void buildLayout(Composite parent, Iterator<String> tokens) {
		String kind = tokens.next();
		if ("H".equals(kind) || "V".equals(kind)) { //$NON-NLS-1$ //$NON-NLS-2$
			SashForm sash = new SashForm(parent, "H".equals(kind) ? SWT.HORIZONTAL : SWT.VERTICAL); //$NON-NLS-1$
			int[] weights = new int[Integer.parseInt(tokens.next())];
			for (int i = 0; i < weights.length; i++) {
				weights[i] = Integer.parseInt(tokens.next());
				buildLayout(sash, tokens);
			}
			sash.setWeights(weights);
		} else {
			createTabFolder(parent);
		}
	}

	private static List<Control> sashChildren(SashForm sash) {
		return Arrays.stream(sash.getChildren()).filter(c -> !(c instanceof Sash) && !c.isDisposed()).toList();
	}

	/**
	 * Returns the tab folders in the order of {@link #getLayout()}.
	 */
	private List<CTabFolder> getTabFoldersInLayoutOrder() {
		List<CTabFolder> folders = new ArrayList<>();
		if (splitRoot != null && !splitRoot.isDisposed()) {
			Arrays.stream(splitRoot.getChildren()).forEach(c -> collectTabFolders(c, folders));
		}
		return folders;
	}

	private static void collectTabFolders(Control control, List<CTabFolder> folders) {
		if (control instanceof SashForm sash) {
			sashChildren(sash).forEach(c -> collectTabFolders(c, folders));
		} else if (control instanceof CTabFolder folder) {
			folders.add(folder);
		}
	}

	/**
	 * Tells the view which tab folder each terminal being restored belongs in.
	 *
	 * @param tabFolders The properties of each terminal, as its tab keeps them, and the index of its tab folder.
	 */
	public void setRestoringTabFolders(Map<Object, Integer> tabFolders) {
		restoringTabFolders = tabFolders;
	}

	/**
	 * Puts every restored terminal in the tab folder it was saved in, whichever one it opened in,
	 * then drops the tab folders left empty. A terminal opens in the active tab folder, and the
	 * view is not always found to make its own the active one while the workbench starts.
	 */
	public void placeRestoredTerminals() {
		Map<Object, Integer> placements = restoringTabFolders;
		restoringTabFolders = null;
		if (placements != null && pageBookControl != null && !pageBookControl.isDisposed()) {
			List<CTabFolder> folders = getTabFoldersInLayoutOrder();
			for (CTabItem item : getTabItems()) {
				Integer index = placements.get(item.getData("properties")); //$NON-NLS-1$
				if (index != null && index >= 0 && index < folders.size() && item.getParent() != folders.get(index)) {
					moveTabItem(item, folders.get(index), null);
				}
			}
		}
		removeEmptyTabFolders();
	}

	/**
	 * Returns the index of the tab folder holding the given tab, in the order of {@link #getLayout()}.
	 *
	 * @param item A tab of this view.
	 * @return The index, or <code>-1</code>.
	 */
	public int getTabFolderIndex(CTabItem item) {
		return getTabFoldersInLayoutOrder().indexOf(item.getParent());
	}

	/**
	 * Makes the tab folder with the given index, in the order of {@link #getLayout()}, the active one:
	 * new terminals open in it.
	 *
	 * @param index The index. An index out of range is ignored.
	 */
	public void setActiveTabFolder(int index) {
		List<CTabFolder> folders = getTabFoldersInLayoutOrder();
		if (index >= 0 && index < folders.size()) {
			setActiveTabFolder(folders.get(index));
		}
	}

	/**
	 * Makes the given tab folder the active one: the tab folder manager works on its tabs.
	 *
	 * @param folder A tab folder of this view.
	 */
	public void setActiveTabFolder(CTabFolder folder) {
		if (tabFolders.contains(folder)) {
			tabFolderControl = folder;
		}
	}

	/**
	 * Returns the tab items of all tab folders of this view.
	 *
	 * @return The tab items, possibly empty.
	 */
	public CTabItem[] getTabItems() {
		return tabFolders.stream().filter(f -> !f.isDisposed()).flatMap(f -> Arrays.stream(f.getItems()))
				.toArray(CTabItem[]::new);
	}

	/**
	 * Moves the dragged tab to the drop position. Only drops on the tab strip reorder.
	 */
	private void reorderTabItem(CTabFolder folder, CTabItem draggedItem, int x, int y) {
		if (folder == null || folder.isDisposed()) {
			return;
		}

		int from = folder.indexOf(draggedItem);
		if (from == -1) {
			return;
		}

		// Map the display-relative drop coordinates to the tab folder and find the tab below them.
		// A drop on the empty space next to the tabs targets the last position.
		Point point = folder.toControl(x, y);
		if (!isOnTabStrip(folder, point)) {
			// The drop event coordinates are not display-relative on every platform, on
			// Wayland for example they are relative to the shell. Retry with the cursor
			// location, which uses the same coordinate base as toControl.
			point = folder.toControl(folder.getDisplay().getCursorLocation());
		}

		// Only drops on the tab strip reorder.
		if (!isOnTabStrip(folder, point)) {
			return;
		}

		CTabItem targetItem = folder.getItem(point);
		int indexUnderCursor = targetItem != null ? folder.indexOf(targetItem) : -1;

		int to = computeReorderIndex(from, indexUnderCursor, folder.getItemCount());
		if (to != -1) {
			folder.moveItem(from, to);
		}

		// Keep the moved terminal selected and focused.
		tabFolderManager.bringToTop(draggedItem);
		setFocus();
	}

	/**
	 * Returns whether the given point, in tab folder coordinates, is on the tab strip.
	 */
	private static boolean isOnTabStrip(CTabFolder folder, Point point) {
		if (folder.getItem(point) != null) {
			return true;
		}
		return point.y >= 0 && point.y <= folder.getTabHeight();
	}

	/**
	 * Computes the destination index for a tab reorder triggered by a drop.
	 * <p>
	 * This method is internal and only exposed for testing.
	 * </p>
	 *
	 * @param from the current index of the dragged tab.
	 * @param indexUnderCursor the index of the tab below the drop location, or <code>-1</code> if the
	 *            drop did not happen over a tab (for example on the empty space following the last tab).
	 * @param itemCount the total number of tabs in the folder.
	 * @return the index the dragged tab should be moved to, or <code>-1</code> if no move is required
	 *         because the tab would keep its position.
	 */
	public static int computeReorderIndex(int from, int indexUnderCursor, int itemCount) {
		int to = indexUnderCursor != -1 ? indexUnderCursor : itemCount - 1;
		if (to < 0 || to == from) {
			return -1;
		}
		return to;
	}

	@Override
	public void dispose() {
		getSite().getPage().removePartListener(activeListener);
		if (activationFilter != null) {
			Display.getCurrent().removeFilter(SWT.FocusIn, activationFilter);
			Display.getCurrent().removeFilter(SWT.MouseDown, activationFilter);
			activationFilter = null;
		}
		hideDropFeedback();
		// Dispose the tab folder manager
		if (tabFolderManager != null) {
			tabFolderManager.dispose();
			tabFolderManager = null;
		}
		// Dispose the tab folder menu handler
		if (tabFolderMenuHandler != null) {
			tabFolderMenuHandler.dispose();
			tabFolderMenuHandler = null;
		}
		// Dispose the tab folder toolbar handler
		if (tabFolderToolbarHandler != null) {
			tabFolderToolbarHandler.dispose();
			tabFolderToolbarHandler = null;
		}

		super.dispose();
	}

	@Override
	public void init(IViewSite site, IMemento memento) throws PartInitException {
		super.init(site, memento);
		restoreState(memento);
	}

	@Override
	public void createPartControl(Composite parent) {
		// Create the page book control
		pageBookControl = doCreatePageBookControl(parent);
		Assert.isNotNull(pageBookControl);
		// Configure the page book control
		doConfigurePageBookControl(pageBookControl);

		// Create the empty page control
		emptyPageControl = doCreateEmptyPageControl(pageBookControl);
		Assert.isNotNull(emptyPageControl);
		// Configure the empty page control
		doConfigureEmptyPageControl(emptyPageControl);

		// Create the tab folder control (empty), within the page holding the split tab folders
		splitRoot = new Composite(pageBookControl, SWT.NONE);
		splitRoot.setLayout(new FillLayout());
		if (restoredLayout != null) {
			try {
				buildLayout(splitRoot, Arrays.asList(restoredLayout.trim().split(" ")).iterator()); //$NON-NLS-1$
			} catch (RuntimeException e) {
				// unreadable layout: start with a single tab folder
				Arrays.stream(splitRoot.getChildren()).forEach(Control::dispose);
			}
		}
		if (tabFolders.isEmpty()) {
			createTabFolder(splitRoot);
		}
		tabFolderControl = tabFolders.get(0);

		// The tab folder of the focused or clicked terminal is the active one
		activationFilter = event -> {
			CTabFolder folder = findTabFolder(event.widget);
			if (folder != null && folder != tabFolderControl && tabFolderManager != null) {
				tabFolderControl = folder;
				CTabItem selection = folder.getSelection();
				if (selection != null) {
					tabFolderManager.bringToTop(selection);
				}
				styleTabFolders();
			}
		};
		getSite().getPage().addPartListener(activeListener);
		parent.getDisplay().addFilter(SWT.FocusIn, activationFilter);
		parent.getDisplay().addFilter(SWT.MouseDown, activationFilter);

		// Create the tab folder manager
		tabFolderManager = doCreateTabFolderManager(this);
		Assert.isNotNull(tabFolderManager);
		// Set the tab folder manager as the selection provider
		getSite().setSelectionProvider(tabFolderManager);
		// The tab folder manager listens to the first tab folder already, the restored layout may have more
		tabFolders.stream().filter(f -> f != tabFolderControl).forEach(tabFolderManager::addTabFolder);

		// Setup the tab folder menu handler
		tabFolderMenuHandler = doCreateTabFolderMenuHandler(this);
		Assert.isNotNull(tabFolderMenuHandler);
		doConfigureTabFolderMenuHandler(tabFolderMenuHandler);

		// Setup the tab folder toolbar handler
		tabFolderToolbarHandler = doCreateTabFolderToolbarHandler(this);
		Assert.isNotNull(tabFolderToolbarHandler);
		doConfigureTabFolderToolbarHandler(tabFolderToolbarHandler);

		// Show the empty page control by default
		switchToEmptyPageControl();

		String secondaryId = ((IViewSite) getSite()).getSecondaryId();
		if (secondaryId != null) {
			String defaultTitle = getPartName();
			// set title
			setPartName(defaultTitle + " " + secondaryId); //$NON-NLS-1$
		}

		// Initialize DnD support
		addDragSupport();
		addDropSupport();
	}

	/**
	 * Creates the {@link PageBook} instance.
	 *
	 * @param parent The parent composite. Must not be <code>null</code>.
	 * @return The page book instance. Must never be <code>null</code>.
	 */
	protected PageBook doCreatePageBookControl(Composite parent) {
		return new PageBook(parent, SWT.NONE);
	}

	/**
	 * Configure the given page book control.
	 *
	 * @param pagebook The page book control. Must not be <code>null</code>.
	 */
	protected void doConfigurePageBookControl(PageBook pagebook) {
		Assert.isNotNull(pagebook);

		if (getContextHelpId() != null) {
			PlatformUI.getWorkbench().getHelpSystem().setHelp(pagebook, getContextHelpId());
		}
	}

	/**
	 * Returns the context help id associated with the terminals console view instance.
	 * <p>
	 * <b>Note:</b> The default implementation returns the view id as context help id.
	 *
	 * @return The context help id or <code>null</code> if none is associated.
	 */
	@Override
	public String getContextHelpId() {
		return getViewSite().getId();
	}

	/**
	 * Creates the empty page control instance.
	 *
	 * @param parent The parent composite. Must not be <code>null</code>.
	 * @return The empty page control instance. Must never be <code>null</code>.
	 */
	protected Control doCreateEmptyPageControl(Composite parent) {
		Composite composite = new Composite(parent, SWT.NONE);
		composite.setLayout(new GridLayout());
		composite.setLayoutData(new GridData(GridData.FILL_BOTH));
		return composite;
	}

	/**
	 * Configures the empty page control.
	 *
	 * @param control The empty page control. Must not be <code>null</code>.
	 */
	protected void doConfigureEmptyPageControl(Control control) {
		Assert.isNotNull(control);
	}

	/**
	 * Creates the tab folder control instance.
	 *
	 * @param parent The parent composite. Must not be <code>null</code>.
	 * @return The tab folder control instance. Must never be <code>null</code>.
	 */
	protected CTabFolder doCreateTabFolderControl(Composite parent) {
		return new CTabFolder(parent, SWT.NO_REDRAW_RESIZE | SWT.NO_TRIM | SWT.FLAT | SWT.BORDER);
	}

	/**
	 * Configures the tab folder control.
	 *
	 * @param tabFolder The tab folder control. Must not be <code>null</code>.
	 */
	protected void doConfigureTabFolderControl(final CTabFolder tabFolder) {
		Assert.isNotNull(tabFolder);

		// Set the layout data
		tabFolder.setLayoutData(new GridData(GridData.FILL_BOTH));

		// Set the tab gradient coloring from the global preferences
		if (useGradientTabBackgroundColor()) {
			tabFolder.setSelectionBackground(
					new Color[] { JFaceResources.getColorRegistry().get("org.eclipse.ui.workbench.ACTIVE_TAB_BG_START"), //$NON-NLS-1$
							JFaceResources.getColorRegistry().get("org.eclipse.ui.workbench.ACTIVE_TAB_BG_END") //$NON-NLS-1$
					}, new int[] { 100 }, true);
		}
		// the theme draws the tabs as those of a view: the selected one filled and underlined
		tabFolder.setData(CSS_CLASS, STACK);
		tabFolder.reskin(SWT.NONE);

		// Attach the mouse listener
		tabFolder.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseDown(MouseEvent e) {
				if (e.button == 2) {
					// middle mouse button click - close tab
					CTabItem item = tabFolder.getItem(new Point(e.x, e.y));
					if (item != null) {
						item.dispose();
					}
				}
			}

			@Override
			public void mouseDoubleClick(MouseEvent e) {
				if (e.button == 1) {
					// double click on a tab - close tab
					CTabItem item = tabFolder.getItem(new Point(e.x, e.y));
					if (item != null) {
						item.dispose();
					}
				}
			}
		});
	}

	/**
	 * If <code>True</code> is returned, the inner tabs are colored with gradient coloring set in
	 * the Eclipse workbench color settings.
	 *
	 * @return <code>True</code> to use gradient tab colors, <code>false</code> otherwise.
	 */
	protected boolean useGradientTabBackgroundColor() {
		return false;
	}

	/**
	 * Creates the tab folder manager.
	 *
	 * @param parentView The parent view instance. Must not be <code>null</code>.
	 * @return The tab folder manager. Must never be <code>null</code>.
	 */
	protected TabFolderManager doCreateTabFolderManager(ITerminalsView parentView) {
		Assert.isNotNull(parentView);
		return new TabFolderManager(parentView);
	}

	/**
	 * Creates the tab folder menu handler.
	 *
	 * @param parentView The parent view instance. Must not be <code>null</code>.
	 * @return The tab folder menu handler. Must never be <code>null</code>.
	 */
	protected TabFolderMenuHandler doCreateTabFolderMenuHandler(ITerminalsView parentView) {
		Assert.isNotNull(parentView);
		return new TabFolderMenuHandler(parentView);
	}

	/**
	 * Configure the tab folder menu handler
	 *
	 * @param menuHandler The tab folder menu handler. Must not be <code>null</code>.
	 */
	protected void doConfigureTabFolderMenuHandler(TabFolderMenuHandler menuHandler) {
		Assert.isNotNull(menuHandler);
		menuHandler.initialize();
	}

	/**
	 * Creates the tab folder toolbar handler.
	 *
	 * @param parentView The parent view instance. Must not be <code>null</code>.
	 * @return The tab folder toolbar handler. Must never be <code>null</code>.
	 */
	protected TabFolderToolbarHandler doCreateTabFolderToolbarHandler(ITerminalsView parentView) {
		Assert.isNotNull(parentView);
		return new TabFolderToolbarHandler(parentView);
	}

	/**
	 * Configure the tab folder toolbar handler
	 *
	 * @param toolbarHandler The tab folder toolbar handler. Must not be <code>null</code>.
	 */
	protected void doConfigureTabFolderToolbarHandler(TabFolderToolbarHandler toolbarHandler) {
		Assert.isNotNull(toolbarHandler);
		toolbarHandler.initialize();
	}

	@Override
	public void setFocus() {
		// Focus the terminal of the active tab folder, the page book would pick the first one
		CTabItem item = tabFolderControl != null && !tabFolderControl.isDisposed() ? tabFolderControl.getSelection()
				: null;
		if (item != null && item.getData() instanceof ITerminalViewControl terminal) {
			terminal.setFocus();
		} else if (pageBookControl != null) {
			pageBookControl.setFocus();
		}
	}

	@Override
	public void switchToEmptyPageControl() {
		if (pageBookControl != null && !pageBookControl.isDisposed() && emptyPageControl != null
				&& !emptyPageControl.isDisposed()) {
			pageBookControl.showPage(emptyPageControl);
		}
	}

	@Override
	public void switchToTabFolderControl() {
		if (pageBookControl != null && !pageBookControl.isDisposed() && splitRoot != null
				&& !splitRoot.isDisposed()) {
			pageBookControl.showPage(splitRoot);
		}
	}

	@Override
	public <T> T getAdapter(Class<T> adapter) {
		if (CTabFolder.class.isAssignableFrom(adapter)) {
			return adapter.cast(tabFolderControl);
		}
		if (TabFolderManager.class.isAssignableFrom(adapter)) {
			return adapter.cast(tabFolderManager);
		}
		if (TabFolderMenuHandler.class.isAssignableFrom(adapter)) {
			return adapter.cast(tabFolderMenuHandler);
		}
		if (TabFolderToolbarHandler.class.isAssignableFrom(adapter)) {
			return adapter.cast(tabFolderToolbarHandler);
		}
		if (TerminalsViewMementoHandler.class.isAssignableFrom(adapter)) {
			return adapter.cast(mementoHandler);
		}

		return super.getAdapter(adapter);
	}

	@Override
	public void saveState(IMemento memento) {
		super.saveState(memento);
		if (memento == null) {
			return;
		}
		mementoHandler.saveState(this, memento);
	}

	/**
	 * Restore the view state from the given memento.
	 *
	 * @param memento The memento or <code>null</code>.
	 */
	@SuppressWarnings("restriction")
	public void restoreState(IMemento memento) {
		if (memento == null || !preferenceStore.getBoolean(ITerminalConstants.PREF_RESTORE_TERMINALS)) {
			return;
		}
		IMemento connections = memento.getChild("terminalConnections"); //$NON-NLS-1$
		restoredLayout = connections != null ? connections.getString("layout") : null; //$NON-NLS-1$
		mementoHandler.restoreState(this, memento);
	}

	@Override
	public boolean show(ShowInContext context) {
		if (context != null) {
			// Get the selection from the context
			ISelection selection = context.getSelection();

			// The selection must contain elements that can be adapted to IResource, File or IPath
			if (selection instanceof IStructuredSelection && !selection.isEmpty()) {
				boolean isValid = true;

				// Build a new structured selection with the adapted elements
				List<Object> elements = new ArrayList<>();

				Iterator<?> iterator = ((IStructuredSelection) selection).iterator();
				while (iterator.hasNext() && isValid) {
					Object element = iterator.next();
					Object adapted = null;

					if (element instanceof File) {
						if (!elements.contains(element)) {
							elements.add(element);
						}
						continue;
					}
					adapted = element instanceof IAdaptable i ? i.getAdapter(File.class) : null;
					if (adapted == null) {
						adapted = Platform.getAdapterManager().getAdapter(element, File.class);
					}
					if (adapted == null) {
						adapted = Platform.getAdapterManager().loadAdapter(element, File.class.getName());
					}
					if (adapted != null) {
						if (!elements.contains(adapted)) {
							elements.add(adapted);
						}
						continue;
					}

					if (element instanceof IPath) {
						if (!elements.contains(element)) {
							elements.add(element);
						}
						continue;
					}
					adapted = element instanceof IAdaptable i ? i.getAdapter(IPath.class) : null;
					if (adapted == null) {
						adapted = Platform.getAdapterManager().getAdapter(element, IPath.class);
					}
					if (adapted == null) {
						adapted = Platform.getAdapterManager().loadAdapter(element, IPath.class.getName());
					}
					if (adapted != null) {
						if (!elements.contains(adapted)) {
							elements.add(adapted);
						}
						continue;
					}

					Bundle bundle = Platform.getBundle("org.eclipse.core.resources"); //$NON-NLS-1$
					if (bundle != null && bundle.getState() != Bundle.UNINSTALLED
							&& bundle.getState() != Bundle.STOPPING) {
						if (element instanceof org.eclipse.core.resources.IResource) {
							if (!elements.contains(element)) {
								elements.add(element);
							}
							continue;
						}

						adapted = element instanceof IAdaptable i
								? i.getAdapter(org.eclipse.core.resources.IResource.class)
								: null;
						if (adapted == null) {
							adapted = Platform.getAdapterManager().getAdapter(element,
									org.eclipse.core.resources.IResource.class);
						}
						if (adapted == null) {
							adapted = Platform.getAdapterManager().loadAdapter(element,
									org.eclipse.core.resources.IResource.class.getName());
						}
					}
					if (adapted != null) {
						if (!elements.contains(adapted)) {
							elements.add(adapted);
						}
						continue;
					}
					isValid = false;
				}

				// If the selection is valid, fire the command to open the local terminal
				if (isValid) {
					selection = new StructuredSelection(elements);
					ICommandService service = PlatformUI.getWorkbench().getService(ICommandService.class);
					Command command = service != null
							? service.getCommand("io.github.insjang.terminal.connector.local.command.launch") //$NON-NLS-1$
							: null;
					if (command != null && command.isDefined() && command.isEnabled()) {
						try {
							ParameterizedCommand pCmd = ParameterizedCommand.generateCommand(command, null);
							Assert.isNotNull(pCmd);
							IHandlerService handlerSvc = PlatformUI.getWorkbench().getService(IHandlerService.class);
							Assert.isNotNull(handlerSvc);
							IEvaluationContext ctx = handlerSvc.getCurrentState();
							ctx = new EvaluationContext(ctx, selection);
							ctx.addVariable(ISources.ACTIVE_CURRENT_SELECTION_NAME, selection);
							handlerSvc.executeCommandInContext(pCmd, null, ctx);
						} catch (Exception e) {
							// If the platform is in debug mode, we print the exception to the log view
							if (Platform.inDebugMode()) {
								ILog.of(TerminalsView.class).error(Messages.AbstractTriggerCommandHandler_error_executionFailed, e);
							}
						}
					}
					return true;
				}
			}
		}
		return false;
	}
}
