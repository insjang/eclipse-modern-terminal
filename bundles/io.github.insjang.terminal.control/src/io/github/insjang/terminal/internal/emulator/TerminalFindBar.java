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

import java.util.List;
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
		List<Match> matches = TerminalFind.findAll(canvas.getTerminalText(), pattern, MAX_MATCHES);
		int index = -1;
		if (!matches.isEmpty()) {
			if (current == null) {
				index = matches.size() - 1;
			} else if (up) {
				for (int i = matches.size() - 1; i >= 0 && index < 0; i--) {
					if (matches.get(i).startsBefore(current.startLine(), current.startColumn())) {
						index = i;
					}
				}
				index = index < 0 ? matches.size() - 1 : index;
			} else {
				for (int i = 0; i < matches.size() && index < 0; i++) {
					if (current.startsBefore(matches.get(i).startLine(), matches.get(i).startColumn())) {
						index = i;
					}
				}
				index = index < 0 ? 0 : index;
			}
		}
		if (index < 0) {
			current = null;
			canvas.clearSelection();
			status.setText(TerminalMessages.FIND_NONE);
		} else {
			current = matches.get(index);
			canvas.selectAndReveal(current.startLine(), current.startColumn(), current.endLine(), current.endColumn());
			status.setText(NLS.bind(TerminalMessages.FIND_COUNT, index + 1,
					matches.size() + (matches.size() == MAX_MATCHES ? "+" : ""))); //$NON-NLS-1$ //$NON-NLS-2$
		}
		layout();
	}
}
