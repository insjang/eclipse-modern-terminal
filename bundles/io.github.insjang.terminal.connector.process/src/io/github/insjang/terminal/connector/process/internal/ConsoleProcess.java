/*******************************************************************************
 * Copyright (c) 2026 insjang and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package io.github.insjang.terminal.connector.process.internal;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinNT.HANDLEByReference;
import com.sun.jna.ptr.IntByReference;

/**
 * A process in a console of the ConPTY that Windows Terminal ships
 * (OpenConsole.exe with conpty.dll, Microsoft's MIT licensed package
 * Microsoft.Windows.Console.ConPTY), not the one Windows has. The console
 * sits between the program and the terminal, and lays out what the program
 * writes before the terminal sees it; this one can be told how wide a
 * character is (grapheme clusters, or code points as shells count them),
 * the one Windows has counts its own way and agrees with neither, so text
 * edited at a prompt with emoji in it lands in the wrong cells. Windows only.
 */
public final class ConsoleProcess extends Process {

	/** glyph widths of the console, as conpty.h names them */
	public static final int GRAPHEMES = 0x08, WCSWIDTH = 0x10;

	private static final int PSEUDOCONSOLE_ATTRIBUTE = 0x00020016;
	private static final int EXTENDED_STARTUPINFO_PRESENT = 0x00080000;
	private static final int CREATE_UNICODE_ENVIRONMENT = 0x00000400;
	private static final int STARTF_USESTDHANDLES = 0x00000100;
	private static final int STILL_ACTIVE = 259;
	private static final int WAIT_TIMEOUT = 0x102;

	public interface ConPty extends Library {
		int ConptyCreatePseudoConsole(int size, HANDLE input, HANDLE output, int flags, HANDLEByReference console);

		int ConptyResizePseudoConsole(HANDLE console, int size);

		void ConptyClosePseudoConsole(HANDLE console);
	}

	public interface Kernel32Ex extends Library {
		boolean InitializeProcThreadAttributeList(Pointer list, int count, int flags, LongByReference size);

		boolean UpdateProcThreadAttribute(Pointer list, int flags, Pointer attribute, Pointer value, Pointer size,
				Pointer previous, Pointer returned);

		void DeleteProcThreadAttributeList(Pointer list);

		boolean CreateProcessW(WString application, char[] commandLine, Pointer processAttributes,
				Pointer threadAttributes, boolean inheritHandles, int flags, Pointer environment, WString directory,
				StartupInfoEx startupInfo, WinBase.PROCESS_INFORMATION processInformation);
	}

	@Structure.FieldOrder({ "StartupInfo", "lpAttributeList" })
	public static class StartupInfoEx extends Structure {
		public WinBase.STARTUPINFO StartupInfo = new WinBase.STARTUPINFO();
		public Pointer lpAttributeList;
	}

	private static ConPty conpty;
	private static boolean looked;

	private final HANDLE console;
	private final HANDLE process;
	private final HANDLE input;
	private final HANDLE output;
	private final int pid;
	private boolean closed;

	/**
	 * @return the console Windows Terminal ships, or null where there is none: not
	 *         Windows, an architecture without it, or it would not load
	 */
	private static synchronized ConPty conpty() {
		if (!looked) {
			looked = true;
			try {
				if (Platform.OS_WIN32.equals(Platform.getOS())) {
					File dll = install();
					if (dll != null) {
						conpty = Native.load(dll.getAbsolutePath(), ConPty.class);
					}
				}
			} catch (Throwable e) {
				UIPlugin.log("The console of Windows Terminal could not be loaded; the one of Windows is used", e); //$NON-NLS-1$
				conpty = null;
			}
		}
		return conpty;
	}

	/** @return whether processes can run in the console Windows Terminal ships */
	public static boolean isAvailable() {
		return conpty() != null;
	}

	/**
	 * Copies conpty.dll and OpenConsole.exe out of the bundle, side by side as the
	 * dll looks for the console next to itself, once per bundle version.
	 */
	private static File install() throws IOException {
		Bundle bundle = FrameworkUtil.getBundle(ConsoleProcess.class);
		String arch = Platform.getOSArch();
		String folder = "os/win32/" + arch + "/"; //$NON-NLS-1$ //$NON-NLS-2$
		if (bundle == null || bundle.getEntry(folder + "conpty.dll") == null) { //$NON-NLS-1$
			return null;
		}
		Path target = Platform.getStateLocation(bundle).append("conpty").append(bundle.getVersion().toString()) //$NON-NLS-1$
				.append(arch).toFile().toPath();
		Files.createDirectories(target);
		for (String name : List.of("OpenConsole.exe", "conpty.dll")) { //$NON-NLS-1$ //$NON-NLS-2$
			Path file = target.resolve(name);
			if (!Files.exists(file)) {
				Path part = target.resolve(name + ".part"); //$NON-NLS-1$
				try (InputStream in = bundle.getEntry(folder + name).openStream()) {
					Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
				}
				Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		return target.resolve("conpty.dll").toFile(); //$NON-NLS-1$
	}

	/**
	 * Starts the program in a new console.
	 *
	 * @param argv the program and its arguments
	 * @param envp the environment as name=value, null for this process's own
	 * @param directory where it starts, null for this process's own
	 * @param glyphWidth {@link #GRAPHEMES} or {@link #WCSWIDTH}
	 */
	public static ConsoleProcess start(List<String> argv, String[] envp, File directory, int columns, int lines,
			int glyphWidth) throws IOException {
		ConPty api = conpty();
		if (api == null) {
			throw new IOException("The console of Windows Terminal is not available"); //$NON-NLS-1$
		}
		Kernel32 k32 = Kernel32.INSTANCE;
		HANDLEByReference inRead = new HANDLEByReference(), inWrite = new HANDLEByReference();
		HANDLEByReference outRead = new HANDLEByReference(), outWrite = new HANDLEByReference();
		check(k32.CreatePipe(inRead, inWrite, null, 0), "CreatePipe"); //$NON-NLS-1$
		check(k32.CreatePipe(outRead, outWrite, null, 0), "CreatePipe"); //$NON-NLS-1$
		HANDLEByReference console = new HANDLEByReference();
		int hr = api.ConptyCreatePseudoConsole(size(columns, lines), inRead.getValue(), outWrite.getValue(),
				glyphWidth, console);
		// the console holds its own ends of the pipes now
		k32.CloseHandle(inRead.getValue());
		k32.CloseHandle(outWrite.getValue());
		if (hr != 0) {
			k32.CloseHandle(inWrite.getValue());
			k32.CloseHandle(outRead.getValue());
			throw new IOException("ConptyCreatePseudoConsole failed: 0x" + Integer.toHexString(hr)); //$NON-NLS-1$
		}
		Kernel32Ex k32x = Native.load("kernel32", Kernel32Ex.class); //$NON-NLS-1$
		LongByReference size = new LongByReference(); // a SIZE_T: 64 bits, as are the consoles shipped
		k32x.InitializeProcThreadAttributeList(null, 1, 0, size);
		Memory list = new Memory(size.getValue());
		WinBase.PROCESS_INFORMATION info = new WinBase.PROCESS_INFORMATION();
		try {
			check(k32x.InitializeProcThreadAttributeList(list, 1, 0, size), "InitializeProcThreadAttributeList"); //$NON-NLS-1$
			try {
				check(k32x.UpdateProcThreadAttribute(list, 0, new Pointer(PSEUDOCONSOLE_ATTRIBUTE),
						console.getValue().getPointer(), new Pointer(Native.POINTER_SIZE), null, null),
						"UpdateProcThreadAttribute"); //$NON-NLS-1$
				StartupInfoEx startup = new StartupInfoEx();
				startup.StartupInfo.cb = new WinDef.DWORD(startup.size());
				// no standard handles of this process: the program gets the console's
				startup.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
				startup.lpAttributeList = list;
				check(k32x.CreateProcessW(null, (commandLine(argv) + '\0').toCharArray(), null, null, false,
						EXTENDED_STARTUPINFO_PRESENT | CREATE_UNICODE_ENVIRONMENT, environment(envp),
						directory == null ? null : new WString(directory.getAbsolutePath()), startup, info),
						"CreateProcess " + argv.get(0)); //$NON-NLS-1$
			} finally {
				k32x.DeleteProcThreadAttributeList(list);
			}
		} catch (IOException e) {
			api.ConptyClosePseudoConsole(console.getValue());
			k32.CloseHandle(inWrite.getValue());
			k32.CloseHandle(outRead.getValue());
			throw e;
		}
		k32.CloseHandle(info.hThread);
		return new ConsoleProcess(console.getValue(), info.hProcess, inWrite.getValue(), outRead.getValue(),
				info.dwProcessId.intValue());
	}

	private ConsoleProcess(HANDLE console, HANDLE process, HANDLE input, HANDLE output, int pid) {
		this.console = console;
		this.process = process;
		this.input = input;
		this.output = output;
		this.pid = pid;
	}

	private static int size(int columns, int lines) {
		// COORD { SHORT X; SHORT Y; } by value
		return Math.max(1, Math.min(lines, Short.MAX_VALUE)) << 16 | Math.max(1, Math.min(columns, Short.MAX_VALUE));
	}

	/** A command line Windows splits back into the arguments (CommandLineToArgvW). */
	static String commandLine(List<String> argv) {
		StringBuilder line = new StringBuilder();
		for (String arg : argv) {
			if (line.length() > 0) {
				line.append(' ');
			}
			if (!arg.isEmpty() && arg.chars().noneMatch(c -> c == ' ' || c == '\t' || c == '"')) {
				line.append(arg);
				continue;
			}
			line.append('"');
			int backslashes = 0;
			for (char c : arg.toCharArray()) {
				if (c == '\\') {
					backslashes++;
				} else {
					// backslashes before a quote are escaped, and the quote too
					line.append("\\".repeat(c == '"' ? backslashes * 2 + 1 : backslashes)).append(c); //$NON-NLS-1$
					backslashes = 0;
				}
			}
			// and those before the closing quote
			line.append("\\".repeat(backslashes * 2)).append('"'); //$NON-NLS-1$
		}
		return line.toString();
	}

	private static Memory environment(String[] envp) {
		if (envp == null) {
			return null;
		}
		StringBuilder block = new StringBuilder();
		for (String entry : envp) {
			block.append(entry).append('\0');
		}
		byte[] bytes = block.append('\0').toString().getBytes(StandardCharsets.UTF_16LE);
		Memory memory = new Memory(bytes.length);
		memory.write(0, bytes, 0, bytes.length);
		return memory;
	}

	private static void check(boolean ok, String what) throws IOException {
		if (!ok) {
			throw new IOException(what + " failed: " + Kernel32.INSTANCE.GetLastError()); //$NON-NLS-1$
		}
	}

	/** Tells the console the terminal's new size. */
	public synchronized void resize(int columns, int lines) {
		if (!closed) {
			conpty.ConptyResizePseudoConsole(console, size(columns, lines));
		}
	}

	@Override
	public OutputStream getOutputStream() {
		return new OutputStream() {
			@Override
			public void write(int b) throws IOException {
				write(new byte[] { (byte) b }, 0, 1);
			}

			@Override
			public void write(byte[] b, int off, int len) throws IOException {
				byte[] bytes = off == 0 ? b : java.util.Arrays.copyOfRange(b, off, off + len);
				IntByReference written = new IntByReference();
				// under the lock the handle is closed with: a closed handle's value may be
				// given to another object
				synchronized (inputLock) {
					while (len > 0) {
						if (inputClosed || !Kernel32.INSTANCE.WriteFile(input, bytes, len, written, null)) {
							throw new IOException("The console is closed"); //$NON-NLS-1$
						}
						len -= written.getValue();
						bytes = java.util.Arrays.copyOfRange(bytes, written.getValue(), bytes.length);
					}
				}
			}
		};
	}

	@Override
	public InputStream getInputStream() {
		return new InputStream() {
			@Override
			public int read() throws IOException {
				byte[] one = new byte[1];
				return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
			}

			@Override
			public int read(byte[] b, int off, int len) throws IOException {
				if (len == 0) {
					return 0;
				}
				byte[] bytes = off == 0 ? b : new byte[len];
				IntByReference read = new IntByReference();
				// a closed console breaks the pipe: the end of the output. Only this reader
				// closes the handle: closing it under a pending ReadFile blocks the closer
				if (outputClosed) {
					return -1;
				}
				if (!Kernel32.INSTANCE.ReadFile(output, bytes, len, read, null) || read.getValue() == 0) {
					closeOutput();
					return -1;
				}
				if (bytes != b) {
					System.arraycopy(bytes, 0, b, off, read.getValue());
				}
				return read.getValue();
			}

			@Override
			public void close() {
				// the reader sees the end once the console is gone, and closes the pipe then.
				// Closing the console waits for it to end its clients, so not on the caller's
				// (the display) thread
				Thread closer = new Thread(ConsoleProcess.this::close, "Console close"); //$NON-NLS-1$
				closer.setDaemon(true);
				closer.start();
			}
		};
	}

	@Override
	public InputStream getErrorStream() {
		return InputStream.nullInputStream(); // the console merges it into the output
	}

	@Override
	public int waitFor() throws InterruptedException {
		Kernel32.INSTANCE.WaitForSingleObject(process, WinBase.INFINITE);
		// what the program wrote last still comes out; then the output ends
		close();
		return exitValue();
	}

	@Override
	public int exitValue() {
		IntByReference code = new IntByReference();
		Kernel32.INSTANCE.GetExitCodeProcess(process, code);
		if (code.getValue() == STILL_ACTIVE && isAlive()) {
			throw new IllegalThreadStateException("The process has not exited"); //$NON-NLS-1$
		}
		return code.getValue();
	}

	@Override
	public boolean isAlive() {
		return Kernel32.INSTANCE.WaitForSingleObject(process, 0) == WAIT_TIMEOUT;
	}

	@Override
	public long pid() {
		return pid;
	}

	@Override
	public void destroy() {
		if (isAlive()) {
			Kernel32.INSTANCE.TerminateProcess(process, 1);
		}
		close();
	}

	private volatile boolean outputClosed;

	/** the reading end, closed by the reader when the output has ended */
	private void closeOutput() {
		if (!outputClosed) {
			outputClosed = true;
			Kernel32.INSTANCE.CloseHandle(output);
		}
	}

	private final Object inputLock = new Object();
	private boolean inputClosed;

	// under the lock resize() takes, so it never resizes a closed console
	private synchronized void close() {
		if (!closed) {
			closed = true;
			// first: a write blocked on a full pipe fails once the console is gone
			conpty.ConptyClosePseudoConsole(console);
			synchronized (inputLock) {
				inputClosed = true;
				Kernel32.INSTANCE.CloseHandle(input);
			}
		}
	}
}
