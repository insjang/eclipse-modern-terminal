/*******************************************************************************
 * Copyright (c) 2026 insjang and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package io.github.insjang.terminal.control;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * The working directory a shell reports with OSC 7 ({@code ESC ] 7 ; file://host/path ST}).
 *
 * @param host the host name as sent, "" when the URI has none
 * @param path the absolute path, percent-decoded as UTF-8
 */
public record TerminalDirectory(String host, String path) {
	private static final String FILE = "file://"; //$NON-NLS-1$

	/**
	 * Parses the URI of an OSC 7 sequence, or returns null when it is not a usable file URI.
	 * Everything after the host is the path: '?' and '#' are taken as part of it, since shells send
	 * directory names without escaping them.
	 */
	public static TerminalDirectory parse(String uri) {
		if (!uri.regionMatches(true, 0, FILE, 0, FILE.length())) {
			return null;
		}
		int slash = uri.indexOf('/', FILE.length());
		if (slash < 0) {
			return null;
		}
		String host = uri.substring(FILE.length(), slash);
		String path;
		try {
			// URLDecoder would make '+' a space: keep it
			path = URLDecoder.decode(uri.substring(slash).replace("+", "%2B"), StandardCharsets.UTF_8); //$NON-NLS-1$ //$NON-NLS-2$
		} catch (IllegalArgumentException e) {
			return null; // a '%' not followed by two hex digits
		}
		// no control characters, no bytes that were not UTF-8
		if ((host + path).chars().anyMatch(c -> c < 0x20 || c == 0x7f || c == 0xfffd)) {
			return null;
		}
		return new TerminalDirectory(host, path);
	}
}
