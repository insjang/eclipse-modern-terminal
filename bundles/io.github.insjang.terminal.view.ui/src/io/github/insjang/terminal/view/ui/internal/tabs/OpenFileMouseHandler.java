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
import java.net.MalformedURLException;
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
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import io.github.insjang.terminal.connector.Logger;
import io.github.insjang.terminal.control.ITerminalMouseListener;
import io.github.insjang.terminal.control.ITerminalViewControl;
import io.github.insjang.terminal.model.ITerminalTextDataReadOnly;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartSite;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.internal.ide.dialogs.OpenResourceDialog;
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

	@SuppressWarnings("restriction")
	@Override
	public void mouseUp(ITerminalTextDataReadOnly terminalText, int line, int column, int button, int stateMask) {
		if ((stateMask & SWT.MODIFIER_MASK) != SWT.MOD1) {
			// Only handle Ctrl-click
			return;
		}
		String textToOpen = terminal.getHoverSelection();
		String lineAndCol = null;
		if (textToOpen.length() > 0) {
			try {
				// if the selection looks like a web URL, open using the browser
				if (textToOpen.startsWith("http://") || textToOpen.startsWith("https://")) { //$NON-NLS-1$//$NON-NLS-2$
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
				selectEditorStack();

				// extract the path from file:// URLs
				if (textToOpen.startsWith("file://")) { //$NON-NLS-1$
					textToOpen = textToOpen.substring(7);
				}
				// a path in a sentence comes with what is around it: (a.txt), `a.txt`, a.txt,
				textToOpen = SURROUNDING.matcher(textToOpen).replaceAll(""); //$NON-NLS-1$
				// remove optional position info name:[row[:col]]
				{
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
				Optional<String> fullPath = Optional.empty();
				if (!textToOpen.startsWith("/") && !new File(textToOpen).isAbsolute()) { //$NON-NLS-1$
					// relative path: try to append to the working directory
					Optional<String> workingDirectory = terminal.getTerminalConnector().getWorkingDirectory();
					if (workingDirectory.isPresent()) {
						fullPath = Optional.of(workingDirectory.get() + "/" + textToOpen); //$NON-NLS-1$
					}
				}
				// if the selection is a file location that maps to a resource
				// open the resource
				String location = toWindowsPath(fullPath.orElse(textToOpen));
				IFile fileForLocation = ResourcesPlugin.getWorkspace().getRoot()
						.getFileForLocation(new Path(location));
				if (fileForLocation != null && fileForLocation.exists()) {
					IEditorPart editor = IDE.openEditor(
							PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage(), fileForLocation,
							true);
					goToLine(lineAndCol, editor);
					return;
				}
				// try an external file, if it exists
				File file = new File(location);
				if (!isNetworkPath(location) && file.exists() && !file.isDirectory()) {
					try {
						IEditorPart editor = IDE.openEditor(site.getPage(), file.toURI(),
								IDE.getEditorDescriptor(file.getName(), true, true).getId(), true);
						goToLine(lineAndCol, editor);
						return;
					} catch (Exception e) {
						// continue
					}
				}
				ResourcesPlugin.getPlugin();
				OpenResourceDialog openResourceDialog = new OpenResourceDialog(site.getShell(),
						ResourcesPlugin.getWorkspace().getRoot(), IResource.FILE);
				openResourceDialog.setInitialPattern(textToOpen);
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
							IEditorPart editor = IDE.openEditor(page, iFile, true);
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
	 * A UNC path other than WSL's: probing it would hand the Windows credentials to whatever host the terminal
	 * output named.
	 */
	static boolean isNetworkPath(String path) {
		String p = path.replace('\\', '/').toLowerCase(Locale.ROOT);
		return File.separatorChar == '\\' && p.startsWith("//") && !p.startsWith("//wsl.localhost/") //$NON-NLS-1$ //$NON-NLS-2$
				&& !p.startsWith("//wsl$/"); //$NON-NLS-1$
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
