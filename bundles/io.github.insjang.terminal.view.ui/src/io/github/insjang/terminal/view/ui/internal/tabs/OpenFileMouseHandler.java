/*******************************************************************************
 * Copyright (c) 2021 Fabrizio Iannetti.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package io.github.insjang.terminal.view.ui.internal.tabs;

import java.io.File;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.UnknownHostException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.e4.ui.model.application.ui.MElementContainer;
import org.eclipse.e4.ui.model.application.ui.MUIElement;
import org.eclipse.e4.ui.model.application.ui.advanced.MArea;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.model.application.ui.basic.MPartStack;
import org.eclipse.e4.ui.workbench.modeling.EModelService;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.window.Window;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.program.Program;
import io.github.insjang.terminal.connector.Logger;
import io.github.insjang.terminal.control.ITerminalMouseListener;
import io.github.insjang.terminal.control.ITerminalViewControl;
import io.github.insjang.terminal.view.ui.internal.Messages;
import io.github.insjang.terminal.model.ITerminalTextDataReadOnly;
import org.eclipse.ui.IEditorDescriptor;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorRegistry;
import org.eclipse.ui.IPageLayout;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartSite;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.internal.ide.dialogs.OpenResourceDialog;
import org.eclipse.ui.part.ISetSelectionTarget;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;
import org.osgi.framework.Bundle;

/**
 * @noreference This class is not intended to be referenced by clients.
 */
public class OpenFileMouseHandler implements ITerminalMouseListener {
	private static final boolean DEBUG_HOVER = Platform.getDebugBoolean(Logger.TRACE_DEBUG_LOG_HOVER);
	private static final List<String> NEEDED_BUNDLES = //
			List.of("org.eclipse.core.resources", //$NON-NLS-1$
					"org.eclipse.ui.ide", //$NON-NLS-1$
					"org.eclipse.ui.editors", //$NON-NLS-1$
					"org.eclipse.text"); //$NON-NLS-1$

	private final ITerminalViewControl terminal;
	private final Pattern regex = Pattern.compile("(\\d*)(:(\\d*))?.*"); //$NON-NLS-1$
	private static final Pattern SURROUNDING = Pattern.compile("^[(\\[<`]+|[)\\]>`,;.]+$"); //$NON-NLS-1$
	private final IWorkbenchPartSite site;

	/**
	 * Check if we have the bundles needed.
	 */
	private boolean neededBundlesAvailable;

	/** The key of this handler in the data of the terminal's control, where the context menu finds it. */
	static final String KEY = OpenFileMouseHandler.class.getName();

	/** What the last right press was on, for the context menu it opens: taken once by the menu. */
	private String menuLink;
	private String menuWord;
	private boolean menuPending;

	/**
	 * A path as the terminal shows it, resolved as it opens.
	 *
	 * @param text the path without what was around it and without its :line:column
	 * @param location the path on this system: absolute, a WSL path turned into a Windows one
	 * @param lineAndCol the :line:column after the path, without the first colon; null for none
	 */
	record PathTarget(String text, String location, String lineAndCol) {
	}

	OpenFileMouseHandler(IWorkbenchPartSite site, ITerminalViewControl terminal) {
		this.site = site;
		this.terminal = terminal;
		neededBundlesAvailable = true;
		for (String bundleName : NEEDED_BUNDLES) {
			if (!bundleAvailable(bundleName)) {
				this.neededBundlesAvailable = false;
				if (DEBUG_HOVER) {
					System.out.format(
							"hover: the %s bundle is not present, therefore full ctrl-click functionality is not available\n", //$NON-NLS-1$
							bundleName);
				}
			}
		}
		if (neededBundlesAvailable && DEBUG_HOVER) {
			System.out.format("hover: the bundles needed for full ctrl-click functionality are available\n"); //$NON-NLS-1$
		}
	}

	@Override
	public void mouseUp(ITerminalTextDataReadOnly terminalText, int line, int column, int button, int stateMask) {
		if ((stateMask & SWT.MODIFIER_MASK) != SWT.MOD1) {
			// Only handle Ctrl-click
			return;
		}
		open(terminalText.getLink(line, column), terminal.getHoverSelection());
	}

	@Override
	public void mouseDown(ITerminalTextDataReadOnly terminalText, int line, int column, int button, int stateMask) {
		if (button == 3) {
			// what the context menu this press opens is about
			menuLink = terminalText.getLink(line, column);
			menuWord = terminal.getWordAt(line, column);
			menuPending = true;
		}
	}

	/**
	 * Opens what a Ctrl+click or the context menu's Open is on: a web URL in the browser, a file in an
	 * editor, a folder in the Project Explorer (or the system's file manager outside the workspace).
	 *
	 * @param link the hyperlink a program put there (OSC 8), which wins over the word there; null for none
	 * @param hover the word there, as the hover finds it; null for none
	 */
	@SuppressWarnings("restriction")
	private void open(String link, String hover) {
		// a URL with an icon glued to its front, as Claude Code prints them, opens from its scheme
		String textToOpen = link != null ? LinkPolicy.target(link, localHostName())
				: hover != null ? LinkPolicy.fromScheme(hover) : null;
		// a link whose text says something else is shown before it is opened, file links too
		if (link != null && textToOpen != null && LinkPolicy.confirm(link, textToOpen, hover)
				&& !MessageDialog.openQuestion(site.getShell(), Messages.OpenLink_title,
						NLS.bind(Messages.OpenLink_question, LinkPolicy.display(textToOpen)))) {
			return;
		}
		if (textToOpen != null && textToOpen.length() > 0) {
			try {
				// if the selection looks like a web URL, open using the browser
				if (LinkPolicy.isWeb(textToOpen)) {
					try {
						PlatformUI.getWorkbench().getBrowserSupport().createBrowser(null).openURL(new URL(textToOpen));
						return;
					} catch (MalformedURLException e) {
						// not a valid URL, continue
					}
				}

				// After this we need Eclipse IDE features. If we don't have them then we stop here.
				if (!neededBundlesAvailable) {
					return;
				}
				PathTarget target = resolvePath(textToOpen, link != null);
				textToOpen = target.text();
				String location = target.location();
				String lineAndCol = target.lineAndCol();
				if (isFolder(location)) {
					showFolder(location);
					return;
				}
				selectEditorStack();
				IFile fileForLocation = ResourcesPlugin.getWorkspace().getRoot()
						.getFileForLocation(new Path(location));
				if (fileForLocation != null && fileForLocation.exists()) {
					String id = editorId(IDE.getEditorDescriptor(fileForLocation, true, true), location);
					if (id == null) {
						return;
					}
					IEditorPart editor = IDE.openEditor(
							PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage(), fileForLocation, id,
							true);
					goToLine(lineAndCol, editor);
					return;
				}
				// try an external file, if it exists
				File file = new File(location);
				if (!isNetworkPath(location) && file.exists() && !file.isDirectory()) {
					try {
						String id = editorId(IDE.getEditorDescriptor(file.getName(), true, true), location);
						if (id == null) {
							return;
						}
						IEditorPart editor = IDE.openEditor(site.getPage(), file.toURI(), id, true);
						goToLine(lineAndCol, editor);
						return;
					} catch (Exception e) {
						// continue
					}
				}
				ResourcesPlugin.getPlugin();
				OpenResourceDialog openResourceDialog = new OpenResourceDialog(site.getShell(),
						ResourcesPlugin.getWorkspace().getRoot(), IResource.FILE);
				// the dialog matches resource names, so a path in it finds nothing
				openResourceDialog.setInitialPattern(LinkPolicy.fileName(textToOpen));
				if (openResourceDialog.open() != Window.OK) {
					return;
				}
				Object[] results = openResourceDialog.getResult();
				List<IFile> files = new ArrayList<>();
				for (Object result : results) {
					if (result instanceof IFile) {
						files.add((IFile) result);
					}
				}
				if (files.size() > 0) {

					final IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
					if (window == null) {
						throw new ExecutionException("no active workbench window"); //$NON-NLS-1$
					}

					final IWorkbenchPage page = window.getActivePage();
					if (page == null) {
						throw new ExecutionException("no active workbench page"); //$NON-NLS-1$
					}

					try {
						for (IFile iFile : files) {
							String id = editorId(IDE.getEditorDescriptor(iFile, true, true),
									iFile.getLocation() != null ? iFile.getLocation().toOSString() : iFile.getName());
							if (id == null) {
								return;
							}
							IEditorPart editor = IDE.openEditor(page, iFile, id, true);
							goToLine(lineAndCol, editor);
						}
					} catch (final PartInitException e) {
						throw new ExecutionException("error opening file in editor", e); //$NON-NLS-1$
					}
				}
			} catch (IllegalArgumentException | NullPointerException | ExecutionException | PartInitException e) {
				ILog.of(getClass()).error("Failed to activate OpenResourceDialog", e); //$NON-NLS-1$
			}

		}

	}

	/**
	 * @param textToOpen the path as Ctrl+click took it, or the local path of a file link
	 * @param fromLink whether it came from a hyperlink (OSC 8), which names the file exactly
	 */
	private PathTarget resolvePath(String textToOpen, boolean fromLink) {
		String lineAndCol = null;
		if (!fromLink) {
			// extract the path from file:// URLs
			if (textToOpen.startsWith("file://")) { //$NON-NLS-1$
				textToOpen = textToOpen.substring(7);
			}
			// a path in a sentence comes with what is around it: (a.txt), `a.txt`, a.txt,
			textToOpen = SURROUNDING.matcher(textToOpen).replaceAll(""); //$NON-NLS-1$
			// remove optional position info name:[row[:col]]
			int startOfRowCol = textToOpen.indexOf(':');
			if (startOfRowCol == 1 && textToOpen.length() > 2) {
				// assume this is the device separator on Windows
				startOfRowCol = textToOpen.indexOf(':', startOfRowCol + 1);
			}
			if (startOfRowCol >= 0) {
				lineAndCol = textToOpen.substring(startOfRowCol + 1);
				textToOpen = textToOpen.substring(0, startOfRowCol);
			}
		}
		String full = textToOpen;
		if (!textToOpen.startsWith("/") && !new File(textToOpen).isAbsolute()) { //$NON-NLS-1$
			// relative path: try to append to the working directory
			Optional<String> workingDirectory = terminal.getTerminalConnector().getWorkingDirectory();
			if (workingDirectory.isPresent()) {
				full = workingDirectory.get() + "/" + textToOpen; //$NON-NLS-1$
			}
		}
		return new PathTarget(textToOpen, toWindowsPath(full), lineAndCol);
	}

	private static boolean isFolder(String location) {
		return !isNetworkPath(location) && new File(location).isDirectory();
	}

	/** A folder in the workspace is shown in the Project Explorer, any other in the system's file manager. */
	private void showFolder(String location) {
		IResource resource = workspaceResource(location);
		if (resource != null) {
			showInProjectExplorer(resource);
		} else {
			openInSystemExplorer(location);
		}
	}

	/**
	 * @return the file or folder of the workspace at the location, refreshed from the disk when the
	 *         workspace does not know it yet (a program just made it); null outside the workspace
	 */
	private IResource workspaceResource(String location) {
		if (!neededBundlesAvailable || isNetworkPath(location)) {
			return null;
		}
		IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
		Path path = new Path(location);
		IResource resource = new File(location).isDirectory() ? root.getContainerForLocation(path)
				: root.getFileForLocation(path);
		if (resource == null || resource.getType() == IResource.ROOT) {
			return null;
		}
		if (!resource.exists() && resource.getParent() != null) {
			try {
				resource.getParent().refreshLocal(IResource.DEPTH_ONE, null);
			} catch (CoreException e) {
				// as it is
			}
		}
		return resource.exists() ? resource : null;
	}

	private void showInProjectExplorer(IResource resource) {
		try {
			IViewPart view = site.getPage().showView(IPageLayout.ID_PROJECT_EXPLORER);
			if (view instanceof ISetSelectionTarget target) {
				target.selectReveal(new StructuredSelection(resource));
			}
		} catch (PartInitException e) {
			ILog.of(getClass()).error("Failed to show " + resource.getFullPath() + " in the Project Explorer", e); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	/** Opens the folder, or the folder of the file, in the system's file manager. */
	private static void openInSystemExplorer(String location) {
		File file = new File(location);
		File folder = file.isDirectory() ? file : file.getParentFile();
		if (folder != null && !isNetworkPath(folder.getPath()) && folder.isDirectory()) {
			Program.launch(folder.getPath());
		}
	}

	private void copy(String text) {
		Clipboard clipboard = new Clipboard(site.getShell().getDisplay());
		try {
			clipboard.setContents(new Object[] { text }, new Transfer[] { TextTransfer.getInstance() });
		} finally {
			clipboard.dispose();
		}
	}

	/**
	 * Puts what the context menu can do with the path or link the right-click was on into the group:
	 * nothing when it was on neither, or when the menu came from the keyboard.
	 */
	void fillMenu(IMenuManager menu, String group) {
		String link = menuLink;
		String word = menuWord;
		boolean pending = menuPending;
		menuLink = null;
		menuWord = null;
		menuPending = false;
		if (!pending) {
			return;
		}
		String text = link != null ? LinkPolicy.target(link, localHostName())
				: word != null ? LinkPolicy.fromScheme(word) : null;
		if (text == null || text.isEmpty()) {
			return;
		}
		if (LinkPolicy.isWeb(text)) {
			menu.appendToGroup(group, action(Messages.PathMenu_openLink, () -> open(link, word)));
			menu.appendToGroup(group, action(Messages.PathMenu_copyLink, () -> copy(text)));
			return;
		}
		PathTarget target = resolvePath(text, link != null);
		String location = target.location();
		File file = new File(location);
		if (isNetworkPath(location) || !file.exists()) {
			return; // a word that names no file
		}
		IResource resource = workspaceResource(location);
		if (!file.isDirectory()) {
			menu.appendToGroup(group, action(Messages.PathMenu_open, () -> open(link, word)));
			String name = file.getName().toLowerCase(Locale.ROOT);
			if (name.endsWith(".html") || name.endsWith(".htm")) { //$NON-NLS-1$ //$NON-NLS-2$
				menu.appendToGroup(group, action(Messages.PathMenu_openInBrowser, () -> openInBrowser(file)));
			}
		}
		menu.appendToGroup(group, action(Messages.PathMenu_copyPath, () -> copy(file.getPath())));
		if (resource != null) {
			menu.appendToGroup(group, action(Messages.PathMenu_showInProjectExplorer,
					() -> showInProjectExplorer(resource)));
		}
		menu.appendToGroup(group, action(Messages.PathMenu_openInSystemExplorer,
				() -> openInSystemExplorer(location)));
	}

	private void openInBrowser(File file) {
		try {
			PlatformUI.getWorkbench().getBrowserSupport().getExternalBrowser().openURL(file.toURI().toURL());
		} catch (PartInitException | MalformedURLException e) {
			ILog.of(getClass()).error("Failed to open " + file + " in the web browser", e); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	private static IAction action(String label, Runnable run) {
		return new Action(label) {
			@Override
			public void run() {
				run.run();
			}
		};
	}

	/**
	 * A UNC path other than WSL's: probing it would hand the Windows credentials to whatever host the terminal
	 * output named.
	 */
	static boolean isNetworkPath(String path) {
		String p = path.replace('\\', '/').toLowerCase(Locale.ROOT);
		return File.separatorChar == '\\' && p.startsWith("//") && !p.startsWith("//wsl.localhost/") //$NON-NLS-1$ //$NON-NLS-2$
				&& !p.startsWith("//wsl$/"); //$NON-NLS-1$
	}

	/**
	 * Terminal output picks the file: an editor inside Eclipse, the system program only for
	 * a PDF or an image and only after asking, never anything else outside Eclipse or OLE.
	 *
	 * @return the editor id, or null when the user said no
	 */
	private String editorId(IEditorDescriptor d, String path) {
		String id = LinkPolicy.editorId(d != null ? d.getId() : null, d != null && d.isInternal(),
				d != null && d.isOpenInPlace());
		if (!LinkPolicy.TEXT_EDITOR_ID.equals(id) || d != null && d.isInternal()) {
			return id; // an editor inside Eclipse
		}
		// what the user chose in the File Associations preferences, for the real file
		IEditorRegistry registry = PlatformUI.getWorkbench().getEditorRegistry();
		String target = LinkPolicy.systemProgramTarget(path, Platform.OS_WIN32.equals(Platform.getOS()),
				name -> isOutside(registry.getDefaultEditor(name)));
		if (target == null) {
			return id;
		}
		// the real file, what a link leads to: the one checked is the one launched
		String question = LinkPolicy.macroCapable(target) ? Messages.OpenSystem_macros : Messages.OpenSystem_question;
		if (MessageDialog.openQuestion(site.getShell(), Messages.OpenSystem_title,
				NLS.bind(question, LinkPolicy.display(target)))) {
			IEditorDescriptor chosen = registry.getDefaultEditor(new File(target).getName());
			if (isOutside(chosen) && !LinkPolicy.SYSTEM_EDITOR_ID.equals(chosen.getId())) {
				try {
					// the program the user named for this kind of file
					IDE.openEditor(site.getPage(), new File(target).toURI(), chosen.getId(), true);
				} catch (PartInitException e) {
					ILog.of(getClass()).error("Failed to open " + target, e); //$NON-NLS-1$
				}
			} else {
				Program.launch(target);
			}
		}
		return null;
	}

	/** @return whether the editor is a program outside Eclipse: the system's or one the user named */
	private static boolean isOutside(IEditorDescriptor d) {
		return d != null && (d.isOpenExternal() || LinkPolicy.SYSTEM_EDITOR_ID.equals(d.getId()));
	}

	private static String localHostName() {
		try {
			return InetAddress.getLocalHost().getHostName();
		} catch (UnknownHostException e) {
			return ""; //$NON-NLS-1$
		}
	}

	/**
	 * A path as a WSL shell prints it, turned into one Windows can open: /mnt/d/a is D:\a, and the rest
	 * of the Linux file system is under \\wsl.localhost\&lt;distribution&gt;. Anything else, and
	 * anything on another platform, is left as it is.
	 */
	private static String toWindowsPath(String path) {
		if (File.separatorChar != '\\' || !path.startsWith("/")) { //$NON-NLS-1$
			return path;
		}
		String drive = ShellPaths.toWindows(path, ""); //$NON-NLS-1$
		if (drive != null) {
			return drive;
		}
		for (String distribution : ShellPaths.wslDistributions()) {
			File file = new File(ShellPaths.toWindows(path, distribution));
			if (file.exists()) {
				return file.getPath();
			}
		}
		return path;
	}

	private boolean bundleAvailable(String symbolicName) {
		Bundle bundle = Platform.getBundle(symbolicName);
		boolean available = bundle != null && bundle.getState() != Bundle.UNINSTALLED
				&& bundle.getState() != Bundle.STOPPING;
		return available;
	}

	private void goToLine(String lineAndCol, IEditorPart editor) {
		ITextEditor textEditor = Adapters.adapt(editor, ITextEditor.class);
		if (textEditor != null) {
			Optional<Integer> optionalOffset = getRegionFromLineAndCol(textEditor, lineAndCol);
			optionalOffset.ifPresent(offset -> textEditor.selectAndReveal(offset, 0));
		}
	}

	/**
	 * Returns the line information for the given line in the given editor
	 */
	private Optional<Integer> getRegionFromLineAndCol(ITextEditor editor, String lineAndCol) {
		if (lineAndCol == null) {
			return Optional.empty();
		}
		Matcher matcher = regex.matcher(lineAndCol);
		if (!matcher.matches()) {
			return Optional.empty();
		}
		String lineStr = matcher.group(1);
		String colStr = matcher.group(3);
		int line;
		int col = 0;
		try {
			line = Integer.parseInt(lineStr);
		} catch (NumberFormatException e1) {
			return Optional.empty();
		}
		try {
			col = Integer.parseInt(colStr);
		} catch (NumberFormatException e1) {
			// if we can't get a column, go to the line alone
		}
		IDocumentProvider provider = editor.getDocumentProvider();
		IEditorInput input = editor.getEditorInput();
		try {
			provider.connect(input);
		} catch (CoreException e) {
			return null;
		}
		try {
			IDocument document = provider.getDocument(input);
			if (document != null && line > 0) {
				// document's lines are 0-offset
				line = line - 1;
				int lineOffset = document.getLineOffset(line);
				if (col > 0) {
					int lineLength = document.getLineLength(line);
					if (col < lineLength) {
						lineOffset += col;
					}
				}
				return Optional.of(lineOffset);
			}
		} catch (BadLocationException e) {
		} finally {
			provider.disconnect(input);
		}
		return Optional.empty();
	}

	/**
	 * An editor opens in the selected stack of the editor area. With the terminal view docked in the
	 * editor area that is the terminal's own stack, so the file would cover the terminal: select the
	 * stack of the last active editor, or else another editor stack, before opening.
	 */
	private void selectEditorStack() {
		MPart part = site.getService(MPart.class);
		EModelService models = site.getService(EModelService.class);
		if (part == null || models == null) {
			return;
		}
		MUIElement terminalStack = stackOf(part);
		MArea area = areaOf(terminalStack);
		if (area == null) {
			return; // a view outside the editor area: editors open elsewhere anyway
		}
		MPartStack target = null;
		IEditorPart last = site.getPage().getActiveEditor();
		if (last != null && stackOf(last.getSite().getService(MPart.class)) instanceof MPartStack stack
				&& isOtherEditorStack(stack, terminalStack, area, models)) {
			target = stack;
		}
		if (target == null) {
			List<MPartStack> stacks = new ArrayList<>(
					models.findElements(area, null, MPartStack.class, List.of("org.eclipse.e4.primaryDataStack"))); //$NON-NLS-1$
			stacks.addAll(models.findElements(area, null, MPartStack.class, List.of("EditorStack"))); //$NON-NLS-1$
			target = stacks.stream().filter(s -> isOtherEditorStack(s, terminalStack, area, models)).findFirst()
					.orElse(null);
		}
		if (target == null) {
			return; // the terminal's stack is the only one
		}
		MUIElement child = target;
		for (MElementContainer<MUIElement> parent = child.getParent(); parent != null; parent = parent.getParent()) {
			parent.setSelectedElement(child);
			if ((Object) parent == area) {
				break;
			}
			child = parent;
		}
	}

	private static MArea areaOf(MUIElement element) {
		for (MUIElement e = element; e != null; e = e.getParent()) {
			if (e instanceof MArea area) {
				return area;
			}
		}
		return null;
	}

	private static MUIElement stackOf(MPart part) {
		if (part == null) {
			return null;
		}
		MUIElement ref = part.getCurSharedRef() != null ? part.getCurSharedRef() : part;
		return ref.getParent();
	}

	private static boolean isOtherEditorStack(MPartStack stack, MUIElement terminalStack, MArea area,
			EModelService models) {
		return stack != terminalStack && stack.isToBeRendered() && stack.isVisible()
				&& models.findElements(area, null, MPartStack.class).contains(stack);
	}
}
