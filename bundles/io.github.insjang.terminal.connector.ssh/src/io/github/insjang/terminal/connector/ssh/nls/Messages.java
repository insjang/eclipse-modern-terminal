/*******************************************************************************
 * Copyright (c) 2011 - 2018 Wind River Systems, Inc. and others. All rights reserved.
 * This program and the accompanying materials are made available under the terms
 * of the Eclipse Public License 2.0 which accompanies this distribution, and is
 * available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 * Wind River Systems - initial API and implementation
 * Max Weninger (Wind River) - [361352] [TERMINALS][SSH] Add SSH terminal support
 *******************************************************************************/
package io.github.insjang.terminal.connector.ssh.nls;

import org.eclipse.osgi.util.NLS;

/**
 * Externalized strings management.
 */
public class Messages extends NLS {

	// The plug-in resource bundle name
	private static final String BUNDLE_NAME = "io.github.insjang.terminal.connector.ssh.nls.Messages"; //$NON-NLS-1$

	/**
	 * Static constructor.
	 */
	static {
		// Load message values from bundle file
		NLS.initializeMessages(BUNDLE_NAME, Messages.class);
	}

	// **** Declare externalized string id's down here *****

	public static String SshLauncherDelegate_terminalTitle;
	public static String SshLauncherDelegate_terminalTitle_port;
	public static String SshLauncherDelegate_terminalTitle_default;
	public static String SshWizardConfigurationPanel_savePassword;
	public static String SshWizardConfigurationPanel_session;
	public static String SshWizardConfigurationPanel_save;
	public static String SshWizardConfigurationPanel_saveTooltip;
	public static String SshWizardConfigurationPanel_delete;
	public static String SshWizardConfigurationPanel_deleteTooltip;
	public static String SshWizardConfigurationPanel_saveTitle;
	public static String SshWizardConfigurationPanel_saveMessage;
}
