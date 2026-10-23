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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
	public void webLinksAskUnlessTheTextIsTheUrl() {
		// the file name rule is for file links only: these used to open without a question
		assertTrue(LinkPolicy.confirm("https://evil.example/?u=/https://github.com/login",
				"https://evil.example/?u=/https://github.com/login", "https://github.com/login"));
		assertTrue(LinkPolicy.confirm("https://evil.example/x/github.com", "https://evil.example/x/github.com", "github.com"));
		assertFalse(LinkPolicy.confirm("https://E.com/A", "https://E.com/A", "HTTPS://e.com/A")); // scheme, host in any case
		assertTrue(LinkPolicy.confirm("https://e.com/A", "https://e.com/A", "https://e.com/a")); // the path is not
		assertTrue(LinkPolicy.isWeb("HTTPS://e.com"));
		assertTrue(LinkPolicy.isWeb("Http://e.com"));
		assertFalse(LinkPolicy.isWeb("file:///tmp"));
	}

	@Test
	public void questionsShowTheRealTarget() {
		// a Cyrillic e in the host, a right-to-left override in the path
		String shown = LinkPolicy.display("https://\u0435xample.com/\u202ecod.exe");
		assertTrue(shown.startsWith("https://xn--") && shown.endsWith(".com/%E2%80%AEcod.exe"), shown);
		assertEquals("https://user@xn--bcher-kva.example:8443/a%20b?q=%ED%95%9C",
				LinkPolicy.display("https://user@b\u00fccher.example:8443/a b?q=\ud55c"));
		assertEquals("/tmp/a\\u202Etxt.exe", LinkPolicy.display("/tmp/a\u202etxt.exe"));
		assertEquals("/tmp/\ud55c\uae00.txt", LinkPolicy.display("/tmp/\ud55c\uae00.txt")); // Hangul stays readable
	}

	@Test
	public void theSystemProgramOnlyForDocumentsAndImages() {
		assertTrue(LinkPolicy.systemProgramAllowed("C:\\Users\\me\\a.PDF"));
		assertTrue(LinkPolicy.systemProgramAllowed("/home/me/shot.png"));
		assertTrue(LinkPolicy.systemProgramAllowed("C:\\a.jpeg. . ")); // Windows drops the trailing dots and spaces
		assertFalse(LinkPolicy.systemProgramAllowed("C:\\a.exe"));
		assertFalse(LinkPolicy.systemProgramAllowed("C:\\a.pdf.exe"));
		assertFalse(LinkPolicy.systemProgramAllowed("C:\\a.exe . "));
		assertFalse(LinkPolicy.systemProgramAllowed("C:\\a.svg"));
		assertFalse(LinkPolicy.systemProgramAllowed("C:\\a.html"));
		assertFalse(LinkPolicy.systemProgramAllowed("C:\\a.exe:x.pdf")); // an alternate data stream
		assertFalse(LinkPolicy.systemProgramAllowed("\\\\server\\share\\a.pdf")); // UNC
		assertFalse(LinkPolicy.systemProgramAllowed("/tmp/.pdf"));
	}

	@Test
	public void neverAProgramOutsideEclipse() {
		assertEquals("org.eclipse.ui.genericeditor.GenericEditor",
				LinkPolicy.editorId("org.eclipse.ui.genericeditor.GenericEditor", true, false));
		assertEquals(LinkPolicy.TEXT_EDITOR_ID, LinkPolicy.editorId("org.eclipse.ui.systemExternalEditor", false, false));
		assertEquals(LinkPolicy.TEXT_EDITOR_ID, LinkPolicy.editorId("org.eclipse.ui.systemInPlaceEditor", false, true));
		assertEquals(LinkPolicy.TEXT_EDITOR_ID, LinkPolicy.editorId("some.external.Program", false, false));
		assertEquals(LinkPolicy.TEXT_EDITOR_ID, LinkPolicy.editorId(null, false, false));
	}

	@Test
	public void theSystemProgramGetsTheRealFile() throws IOException {
		Path dir = Files.createTempDirectory("linkpolicy");
		try {
			Path pdf = Files.writeString(dir.resolve("a.pdf"), "%PDF");
			Path hta = Files.writeString(dir.resolve("evil.hta"), "<script>");
			Path disguised = Files.createSymbolicLink(dir.resolve("report.pdf"), hta);
			Path real = Files.createSymbolicLink(dir.resolve("link.pdf"), pdf);
			assertEquals(pdf.toRealPath().toString(), LinkPolicy.systemProgramTarget(pdf.toString(), true));
			assertNull(LinkPolicy.systemProgramTarget(disguised.toString(), true)); // the name says pdf, it leads to .hta
			assertEquals(pdf.toRealPath().toString(), LinkPolicy.systemProgramTarget(real.toString(), true));
			assertNull(LinkPolicy.systemProgramTarget(pdf.toString(), false)); // not Windows: always inside Eclipse
			assertNull(LinkPolicy.systemProgramTarget(dir.resolve("missing.pdf").toString(), true));
		} finally {
			try (var files = Files.list(dir)) {
				for (Path p : files.toList()) {
					Files.delete(p);
				}
			}
			Files.delete(dir);
		}
	}
}
