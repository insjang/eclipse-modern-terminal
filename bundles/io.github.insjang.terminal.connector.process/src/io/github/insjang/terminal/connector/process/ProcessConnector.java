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
 * Kaloyan Raev - Bug 485658 - NPE prevents displaying the actual error
 *******************************************************************************/
package io.github.insjang.terminal.connector.process;

import java.io.File;
import java.io.IOException;
import java.io.StreamTokenizer;
import java.io.StringReader;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.eclipse.cdt.utils.pty.PTY;
import org.eclipse.cdt.utils.spawner.ProcessFactory;
import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Widget;
import io.github.insjang.terminal.connector.ISettingsStore;
import io.github.insjang.terminal.connector.ITerminalControl;
import io.github.insjang.terminal.connector.NullSettingsStore;
import io.github.insjang.terminal.connector.TerminalState;
import io.github.insjang.terminal.connector.process.internal.ConsoleProcess;
import io.github.insjang.terminal.connector.process.internal.Env;
import io.github.insjang.terminal.connector.process.internal.Messages;
import io.github.insjang.terminal.connector.process.internal.ProcessDestroyer;
import io.github.insjang.terminal.connector.process.internal.ProcessMonitor;
import io.github.insjang.terminal.connector.process.internal.UIPlugin;
import io.github.insjang.terminal.view.core.ILineSeparatorConstants;
import io.github.insjang.terminal.view.ui.streams.AbstractStreamsConnector;

/**
 * Process connector implementation.
 */
public class ProcessConnector extends AbstractStreamsConnector {
	// Reference to the process settings
	private final ProcessSettings settings;

	// Reference to the PTY instance.
	private PTY pty;
	// the program in the console Windows Terminal ships, instead of a PTY of CDT's
	private ConsoleProcess console;
	// Reference to the launched process instance.
	private Process process;
	/** the exit code of a process that ended by itself */
	private volatile OptionalInt exitCode = OptionalInt.empty();
	// Reference to the process monitor
	private ProcessMonitor monitor;

	// The terminal width and height. Initially unknown.
	private int width = -1;
	private int height = -1;

	/**
	 * Constructor.
	 */
	public ProcessConnector() {
		this(new ProcessSettings());
	}

	/**
	 * Constructor.
	 *
	 * @param settings The process settings. Must not be <code>null</code>
	 */
	public ProcessConnector(ProcessSettings settings) {
		super();

		Assert.isNotNull(settings);
		this.settings = settings;
	}

	/**
	 * Returns the process object or <code>null</code> if the
	 * connector is connector.
	 *
	 * @return The process object or <code>null</code>.
	 */
	public Process getProcess() {
		return process;
	}

	@Override
	public void connect(ITerminalControl control) {
		Assert.isNotNull(control);
		super.connect(control);

		pty = null;
		console = null;
		width = -1;
		height = -1;

		try {
			boolean isAnsiTerminal = false;

			// Try to determine process and PTY instance from the process settings
			process = settings.getProcess();
			pty = settings.getPTY();

			// No process -> create PTY on supported platforms and execute
			// process image.
			if (process == null) {
				// on Windows the console Windows Terminal ships, which counts character widths
				// as the terminal does, where it is there; else a PTY of CDT's
				boolean ownConsole = ConsoleProcess.isAvailable();
				if (ownConsole) {
					control.updateTerminalDimensions(); // the size the console starts with
				} else if (PTY.isSupported(PTY.Mode.TERMINAL)) {
					try {
						pty = new PTY(PTY.Mode.TERMINAL);
						control.updateTerminalDimensions();
						// Initialize the terminal size
					} catch (IOException e) {
						// PTY not supported
					}
				}

				// Build up the command
				StringBuilder command = new StringBuilder(settings.getImage());
				String arguments = settings.getArguments();
				if (arguments != null && !"".equals(arguments.trim())) { //$NON-NLS-1$
					// Append to the command now
					command.append(" "); //$NON-NLS-1$
					command.append(arguments.trim());
				}

				File workingDir = null;
				if (settings.getWorkingDir() != null) {
					workingDir = new File(settings.getWorkingDir());
				}

				String[] envp = null;
				if (settings.getEnvironment() != null) {
					envp = settings.getEnvironment();
				}

				if (settings.isMergeWithNativeEnvironment()) {
					envp = Env.getEnvironment(envp, true);
				}

				isAnsiTerminal = getTermVariable(envp).startsWith("ansi"); //$NON-NLS-1$

				if (pty != null || ownConsole) {
					// A PTY is available -> can use the ProcessFactory.

					// Tokenize the command (ProcessFactory takes an array)
					StreamTokenizer st = new StreamTokenizer(new StringReader(command.toString()));
					st.resetSyntax();
					st.whitespaceChars(0, 32);
					st.whitespaceChars(0xa0, 0xa0);
					st.wordChars(33, 255);
					st.quoteChar('"');
					st.quoteChar('\'');

					List<String> argv = new ArrayList<>();
					int ttype = st.nextToken();
					while (ttype != StreamTokenizer.TT_EOF) {
						argv.add(st.sval);
						ttype = st.nextToken();
					}

					if (ownConsole) {
						try {
							process = console = ConsoleProcess.start(argv, envp, workingDir, width > 0 ? width : 80,
									height > 0 ? height : 24, glyphWidth());
						} catch (IOException e) {
							// e.g. a policy that does not let OpenConsole.exe run from the workspace:
							// the console of Windows then, as before
							UIPlugin.log("The console of Windows Terminal did not start; the one of Windows is used", e); //$NON-NLS-1$
							pty = new PTY(PTY.Mode.TERMINAL);
						}
					}
					if (process == null) {
						// Execute the process
						process = ProcessFactory.getFactory().exec(argv.toArray(new String[argv.size()]), envp,
								workingDir, pty);
					}
				} else {
					// No PTY -> just execute via the standard Java Runtime implementation.
					process = Runtime.getRuntime().exec(command.toString(), envp, workingDir);
				}
			}

			String lineSeparator = settings.getLineSeparator();
			boolean terminal = pty != null || console != null;
			if (lineSeparator == null && !terminal) {
				lineSeparator = System.getProperty("line.separator"); //$NON-NLS-1$
				if ("\r".equals(lineSeparator)) { //$NON-NLS-1$
					lineSeparator = ILineSeparatorConstants.LINE_SEPARATOR_CR;
				} else if ("\n".equals(lineSeparator)) { //$NON-NLS-1$
					lineSeparator = ILineSeparatorConstants.LINE_SEPARATOR_LF;
				} else {
					lineSeparator = ILineSeparatorConstants.LINE_SEPARATOR_CRLF;
				}
			}

			// Setup the listeners
			setStdoutListeners(settings.getStdOutListeners());
			setStderrListeners(settings.getStdErrListeners());

			// Enable VT100 line wrapping if we are connected via pty
			// And TERM is VT100 compatible
			if (terminal && !isAnsiTerminal) {
				control.setVT100LineWrapping(true);
			}

			// connect the streams
			connectStreams(control, process.getOutputStream(), process.getInputStream(),
					(terminal ? null : process.getErrorStream()), settings.isLocalEcho(), lineSeparator);

			// Set the terminal control state to CONNECTED
			control.setState(TerminalState.CONNECTED);

			// Create the process monitor
			monitor = new ProcessMonitor(this);
			monitor.startMonitoring();
		} catch (IOException e) {
			// Disconnect right away
			disconnect();
			// Save the shell so the error message can have somewhere to display
			Shell shell = control.getShell();
			// Lookup the tab item
			getConsoleViewManager().findConsole(control).ifPresent(Widget::dispose);
			// Get the error message from the exception
			String msg = e.getLocalizedMessage() != null ? e.getLocalizedMessage() : ""; //$NON-NLS-1$
			Assert.isNotNull(msg);
			// Strip away "Exec_tty error:"
			msg = msg.replace("Exec_tty error:", "").trim(); //$NON-NLS-1$ //$NON-NLS-2$
			// Repackage into a more user friendly error
			msg = NLS.bind(Messages.ProcessConnector_error_creatingProcess, settings.getImage(), msg);
			// Open an error dialog
			MessageDialog.openError(shell, Messages.ProcessConnector_error_title, msg);
		}
	}

	/**
	 * @return how the console counts character widths: as the terminal does, by the
	 *         Character width preference
	 */
	private static int glyphWidth() {
		boolean codePoints = Platform.getPreferencesService().getBoolean("io.github.insjang.terminal.control", //$NON-NLS-1$
				"TerminalPrefLegacyWidth", false, null); //$NON-NLS-1$
		return codePoints ? ConsoleProcess.WCSWIDTH : ConsoleProcess.GRAPHEMES;
	}

	private static String getTermVariable(String[] envp) {
		if (envp != null && !Platform.OS_WIN32.equals(Platform.getOS())) {
			for (String var : envp) {
				if (var.startsWith("TERM=")) { //$NON-NLS-1$
					return var.substring(5);
				}
			}
		}
		return "xterm"; //$NON-NLS-1$
	}

	@Override
	public boolean isLocalEcho() {
		return settings.isLocalEcho();
	}

	@Override
	public void doDisconnect() {
		// a process that ended by itself: a failure shows its code, as Windows Terminal does, and keeps the tab
		if (process != null && !process.isAlive()) {
			exitCode = OptionalInt.of(process.exitValue());
			if (exitCode.getAsInt() != 0) {
				fControl.displayTextInTerminal("[process exited with code " + exitCode.getAsInt() + "]"); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}

		// Stop monitoring the process
		if (monitor != null) {
			monitor.dispose();
		}

		boolean isWindows = Platform.OS_WIN32.equals(Platform.getOS());

		if (!isWindows) {
			// Start destroying the process first, except on windows (Bug 465674)
			if (process != null) {
				ProcessDestroyer.destroy(process);
				process = null;
			}
		}

		// Dispose the streams
		super.doDisconnect();

		if (isWindows) {
			// On Windows destroy the process after closing streams
			if (process != null) {
				ProcessDestroyer.destroy(process);
				process = null;
			}
		}

		// Set the terminal control state to CLOSED.
		fControl.setState(TerminalState.CLOSED);
	}

	@Override
	public OptionalInt getExitCode() {
		return exitCode;
	}

	@Override
	public void setDefaultSettings() {
		settings.load(new NullSettingsStore());
	}

	@Override
	public String getSettingsSummary() {
		return settings.getImage() != null ? settings.getImage() : ""; //$NON-NLS-1$
	}

	@Override
	public void load(ISettingsStore store) {
		settings.load(store);
	}

	@Override
	public void save(ISettingsStore store) {
		settings.save(store);
	}

	@Override
	public void setTerminalSize(int newWidth, int newHeight) {
		if (width != newWidth || height != newHeight) {
			width = newWidth;
			height = newHeight;
			if (pty != null) {
				pty.setTerminalSize(newWidth, newHeight);
			} else if (console != null) {
				console.resize(newWidth, newHeight);
			}
		}
	}

	/**
	 * @since 4.8
	 */
	@Override
	public Optional<String> getWorkingDirectory() {
		try {
			long pid = process.pid();
			try {
				if (Platform.getOS().equals(Platform.OS_LINUX)) {
					Path procCwd = Files.readSymbolicLink(FileSystems.getDefault().getPath("/proc/" + pid + "/cwd")); //$NON-NLS-1$//$NON-NLS-2$
					return Optional.of(procCwd.toAbsolutePath().toString());
				}
			} catch (Exception e) {
				UIPlugin.log("Failed to obtain working directory of process id " + pid, e); //$NON-NLS-1$
			}
		} catch (Exception e) {
			UIPlugin.log("Failed to obtain process id of terminal process", e); //$NON-NLS-1$
		}
		return Optional.empty();
	}
}
