/*******************************************************************************
 * Copyright (c) 2007, 2018 Wind River Systems, Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * Contributors:
 * Michael Scharf (Wind River) - initial API and implementation
 * Anton Leherbauer (Wind River) - [420928] Terminal widget leaks memory
 *******************************************************************************/
package io.github.insjang.terminal.internal.textcanvas;

import org.eclipse.swt.widgets.Display;
import io.github.insjang.terminal.model.ITerminalTextDataSnapshot;

/**
 * @author Michael.Scharf@scharf-software.com
 *
 */
public class PollingTextCanvasModel extends AbstractTextCanvasModel {
	private static final int DEFAULT_POLL_INTERVAL = 50;
	/**
	 * How long a program is given to finish a screen it said it was drawing. A
	 * program that says so and then stops must not leave the view frozen.
	 */
	private static final int REDRAW_GRACE = 200;
	int fPollInterval = -1;
	private boolean fRedrawing;
	// when the view held back the first time since it last looked, or 0
	private long fHeldBackSince;

	/**
	 * A program that draws a screen in pieces can say where a screen begins and
	 * ends, and while it is between the two the view leaves it alone rather than
	 * catching it half drawn.
	 *
	 * @param redrawing whether a screen is being drawn
	 */
	public synchronized void setSynchronizedOutput(boolean redrawing) {
		fRedrawing = redrawing;
	}

	/**
	 * Copies the text unless a screen is being drawn. Every look at the text
	 * comes here, the polls and a scroll or resize alike, and the check and the
	 * copy are one step, so a screen cannot begin between them. The view is held
	 * back for at most {@link #REDRAW_GRACE} since it last looked, however many
	 * screens begin meanwhile.
	 */
	@Override
	protected synchronized boolean takeSnapshot() {
		long now = System.currentTimeMillis();
		if (fRedrawing && fHeldBackSince == 0) {
			fHeldBackSince = now;
		}
		if (fRedrawing && now - fHeldBackSince < REDRAW_GRACE) {
			return false;
		}
		fHeldBackSince = 0;
		return super.takeSnapshot();
	}

	/**
	 *
	 */
	public PollingTextCanvasModel(ITerminalTextDataSnapshot snapshot) {
		super(snapshot);
		startPolling();
	}

	public void setUpdateInterval(int t) {
		fPollInterval = t;
	}

	public void stopPolling() {
		// timerExec only dispatches if the delay is >=0
		fPollInterval = -1;
	}

	public void startPolling() {
		if (fPollInterval < 0) {
			fPollInterval = DEFAULT_POLL_INTERVAL;
			Display.getDefault().timerExec(fPollInterval, new Runnable() {
				@Override
				public void run() {
					update();
					Display.getDefault().timerExec(fPollInterval, this);
				}
			});
		}
	}
}
