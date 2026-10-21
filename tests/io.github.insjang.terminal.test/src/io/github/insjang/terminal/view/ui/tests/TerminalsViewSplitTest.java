/*******************************************************************************
 * Copyright (c) 2026 Eclipse contributors and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package io.github.insjang.terminal.view.ui.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import io.github.insjang.terminal.view.ui.internal.view.TerminalsView;
import org.junit.jupiter.api.Test;

/**
 * Tests for the drop zones that split the terminals view when a terminal tab is dragged onto the
 * terminal area.
 */
public class TerminalsViewSplitTest {

	// terminal area below a 20 pixel tab strip
	private static final Rectangle CLIENT = new Rectangle(0, 20, 400, 200);

	private static int zone(int x, int y) {
		return TerminalsView.computeDropZone(CLIENT, new Point(x, y));
	}

	@Test
	public void aboveTheTerminalAreaIsTheTabStrip() {
		assertEquals(TerminalsView.DROP_TABS, zone(200, 10));
	}

	@Test
	public void edgesSplit() {
		assertEquals(SWT.LEFT, zone(20, 120));
		assertEquals(SWT.RIGHT, zone(390, 120));
		assertEquals(SWT.TOP, zone(200, 30));
		assertEquals(SWT.BOTTOM, zone(200, 210));
	}

	@Test
	public void centerMovesIntoTheTabFolder() {
		assertEquals(SWT.CENTER, zone(200, 120));
		// a quarter from the left edge is already the center
		assertEquals(SWT.CENTER, zone(100, 120));
	}

	@Test
	public void cornerGoesToTheNearerEdge() {
		assertEquals(SWT.LEFT, zone(10, 40));
		assertEquals(SWT.TOP, zone(60, 25));
	}
}
