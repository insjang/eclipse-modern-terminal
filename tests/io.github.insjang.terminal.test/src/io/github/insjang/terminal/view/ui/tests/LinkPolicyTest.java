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
	/** JUnit removes it after each test: no temporary folders left behind */
	@org.junit.jupiter.api.io.TempDir
	Path tmp;

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
	public void aUrlWithSomethingGluedToItsFrontOpensFromItsScheme() {
		// Claude Code puts an icon right before the URLs it prints
		assertEquals("https://claude.ai/artifact/1a", LinkPolicy.fromScheme("\u29c9https://claude.ai/artifact/1a"));
		assertEquals("HTTP://e.com", LinkPolicy.fromScheme("URL:HTTP://e.com"));
		assertEquals("https://e.com", LinkPolicy.fromScheme("https://e.com"));
		assertEquals("src/a.txt", LinkPolicy.fromScheme("src/a.txt"));
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
		// Office documents only where they cannot carry macros
		assertTrue(LinkPolicy.systemProgramAllowed("D:\\docs\\report.XLSX"));
		assertTrue(LinkPolicy.systemProgramAllowed("D:\\docs\\a.docx"));
		assertTrue(LinkPolicy.systemProgramAllowed("D:\\docs\\a.pptx"));
		assertFalse(LinkPolicy.systemProgramAllowed("D:\\docs\\a.xlsm"));
		assertFalse(LinkPolicy.systemProgramAllowed("D:\\docs\\a.docm"));
		assertFalse(LinkPolicy.systemProgramAllowed("D:\\docs\\a.xls"));
		assertFalse(LinkPolicy.systemProgramAllowed("D:\\docs\\a.doc"));
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
		Path dir = Files.createTempDirectory(tmp, "linkpolicy");
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
			assertNull(LinkPolicy.systemProgramTarget("//server/share/a.pdf", true)); // not even resolved
			assertNull(LinkPolicy.systemProgramTarget("\\\\server\\share\\a.pdf", true));
		} finally {
			try (var files = Files.list(dir)) {
				for (Path p : files.toList()) {
					Files.delete(p);
				}
			}
			Files.delete(dir);
		}
	}

	@Test
	public void testFileName() {
		assertEquals("reviewer_prompt.md", LinkPolicy.fileName("reference/reviewer_prompt.md"));
		assertEquals("a.txt", LinkPolicy.fileName("C:\\x\\a.txt"));
		assertEquals("a.txt", LinkPolicy.fileName("a.txt"));
	}

	@Test
	public void testOfficeOnlyWithoutMacros() throws IOException {
		Path dir = Files.createTempDirectory(tmp, "office");
		Path plain = dir.resolve("plain.xlsx");
		zip(plain, "[Content_Types].xml", "xl/workbook.xml");
		assertEquals(plain.toRealPath().toString(), LinkPolicy.systemProgramTarget(plain.toString(), true));
		// macros in a package named as one without them
		Path macros = dir.resolve("macros.xlsx");
		zip(macros, "[Content_Types].xml", "xl/vbaProject.bin");
		assertNull(LinkPolicy.systemProgramTarget(macros.toString(), true));
		// an old binary document (OLE) renamed
		Path ole = dir.resolve("old.docx");
		Files.write(ole, new byte[] { (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A,
				(byte) 0xE1 });
		assertNull(LinkPolicy.systemProgramTarget(ole.toString(), true));
		// a hyperlink out of the document is fine, a remote template is not
		Path links = dir.resolve("links.docx");
		zipWith(links, "word/_rels/document.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"r1\" "
				+ "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\" "
				+ "Target=\"https://example.com/\" TargetMode=\"External\"/></Relationships>");
		assertEquals(links.toRealPath().toString(), LinkPolicy.systemProgramTarget(links.toString(), true));
		Path template = dir.resolve("template.docx");
		zipWith(template, "word/_rels/settings.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"r1\" "
				+ "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/attachedTemplate\" "
				+ "Target=\"file://attacker/share/t.dotm\" TargetMode=\"External\"/></Relationships>");
		assertNull(LinkPolicy.systemProgramTarget(template.toString(), true));
		// macros declared by content type under a harmless name
		Path declared = dir.resolve("declared.xlsx");
		try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(Files.newOutputStream(declared))) {
			out.putNextEntry(new java.util.zip.ZipEntry("[Content_Types].xml"));
			out.write("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/xl/x.bin\" ContentType=\"application/vnd.ms-office.vbaProject\"/></Types>"
					.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			out.closeEntry();
		}
		assertNull(LinkPolicy.systemProgramTarget(declared.toString(), true));
		// a ZIP behind something else is not a document Office takes for one
		Path behind = dir.resolve("behind.xlsx");
		Files.write(behind, java.util.Arrays.copyOf(new byte[] { (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0 }, 4));
		Files.write(behind, Files.readAllBytes(plain), java.nio.file.StandardOpenOption.APPEND);
		assertNull(LinkPolicy.systemProgramTarget(behind.toString(), true));
		// a character reference in the attribute is still External to Office
		Path encoded = dir.resolve("encoded.docx");
		zipWith(encoded, "word/_rels/settings.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
				+ "<Relationship Id=\"r1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/frame\" "
				+ "Target=\"https://attacker/\" TargetMode=\"&#69;xternal\"/></Relationships>");
		assertNull(LinkPolicy.systemProgramTarget(encoded.toString(), true));
		// a DOCTYPE, the way to entities, is refused outright
		Path doctype = dir.resolve("doctype.docx");
		zipWith(doctype, "word/_rels/document.xml.rels", "<!DOCTYPE r [<!ENTITY e \"External\">]><Relationships/>");
		assertNull(LinkPolicy.systemProgramTarget(doctype.toString(), true));
		Path embedded = dir.resolve("embedded.pptx");
		zip(embedded, "[Content_Types].xml", "ppt/embeddings/oleObject1.bin");
		assertNull(LinkPolicy.systemProgramTarget(embedded.toString(), true));
	}

	private static void zipWith(Path file, String name, String text) throws IOException {
		try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(Files.newOutputStream(file))) {
			out.putNextEntry(new java.util.zip.ZipEntry("[Content_Types].xml"));
			out.write(TYPES.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			out.closeEntry();
			out.putNextEntry(new java.util.zip.ZipEntry(name));
			out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			out.closeEntry();
		}
	}

	private static final String TYPES = "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
			+ "<Default Extension=\"xml\" ContentType=\"application/xml\"/></Types>";

	private static void zip(Path file, String... names) throws IOException {
		try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(Files.newOutputStream(file))) {
			for (String name : names) {
				out.putNextEntry(new java.util.zip.ZipEntry(name));
				out.write(name.equals("[Content_Types].xml") ? TYPES.getBytes(java.nio.charset.StandardCharsets.UTF_8) : new byte[] { 'x' });
				out.closeEntry();
			}
		}
	}

	@Test
	public void testWhatTheUserAssociated() throws IOException {
		Path dir = Files.createTempDirectory(tmp, "chosen");
		Path xlsm = Files.writeString(dir.resolve("book.xlsm"), "x");
		Path exe = Files.writeString(dir.resolve("tool.exe"), "MZ");
		Path csv = Files.writeString(dir.resolve("data.csv"), "a,b");
		// associated by the user with a program outside Eclipse: it may open there
		assertEquals(xlsm.toRealPath().toString(), LinkPolicy.systemProgramTarget(xlsm.toString(), true, n -> true));
		assertEquals(csv.toRealPath().toString(), LinkPolicy.systemProgramTarget(csv.toString(), true, n -> true));
		// not associated: only the default list
		assertNull(LinkPolicy.systemProgramTarget(xlsm.toString(), true, n -> false));
		assertNull(LinkPolicy.systemProgramTarget(csv.toString(), true, n -> false));
		// what runs on its own never does, associated or not
		assertNull(LinkPolicy.systemProgramTarget(exe.toString(), true, n -> true));
		assertNull(LinkPolicy.systemProgramTarget(dir.resolve("a.ps1").toString(), true, n -> true));
		// judged by the real file: a link named .csv to an .exe stays shut
		Path disguised = Files.createSymbolicLink(dir.resolve("report.csv"), exe);
		assertNull(LinkPolicy.systemProgramTarget(disguised.toString(), true, n -> true));
		// an .xlsx has to be what it says even where the user chose a program for it
		Path fake = Files.writeString(dir.resolve("fake.xlsx"), "not a zip");
		assertNull(LinkPolicy.systemProgramTarget(fake.toString(), true, n -> true));
		// and the question warns for a kind that can carry macros
		assertTrue(LinkPolicy.macroCapable("D:\\a.XLSM"));
		assertTrue(LinkPolicy.macroCapable("D:\\a.doc"));
		assertFalse(LinkPolicy.macroCapable("D:\\a.xlsx"));
		assertFalse(LinkPolicy.macroCapable("D:\\a.pdf"));
	}
}
