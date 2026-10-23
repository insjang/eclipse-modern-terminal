/*******************************************************************************
 * Copyright (c) 2026 insjang and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package io.github.insjang.terminal.view.ui.internal.actions;

import org.eclipse.jface.resource.ImageDescriptor;
import io.github.insjang.terminal.control.ITerminalViewControl;

/**
 * Selects and copies the output of the last command, where the shell marks it (OSC 133).
 */
public class TerminalActionCopyLastOutput extends AbstractTerminalAction {
	public TerminalActionCopyLastOutput() {
		super(TerminalActionCopyLastOutput.class.getName());

		setupAction(ActionMessages.COPY_LAST_OUTPUT, ActionMessages.COPY_LAST_OUTPUT_TOOLTIP, (ImageDescriptor) null,
				null, null, false);
	}

	@Override
	public void run() {
		ITerminalViewControl target = getTarget();
		if (target != null) {
			target.copyLastCommandOutput();
		}
	}

	@Override
	public void updateAction(boolean aboutToShow) {
		ITerminalViewControl target = getTarget();
		setEnabled(target != null && !target.isEmpty());
	}
}
