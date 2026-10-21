/*******************************************************************************
 * Copyright (c) 2008, 2018 Wind River Systems, Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 * Martin Oberhuber (Wind River) - initial API and implementation
 *******************************************************************************/
package io.github.insjang.terminal.test;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

/**
 * Master test suite to run all terminal unit tests.
 */
@Suite
@SelectClasses({ //
		io.github.insjang.terminal.internal.emulator.AllTestSuite.class, //
		io.github.insjang.terminal.internal.model.AllTestSuite.class, //
		io.github.insjang.terminal.model.AllTestSuite.class, //
		io.github.insjang.terminal.internal.connector.TerminalConnectorTest.class, //
		io.github.insjang.terminal.internal.connector.TerminalToRemoteInjectionOutputStreamTest.class, //
		io.github.insjang.terminal.view.ui.tests.TerminalsViewReorderTest.class, //
})
public class AutomatedTestSuite {

	public static final String PI_TERMINAL_TESTS = "io.github.insjang.terminal.test"; //$NON-NLS-1$

}
