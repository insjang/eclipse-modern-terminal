/*******************************************************************************
 * Copyright (c) 2026 insjang and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package io.github.insjang.terminal.internal.emulator;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

import io.github.insjang.terminal.internal.control.impl.TerminalMessages;
import io.github.insjang.terminal.internal.model.TerminalFind;
import io.github.insjang.terminal.internal.model.TerminalFind.Match;
import io.github.insjang.terminal.internal.textcanvas.TextCanvas;

/**
 * The find bar above the terminal (Ctrl+Shift+F): searches the scrollback,
 * Enter for the match above, Shift+Enter for the one below, Esc closes.
 */
class TerminalFindBar extends Composite {
	/** Most matches counted; a search for one letter in a full buffer stops there. */
	private static final int MAX_MATCHES = 10000;
	/** Longest a search or a count may take, in ns. */
	private static final long TIME_LIMIT = 500_000_000L;
	private final TextCanvas canvas;
	private final Text query;
	private final Button caseSensitive;
	private final Button regex;
	private final Label status;
	private Match current;

	TerminalFindBar(Composite parent, TextCanvas canvas) {
		super(parent, SWT.NONE);
		this.canvas = canvas;
		GridLayout layout = new GridLayout(6, false);
		layout.marginHeight = 2;
		layout.marginWidth = 4;
		setLayout(layout);
		setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

		query = new Text(this, SWT.BORDER | SWT.SINGLE | SWT.SEARCH);
		query.setMessage(TerminalMessages.FIND_MESSAGE);
		GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
		data.widthHint = 200;
		query.setLayoutData(data);
		caseSensitive = check(TerminalMessages.FIND_CASE);
		regex = check(TerminalMessages.FIND_REGEX);
		push("↑", TerminalMessages.FIND_PREVIOUS, true); //$NON-NLS-1$
		push("↓", TerminalMessages.FIND_NEXT, false); //$NON-NLS-1$
		status = new Label(this, SWT.NONE);
		status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));

		query.addModifyListener(e -> {
			current = null; // from the bottom again
			find(true);
		});
		query.addTraverseListener(e -> {
			if (e.detail == SWT.TRAVERSE_RETURN) {
				e.doit = false;
				find((e.stateMask & SWT.SHIFT) == 0);
			} else if (e.detail == SWT.TRAVERSE_ESCAPE) {
				e.doit = false;
				close();
			}
		});
	}

	private Button check(String text) {
		Button b = new Button(this, SWT.CHECK);
		b.setText(text);
		b.addListener(SWT.Selection, e -> {
			current = null;
			find(true);
		});
		return b;
	}

	private void push(String text, String tooltip, boolean up) {
		Button b = new Button(this, SWT.PUSH);
		b.setText(text);
		b.setToolTipText(tooltip);
		b.addListener(SWT.Selection, e -> find(up));
	}

	void open() {
		String selection = canvas.getSelectionText();
		if (selection != null && !selection.isEmpty() && selection.indexOf('\n') < 0) {
			query.setText(selection);
		}
		query.selectAll();
		query.setFocus();
	}

	private void close() {
		Composite parent = getParent();
		dispose();
		parent.layout(true);
		canvas.setFocus();
	}

	/**
	 * Selects the match above the current one (<code>up</code>) or below it, going
	 * round at the top and the bottom; with none yet, the last one.
	 */
	private void find(boolean up) {
		String text = query.getText();
		if (text.isEmpty()) {
			status.setText(""); //$NON-NLS-1$
			layout();
			return;
		}
		Pattern pattern;
		try {
			pattern = TerminalFind.pattern(text, caseSensitive.getSelection(), regex.getSelection());
		} catch (PatternSyntaxException e) {
			status.setText(TerminalMessages.FIND_BAD_REGEX);
			layout();
			return;
		}
		// the text is the program's and the expression the user's: neither may hang the UI
		Match found;
		try {
			found = TerminalFind.next(canvas.getTerminalText(), pattern, current, up, System.nanoTime() + TIME_LIMIT);
		} catch (TerminalFind.TooSlowException e) {
			status.setText(TerminalMessages.FIND_TOO_SLOW);
			layout();
			return;
		}
		if (found == null) {
			current = null;
			canvas.clearSelection();
			status.setText(TerminalMessages.FIND_NONE);
		} else {
			current = found;
			canvas.selectAndReveal(current.startLine(), current.startColumn(), current.endLine(), current.endColumn());
			try {
				int[] count = TerminalFind.count(canvas.getTerminalText(), pattern, current, MAX_MATCHES,
						System.nanoTime() + TIME_LIMIT);
				status.setText(count[0] > MAX_MATCHES ? NLS.bind(TerminalMessages.FIND_MANY, MAX_MATCHES)
						: NLS.bind(TerminalMessages.FIND_COUNT, count[1] + 1, count[0]));
			} catch (TerminalFind.TooSlowException e) {
				status.setText(""); //$NON-NLS-1$ // found, but too slow to count
			}
		}
		layout();
	}
}
