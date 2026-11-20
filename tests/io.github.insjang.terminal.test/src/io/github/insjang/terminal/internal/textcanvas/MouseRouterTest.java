package io.github.insjang.terminal.internal.textcanvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.junit.jupiter.api.Test;

public class MouseRouterTest {
	/** A program listening for every press, move and wheel turn, as Claude Code does. */
	private final StringBuilder got = new StringBuilder();
	private final MouseRouter router = new MouseRouter();
	{
		router.setProgram(new TextCanvas.IMouseHandler() {
			@Override
			public boolean button(int button, int modifiers, Point cell, boolean pressed) {
				got.append(pressed ? "press" : "release").append(modifiers).append(' ');
				return true;
			}

			@Override
			public boolean moved(int button, int modifiers, Point cell) {
				got.append("move").append(modifiers).append(' ');
				return true;
			}

			@Override
			public boolean wheel(int lines, int modifiers, Point cell) {
				got.append("wheel").append(modifiers).append(' ');
				return true;
			}
		});
	}

	private final Point cell = new Point(3, 2);

	@Test
	public void ctrlAloneKeepsClicksAndMovesForTheTerminal() {
		// Ctrl+click opens a link and Ctrl+move underlines it, whoever listens
		assertFalse(router.press(1, SWT.MOD1, cell));
		assertFalse(router.release(1, SWT.MOD1 | SWT.BUTTON1, cell));
		assertFalse(router.move(SWT.MOD1, cell, false));
		assertEquals("", got.toString());
	}

	@Test
	public void ctrlWithAltOrAPlainClickGoesToTheProgram() {
		assertTrue(router.press(1, SWT.MOD1 | SWT.ALT, cell));
		assertTrue(router.release(1, SWT.MOD1 | SWT.ALT | SWT.BUTTON1, cell));
		assertTrue(router.press(1, 0, cell));
		assertTrue(router.move(SWT.BUTTON1, cell, false));
		// the release follows its press, Ctrl pressed since or not
		assertTrue(router.release(1, SWT.MOD1 | SWT.BUTTON1, cell));
		assertEquals("press24 release24 press0 move0 release16 ", got.toString());
	}

	@Test
	public void shiftKeepsEverythingForTheTerminal() {
		assertFalse(router.press(1, SWT.SHIFT, cell));
		assertFalse(router.wheel(1, SWT.SHIFT, cell));
		assertEquals("", got.toString());
	}

	@Test
	public void ctrlWheelStillGoesToTheProgram() {
		assertTrue(router.wheel(1, SWT.MOD1, cell));
		assertEquals("wheel16 ", got.toString());
	}
}
