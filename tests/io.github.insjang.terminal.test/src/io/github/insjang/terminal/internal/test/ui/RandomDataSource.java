/*******************************************************************************
 * Copyright (c) 2007, 2018 Wind River Systems, Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * Contributors:
 * Michael Scharf (Wind River) - initial API and implementation
 *******************************************************************************/
package io.github.insjang.terminal.internal.test.ui;

import static io.github.insjang.terminal.model.TerminalColor.BLACK;
import static io.github.insjang.terminal.model.TerminalColor.GREEN;
import static io.github.insjang.terminal.model.TerminalColor.RED;
import static io.github.insjang.terminal.model.TerminalColor.YELLOW;

import java.util.Random;

import io.github.insjang.terminal.model.ITerminalTextData;
import io.github.insjang.terminal.model.TerminalStyle;

public class RandomDataSource implements IDataSource {
	Random fRandom = new Random();
	TerminalStyle styleNormal = TerminalStyle.getStyle(BLACK, GREEN);
	TerminalStyle styles[] = new TerminalStyle[] { styleNormal, styleNormal.setBold(true),
			styleNormal.setForeground(RED), styleNormal.setForeground(YELLOW),
			styleNormal.setBold(true).setUnderline(true), styleNormal.setReverse(true),
			styleNormal.setReverse(true).setBold(true), styleNormal.setReverse(true).setUnderline(true) };

	@Override
	public int step(ITerminalTextData terminal) {
		int N = fRandom.nextInt(1000);
		int h = terminal.getHeight();
		int w = terminal.getWidth();
		synchronized (terminal) {
			for (int i = 0; i < N; i++) {
				int line = fRandom.nextInt(h);
				int col = fRandom.nextInt(w);
				char c = (char) ('A' + fRandom.nextInt('z' - 'A'));
				TerminalStyle style = styles[fRandom.nextInt(styles.length)];
				terminal.setChar(line, col, c, style);
			}
		}
		return N;
	}

}
