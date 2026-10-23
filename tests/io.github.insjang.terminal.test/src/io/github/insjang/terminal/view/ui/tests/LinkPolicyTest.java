/*******************************************************************************
 * Copyright (c) 2026 insjang and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package io.github.insjang.terminal.view.ui.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.insjang.terminal.view.ui.internal.tabs.LinkPolicy;

public class LinkPolicyTest {
	@Test
	public void targets() {
		assertEquals("https://e.com/a", LinkPolicy.target("https://e.com/a", "pc"));
		assertEquals("/home/me/a b", LinkPolicy.target("file:///home/me/a%20b", "pc"));
		assertEquals("/tmp/x", LinkPolicy.target("file://localhost/tmp/x", "pc"));
		assertEquals("/tmp/x", LinkPolicy.target("file://PC/tmp/x", "pc")); // this computer, as ls --hyperlink writes it
		assertEquals("C:/Users/me/a.txt", LinkPolicy.target("file:///C:/Users/me/a.txt", "pc"));
		assertNull(LinkPolicy.target("file://server/share/a.txt", "pc")); // another host: never touched (NTLM)
		assertNull(LinkPolicy.target("file:////server/share/a.txt", "pc")); // UNC in the path
		assertNull(LinkPolicy.target("file:///%5C%5Cserver%5Cshare", "pc"));
		assertNull(LinkPolicy.target("file:///tmp/%0Aa", "pc")); // control character
	}

	@Test
	public void confirmWhenTheTextSaysSomethingElse() {
		assertFalse(LinkPolicy.confirm("https://e.com/a", "https://e.com/a", "https://e.com/a"));
		assertTrue(LinkPolicy.confirm("https://evil.example/", "https://evil.example/", "https://e.com/a"));
		assertTrue(LinkPolicy.confirm("https://e.com/a", "https://e.com/a", "docs"));
		assertFalse(LinkPolicy.confirm("file:///home/me/README.md", "/home/me/README.md", "README.md")); // ls --hyperlink
		assertFalse(LinkPolicy.confirm("file:///home/me/README.md", "/home/me/README.md", "/home/me/README.md"));
		assertTrue(LinkPolicy.confirm("file:///tmp/run.bat", "/tmp/run.bat", "README.md"));
		assertTrue(LinkPolicy.confirm("file:///tmp/xREADME.md", "/tmp/xREADME.md", "README.md"));
		assertTrue(LinkPolicy.confirm("file:///tmp/a", "/tmp/a", ""));
	}

	@Test
	public void neverAProgramOutsideEclipse() {
		assertEquals("org.eclipse.ui.genericeditor.GenericEditor",
				LinkPolicy.editorId("org.eclipse.ui.genericeditor.GenericEditor", false, false));
		assertEquals(LinkPolicy.TEXT_EDITOR_ID, LinkPolicy.editorId("org.eclipse.ui.systemExternalEditor", true, false));
		assertEquals(LinkPolicy.TEXT_EDITOR_ID, LinkPolicy.editorId("org.eclipse.ui.systemInPlaceEditor", false, true));
		assertEquals(LinkPolicy.TEXT_EDITOR_ID, LinkPolicy.editorId(null, false, false));
	}
}
