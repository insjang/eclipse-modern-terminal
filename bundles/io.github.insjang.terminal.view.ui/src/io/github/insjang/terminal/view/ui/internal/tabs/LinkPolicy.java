/*******************************************************************************
 * Copyright (c) 2026 insjang and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package io.github.insjang.terminal.view.ui.internal.tabs;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.IDN;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import io.github.insjang.terminal.control.TerminalDirectory;

/**
 * What Ctrl+click may do with a hyperlink a program put in the terminal (OSC 8):
 * the output is not to be trusted, so a link only opens a web page or a local file
 * in an Eclipse editor, never runs a program, and says where it goes when its text
 * says something else.
 */
public final class LinkPolicy {
	/** The editor for a file whose editor would be a program outside Eclipse. */
	public static final String TEXT_EDITOR_ID = "org.eclipse.ui.DefaultTextEditor"; //$NON-NLS-1$

	private LinkPolicy() {
	}

	/**
	 * @param link an http, https or file URI (others never get stored)
	 * @param localHost the name of this computer
	 * @return the web URL as it is, a file URI as its local path; null for a file on
	 *         another host or a UNC path (opening it would hand over the Windows login)
	 */
	public static String target(String link, String localHost) {
		if (!link.regionMatches(true, 0, "file://", 0, 7)) { //$NON-NLS-1$
			return link;
		}
		TerminalDirectory file = TerminalDirectory.parse(link);
		if (file == null) {
			return null;
		}
		String host = file.host();
		if (!host.isEmpty() && !host.equalsIgnoreCase("localhost") && !host.equalsIgnoreCase(localHost)) { //$NON-NLS-1$
			return null;
		}
		String path = file.path();
		if (path.startsWith("//") || path.startsWith("/\\") || path.startsWith("\\")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			return null;
		}
		// file:///C:/a on Windows
		return path.length() > 3 && path.charAt(0) == '/' && path.charAt(2) == ':' ? path.substring(1) : path;
	}

	/** @return the last segment of a path, by either separator: what a file is called */
	public static String fileName(String path) {
		return path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
	}

	/** @return whether the text is an http or https URL, in any case */
	public static boolean isWeb(String s) {
		return s.regionMatches(true, 0, "http://", 0, 7) || s.regionMatches(true, 0, "https://", 0, 8); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/**
	 * @param target what the link opens (see {@link #target})
	 * @param visible the text under the mouse
	 * @return whether to show where the link goes and ask first: a web link unless the
	 *         text is the URL itself (scheme and host in any case); a file link unless the
	 *         text is the link, the path, or the file name (as ls --hyperlink shows it)
	 */
	public static boolean confirm(String link, String target, String visible) {
		if (visible == null || visible.isEmpty()) {
			return true;
		}
		if (isWeb(link)) {
			return !normalised(visible).equals(normalised(link));
		}
		return !visible.equals(link) && !visible.equals(target) && !target.endsWith('/' + visible)
				&& !target.endsWith('\\' + visible);
	}

	// scheme and host in lower case, the rest as it is
	private static String normalised(String url) {
		int start = url.indexOf("://"); //$NON-NLS-1$
		if (start < 0) {
			return url;
		}
		int end = authorityEnd(url, start + 3);
		return url.substring(0, end).toLowerCase(Locale.ROOT) + url.substring(end);
	}

	private static int authorityEnd(String url, int from) {
		int end = url.length();
		for (char c : new char[] { '/', '?', '#' }) {
			int i = url.indexOf(c, from);
			if (i >= 0 && i < end) {
				end = i;
			}
		}
		return end;
	}

	/**
	 * The target as it is shown in a question: a web URL with its host in ASCII (an IDN
	 * in punycode) and everything not plain ASCII in the rest percent-encoded, a path with
	 * control, format (bidi) and line separator characters as \\uXXXX, so that nothing
	 * can make it look like another address.
	 */
	public static String display(String target) {
		if (!isWeb(target)) {
			StringBuilder b = new StringBuilder();
			target.codePoints().forEach(c -> {
				int type = Character.getType(c);
				if (type == Character.CONTROL || type == Character.FORMAT || type == Character.LINE_SEPARATOR
						|| type == Character.PARAGRAPH_SEPARATOR) {
					b.append(String.format("\\u%04X", c)); //$NON-NLS-1$
				} else {
					b.appendCodePoint(c);
				}
			});
			return b.toString();
		}
		int start = target.indexOf("://") + 3; //$NON-NLS-1$
		int end = authorityEnd(target, start);
		String authority = target.substring(start, end);
		int at = authority.lastIndexOf('@');
		int colon = authority.indexOf(':', at + 1);
		String host = authority.substring(at + 1, colon < 0 ? authority.length() : colon);
		try {
			host = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED);
		} catch (IllegalArgumentException e) {
			// shown encoded below
		}
		String shown = target.substring(0, start) + authority.substring(0, at + 1) + host
				+ (colon < 0 ? "" : authority.substring(colon)) + target.substring(end); //$NON-NLS-1$
		StringBuilder b = new StringBuilder();
		for (byte c : shown.getBytes(StandardCharsets.UTF_8)) {
			if (c > 0x20 && c < 0x7f) {
				b.append((char) c);
			} else {
				b.append(String.format("%%%02X", c & 0xff)); //$NON-NLS-1$
			}
		}
		return b.toString();
	}

	/** The id of the editor that hands a file to the program the system has for it. */
	public static final String SYSTEM_EDITOR_ID = "org.eclipse.ui.systemExternalEditor"; //$NON-NLS-1$
	/** What the system program may open from terminal output: documents and images that run nothing. */
	// Office documents only in the formats that cannot carry macros (not xlsm, docm, xls, doc)
	private static final Set<String> SYSTEM_PROGRAM_TYPES = Set.of("pdf", "png", "jpg", "jpeg", "gif", "bmp", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
			"xlsx", "docx", "pptx"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

	/**
	 * The editor for a file named by terminal output: the registered one if it runs inside
	 * Eclipse, else the text editor; never one outside Eclipse (the system program runs
	 * .exe, .bat, .lnk...) or in place (OLE).
	 *
	 * @param id the registered editor, or null
	 * @param internal whether it runs inside Eclipse
	 * @param inPlace whether it is an OLE editor
	 */
	public static String editorId(String id, boolean internal, boolean inPlace) {
		if (id == null || !internal || inPlace || id.equals(SYSTEM_EDITOR_ID)
				|| id.equals("org.eclipse.ui.systemInPlaceEditor")) { //$NON-NLS-1$
			return TEXT_EDITOR_ID;
		}
		return id;
	}

	/**
	 * The file the system program may open in place of an Eclipse editor: on Windows only
	 * (elsewhere xdg-open picks the program by the content, not the name), and only a PDF,
	 * an image or an Office document that cannot carry macros, by the name of the file a
	 * link, junction or symlink leads to.
	 *
	 * @return the real path to hand to the system program, or null to open inside Eclipse
	 */
	public static String systemProgramTarget(String path, boolean windows) {
		// a network path is never touched, not even to resolve it (that would hand over the Windows login)
		if (!windows || path.startsWith("//") || path.startsWith("\\\\")) { //$NON-NLS-1$ //$NON-NLS-2$
			return null;
		}
		String real;
		try {
			real = Path.of(path).toRealPath().toString();
		} catch (IOException | RuntimeException e) {
			return null;
		}
		return systemProgramAllowed(real) && (!isOffice(real) || macroFree(real)) ? real : null;
	}

	private static boolean isOffice(String path) {
		String p = path.toLowerCase(Locale.ROOT).replaceAll("[. ]+$", ""); //$NON-NLS-1$ //$NON-NLS-2$
		return p.endsWith(".xlsx") || p.endsWith(".docx") || p.endsWith(".pptx"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	/**
	 * Office opens a file by what it holds, not by its name: a workbook with macros renamed
	 * to .xlsx would still run them. The document has to be what its name says, a ZIP
	 * package from its first byte (an old binary document, or one hidden behind it, is
	 * not), with nothing in it that runs or is fetched: no macro project, ActiveX control,
	 * ribbon customisation or embedded object, and no relationship that reaches out of the
	 * file other than a hyperlink (a remote template is fetched on opening, and with it
	 * goes the Windows login).
	 */
	static boolean macroFree(String path) {
		try (InputStream in = Files.newInputStream(Path.of(path))) {
			byte[] head = in.readNBytes(4);
			if (head.length < 4 || head[0] != 'P' || head[1] != 'K' || head[2] != 3 || head[3] != 4) {
				return false;
			}
		} catch (IOException | RuntimeException e) {
			return false;
		}
		try (ZipFile zip = new ZipFile(path)) {
			Set<String> names = new HashSet<>();
			for (ZipEntry entry : Collections.list(zip.entries())) {
				// part names are percent-encoded URIs, and Office takes them in any case
				String name = URLDecoder.decode(entry.getName().replace("+", "%2B"), StandardCharsets.UTF_8) //$NON-NLS-1$ //$NON-NLS-2$
						.toLowerCase(Locale.ROOT);
				if (!names.add(name) || ACTIVE.matcher(name).find()) {
					return false; // a part twice, or one that runs
				}
				if (name.endsWith(".rels") && !relationshipsStayInside(xml(zip, entry))) { //$NON-NLS-1$
					return false;
				}
				if (name.equals("[content_types].xml") && !typesAreInert(xml(zip, entry))) { //$NON-NLS-1$
					return false;
				}
			}
			return true;
		} catch (IOException | ParserConfigurationException | SAXException | RuntimeException e) {
			return false; // anything that does not read as a plain document is not one
		}
	}

	// what can run, or brings in what does: macros, ActiveX, OLE, ribbon code, templates, packages
	private static final Pattern ACTIVE = Pattern.compile(
			"vbaproject|vbadata|macroenabled|activex|oleobject|/embeddings/|customui|attachedtemplate|/package"); //$NON-NLS-1$

	private static final String RELATIONSHIPS = "http://schemas.openxmlformats.org/package/2006/relationships"; //$NON-NLS-1$
	private static final String HYPERLINK = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink"; //$NON-NLS-1$
	private static final String TYPES = "http://schemas.openxmlformats.org/package/2006/content-types"; //$NON-NLS-1$

	/** No relationship of a kind that runs, and none that reaches out of the file but a hyperlink. */
	private static boolean relationshipsStayInside(Document rels) {
		NodeList list = rels.getElementsByTagNameNS("*", "Relationship"); //$NON-NLS-1$ //$NON-NLS-2$
		for (int i = 0; i < list.getLength(); i++) {
			Element r = (Element) list.item(i);
			if (!RELATIONSHIPS.equals(r.getNamespaceURI())) {
				return false;
			}
			String type = r.getAttribute("Type"); //$NON-NLS-1$
			if (ACTIVE.matcher(type.toLowerCase(Locale.ROOT)).find()) {
				return false;
			}
			if ("external".equalsIgnoreCase(r.getAttribute("TargetMode").strip()) && !HYPERLINK.equals(type)) { //$NON-NLS-1$ //$NON-NLS-2$
				return false;
			}
		}
		return true;
	}

	/** No part declared as one that runs, whatever it is called. */
	private static boolean typesAreInert(Document types) {
		NodeList all = types.getElementsByTagNameNS("*", "*"); //$NON-NLS-1$ //$NON-NLS-2$
		for (int i = 0; i < all.getLength(); i++) {
			Element e = (Element) all.item(i);
			if (!TYPES.equals(e.getNamespaceURI())
					|| ACTIVE.matcher(e.getAttribute("ContentType").toLowerCase(Locale.ROOT)).find()) { //$NON-NLS-1$
				return false;
			}
		}
		return true;
	}

	private static final int MAX_PART = 1 << 20;

	/** A part read whole, never cut short, by a parser that resolves nothing outside it. */
	private static Document xml(ZipFile zip, ZipEntry entry) throws IOException, ParserConfigurationException, SAXException {
		byte[] bytes;
		try (InputStream in = zip.getInputStream(entry)) {
			bytes = in.readNBytes(MAX_PART + 1);
		}
		if (bytes.length > MAX_PART) {
			throw new IOException("part too large"); //$NON-NLS-1$
		}
		// the JDK's own parser, whatever else is on the class path: the features below are its
		DocumentBuilderFactory f = DocumentBuilderFactory.newDefaultInstance();
		f.setNamespaceAware(true);
		f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); //$NON-NLS-1$
		f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
		f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); //$NON-NLS-1$
		f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, ""); //$NON-NLS-1$
		f.setXIncludeAware(false);
		f.setExpandEntityReferences(false);
		return f.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
	}

	/**
	 * @return whether the file is a PDF, an image or a macro-free Office document as Windows
	 *         opens it: the name without
	 *         trailing dots and spaces (Windows drops them), no alternate data stream (a ':'
	 *         after the drive), not a UNC path
	 */
	public static boolean systemProgramAllowed(String path) {
		String p = path.replace('\\', '/');
		if (p.startsWith("//") || p.indexOf(':', p.length() > 1 && p.charAt(1) == ':' ? 2 : 0) >= 0) { //$NON-NLS-1$
			return false;
		}
		String name = p.substring(p.lastIndexOf('/') + 1).replaceAll("[. ]+$", ""); //$NON-NLS-1$ //$NON-NLS-2$
		int dot = name.lastIndexOf('.');
		return dot > 0 && SYSTEM_PROGRAM_TYPES.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
	}
}
