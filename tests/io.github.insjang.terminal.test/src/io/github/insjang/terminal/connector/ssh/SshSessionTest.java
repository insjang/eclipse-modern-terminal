/*******************************************************************************
 * Copyright (c) 2026 insjang and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package io.github.insjang.terminal.connector.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.insjang.terminal.connector.ssh.connector.SshSession;

public class SshSessionTest {
	@Test
	public void defaultNameShowsThePortOnlyWhenNot22() {
		assertEquals("me@h.example", SshSession.defaultName("me", "h.example", 22));
		assertEquals("me@h.example:2222", SshSession.defaultName("me", "h.example", 2222));
		assertEquals("h.example", SshSession.defaultName("", "h.example", 22));
	}

	@Test
	public void roundTripKeepsAwkwardValues() {
		// Windows path (':' and '\'), spaces, '|', a line break, non-ASCII, IPv6, nulls
		SshSession a = new SshSession("prod | 운영 db", "fe80::1", 2222, "me", "C:\\Users\\me\\.ssh\\id ed25519", 5, 0,
				"UTF-8", true, "/srv/it's here", null);
		SshSession b = new SshSession("b\nx", "h", 22, "u", null, 0, 300, null, false, null, "8080:localhost:80 9000:[::1]:9");
		List<SshSession> back = SshSession.decode(SshSession.encode(List.of(b, a)));
		assertEquals(List.of(b, a), back); // sorted by name: "b\nx" before "prod..."
	}

	@Test
	public void brokenLinesAreSkipped() {
		String good = SshSession.encode(List.of(new SshSession("n", "h", 22, "u", null, 0, 300, null, false, null, null)));
		assertEquals(1, SshSession.decode("junk\n" + good + "\na b c d e x 0 0 false\n").size());
		// a bad '%' escape drops only its line
		assertEquals(List.of("ok"), SshSession.decode("n%zz h 22 u  0 300  true\nok h 22 u  0 300  true").stream()
				.map(SshSession::name).toList());
		assertTrue(SshSession.decode("").isEmpty());
		assertTrue(SshSession.decode(null).isEmpty());
	}

	@Test
	public void linesWithoutTheStartFolderStillLoad() {
		// the 9 fields of a session saved before the start folder
		SshSession old = SshSession.decode("n h 22 u  0 300  true").get(0);
		assertEquals(new SshSession("n", "h", 22, "u", null, 0, 300, null, true, null, null), old);
		SshSession withFolder = new SshSession("n", "h", 22, "u", null, 0, 300, null, true, "~/a b", null);
		assertEquals(List.of(withFolder), SshSession.decode(SshSession.encode(List.of(withFolder))));
	}

	@Test
	public void linesWithoutForwardsStillLoad() {
		// the 10 fields of a session saved before the forwards
		assertEquals(new SshSession("n", "h", 22, "u", null, 0, 300, null, true, "/x", null),
				SshSession.decode("n h 22 u  0 300  true %2Fx").get(0));
	}

	@Test
	public void forwardsAreParsedLikeSshMinusL() {
		assertEquals(List.of(new SshSession.Forward(8080, "localhost", 80), new SshSession.Forward(5433, "db.lan", 5432),
				new SshSession.Forward(0, "::1", 22)), SshSession.parseForwards(" 8080:localhost:80, 5433:db.lan:5432  0:[::1]:22 "));
		assertEquals("9000:[::1]:9", new SshSession.Forward(9000, "::1", 9).toString());
		assertTrue(SshSession.parseForwards(null).isEmpty());
		assertTrue(SshSession.parseForwards("  , ").isEmpty());
		for (String bad : new String[] { "8080", "8080:80", "x:h:80", "8080:h:0", "70000:h:80", "8080::80", "1:fe80::1:22",
				"1:[h:2", "-1:h:2" }) {
			IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> SshSession.parseForwards("1:h:2 " + bad));
			assertEquals(bad, e.getMessage());
		}
	}

	@Test
	public void startCommandQuotesTheFolderForSh() {
		// the login shell (sh, csh, fish) only starts sh, with the folder as its argument
		String sh = "exec sh -c 'case $1 in \"~\") set -- \"$HOME\";; \"~/\"*) set -- \"$HOME/${1#??}\";; esac; "
				+ "cd \"$1\"; exec \"$SHELL\" -l' sh ";
		assertEquals(sh + "'/srv/a b'", SshSession.startCommand("/srv/a b"));
		assertEquals(sh + "'/x/it'\\''s'", SshSession.startCommand("/x/it's"));
		assertEquals(sh + "'/x/a'\\!'b'", SshSession.startCommand("/x/a!b")); // csh history even in quotes
		assertEquals("'$(rm -rf ~)`x`;\"'", SshSession.quote("$(rm -rf ~)`x`;\""));
		// the home: sh puts $HOME for "~" and "~/"
		assertEquals(sh + "'~/my proj'", SshSession.startCommand("~/my proj"));
		assertEquals(sh + "'~'", SshSession.startCommand("~"));
		assertEquals(sh + "'./-x'", SshSession.startCommand("-x"));
		assertEquals("'~user'", SshSession.quote("~user"));
	}

	@Test
	public void sameLoginIsUserHostAndPort() {
		SshSession a = new SshSession("a", "h", 22, "u", null, 0, 300, null, true, null, null);
		assertTrue(a.sameLogin(new SshSession("b", "h", 22, "u", "k", 9, 9, "x", false, null, null)));
		assertFalse(a.sameLogin(new SshSession("a", "h", 2222, "u", null, 0, 300, null, true, null, null)));
		assertFalse(a.sameLogin(new SshSession("a", "h", 22, "v", null, 0, 300, null, true, null, null)));
	}
}
