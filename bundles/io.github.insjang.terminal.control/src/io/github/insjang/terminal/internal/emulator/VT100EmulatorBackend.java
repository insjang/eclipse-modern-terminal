/*******************************************************************************
 * Copyright (c) 2007, 2018 Wind River Systems, Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * Contributors:
 * Michael Scharf (Wind River) - initial API and implementation
 * Anton Leherbauer (Wind River) - [206329] Changing terminal size right after connect does not scroll properly
 * Anton Leherbauer (Wind River) - [433751] Add option to enable VT100 line wrapping mode
 * Anton Leherbauer (Wind River) - [458218] Add support for ANSI insert mode
 * Anton Leherbauer (Wind River) - [458402] Add support for scroll up/down and scroll region
 *******************************************************************************/
package io.github.insjang.terminal.internal.emulator;

import java.text.BreakIterator;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.insjang.terminal.internal.model.CharWidth;
import io.github.insjang.terminal.model.ITerminalTextData;
import io.github.insjang.terminal.model.ITerminalTextDataReadOnly;
import io.github.insjang.terminal.internal.model.TerminalTextDataStore;
import io.github.insjang.terminal.model.TerminalStyle;

/**
 * @noextend This class is not intended to be subclassed by clients.
 * @noreference This class not intended to be referenced by clients.
 *      It used to be package protected, and it is public only for Unit Tests.
 *
 */
public class VT100EmulatorBackend implements IVT100EmulatorBackend {

	private static class ScrollRegion {
		static final ScrollRegion FULL_WINDOW = new ScrollRegion(0, Integer.MAX_VALUE - 1);
		private final int fTop;
		private final int fBottom;

		ScrollRegion(int top, int bottom) {
			fTop = top;
			fBottom = bottom;
		}

		boolean contains(int line) {
			return line >= fTop && line <= fBottom;
		}

		int getTopLine() {
			return fTop;
		}

		int getBottomLine() {
			return fBottom;
		}

		int getHeight() {
			return fBottom - fTop + 1;
		}
	}

	/**
	 * This field holds the number of the column in which the cursor is
	 * logically positioned. The leftmost column on the screen is column 0, and
	 * column numbers increase to the right. The maximum value of this field is
	 * {@link #widthInColumns} - 1. We track the cursor column using this field
	 * to avoid having to recompute it repeatly using StyledText method calls.
	 * <p>
	 *
	 * The StyledText widget that displays text has a vertical bar (called the
	 * "caret") that appears _between_ character cells, but ANSI terminals have
	 * the concept of a cursor that appears _in_ a character cell, so we need a
	 * convention for which character cell the cursor logically occupies when
	 * the caret is physically between two cells. The convention used in this
	 * class is that the cursor is logically in column N when the caret is
	 * physically positioned immediately to the _left_ of column N.
	 * <p>
	 *
	 * When fCursorColumn is N, the next character output to the terminal appears
	 * in column N. When a character is output to the rightmost column on a
	 * given line (column widthInColumns - 1), the cursor moves to column 0 on
	 * the next line after the character is drawn (this is the default line wrapping
	 * mode). If VT100 line wrapping mode is enabled, the cursor does not move
	 * to the next line until the next character is printed (this is known as
	 * the VT100 'eat_newline_glitch').
	 * If the cursor is in the bottommost line when line wrapping
	 * occurs, the topmost visible line is scrolled off the top edge of the
	 * screen.
	 * <p>
	 */
	private int fCursorColumn;
	private int fCursorLine;
	/* true if last output occurred on rightmost column
	 * and next output requires line wrap */
	private boolean fWrapPending;
	private boolean fInsertMode;
	private TerminalStyle fDefaultStyle;
	private TerminalStyle fStyle;
	int fLines;
	int fColumns;
	final private ITerminalTextData fTerminal;

	/**
	 * What the normal screen held while the alternate one is showing, and null the
	 * rest of the time, which is also how we know which screen we are on.
	 */
	private ITerminalTextData fNormalScreen;
	private int fNormalMaxHeight;
	/** in the normal buffer, not on the screen: the screen may be resized meanwhile */
	private int fNormalCursorLine;
	private int fNormalCursorColumn;
	/** the screen the normal buffer was saved from, and whether a wrap was pending on it */
	private int fNormalLines;
	private int fNormalColumns;
	private boolean fNormalWrapPending;
	/** the rendition the shell had selected: 1049 saves it with the cursor, as DECSC does */
	private TerminalStyle fNormalStyle;
	private boolean fVT100LineWrapping;
	private ScrollRegion fScrollRegion = ScrollRegion.FULL_WINDOW;
	/** the line of the last prompt (OSC 133;A) when it was marked, -1 for none; lines only move up since */
	private int fPromptLine = -1;
	/** an output mark (C) came after that prompt and no D yet */
	private boolean fCommandOpen;
	/** the hyperlink (OSC 8) the text written now gets, or null */
	private String fLink;
	/** Most different hyperlinks held at once: each is kept once, the cells share it. */
	public static final int MAX_LINKS = 1000;
	// the links in use, each once; a link not in it is never put on a cell
	private final Map<String, String> fLinks = new HashMap<>();
	// when the links no cell has were last let go: a walk over the buffer, at most twice a second
	private long fLinksDropped = Long.MIN_VALUE / 2;

	public VT100EmulatorBackend(ITerminalTextData terminal) {
		fTerminal = terminal;
	}

	@Override
	public void enableAlternateScreen(boolean enable, boolean cursor) {
		synchronized (fTerminal) {
			if (enable == (fNormalScreen != null)) {
				// Already on the screen being asked for. Programs do ask twice.
				return;
			}
			if (enable) {
				fNormalScreen = new TerminalTextDataStore();
				fNormalScreen.copy(fTerminal);
				fNormalCursorLine = toAbsoluteLine(fCursorLine);
				fNormalCursorColumn = fCursorColumn;
				fNormalLines = fLines;
				fNormalColumns = fColumns;
				fNormalWrapPending = fWrapPending;
				fNormalStyle = fStyle;
				fNormalMaxHeight = fTerminal.getMaxHeight();
				// clearing leaves the buffer the size of the screen, which is what the
				// alternate screen is: no history to scroll back through. Capping the
				// buffer there keeps it so: scrolling drops the top line instead of
				// growing the buffer, as a program on this screen expects.
				clearBuffer();
				fTerminal.setMaxHeight(fLines);
				// the rendition carries over: clearing resets it, entering does not
				setStyle(fNormalStyle);
				if (!cursor) {
					// 47 and 1047 move no cursor: it stays on its row and column, a
					// wrap pending there still pending
					setCursor(fNormalCursorLine - (fNormalScreen.getHeight() - fLines), fNormalCursorColumn);
					fWrapPending = fNormalWrapPending;
				}
			} else {
				restoreNormalScreen(cursor);
			}
		}
	}

	/**
	 * Puts the normal screen back as if it had been resized along with the alternate
	 * one: the window may have changed while the program had it, and the buffer put
	 * back is the one from before. Narrower, and every write past its margin throws;
	 * shorter than the screen, and the top of the screen sits above its first line.
	 */
	private void restoreNormalScreen(boolean cursorBack) {
		// where the program left the cursor, for the modes that keep it there
		int programLine = fCursorLine;
		int programColumn = fCursorColumn;
		boolean programWrapPending = fWrapPending;
		TerminalStyle programStyle = fStyle;
		ITerminalTextData saved = fNormalScreen;
		fNormalScreen = null;
		// the margins were the full screen program's, not the shell's
		fScrollRegion = ScrollRegion.FULL_WINDOW;
		int height = saved.getHeight();
		int cursor = fNormalCursorLine; // a line of the saved buffer
		// the height setDimensions would give the normal screen: at least the screen,
		// and with no history, the rows below the cursor go when the screen shrinks
		int newHeight = Math.max(fLines, height);
		if (fLines < fNormalLines && height == fNormalLines) {
			newHeight = Math.max(fLines, cursor + 1);
		}
		fTerminal.setMaxHeight(Math.max(fTerminal.getMaxHeight(), Math.max(height, newHeight)));
		fTerminal.copy(saved);
		fTerminal.setDimensions(newHeight, fColumns);
		// rows the buffer gains are blank: they may still hold what the program drew there
		for (int line = height; line < newHeight; line++) {
			fTerminal.cleanLine(line);
		}
		// a limit lowered meanwhile takes the oldest lines, never the screen
		int limit = Math.max(fNormalMaxHeight, fLines);
		int excess = newHeight - limit;
		if (excess > 0) {
			fTerminal.scroll(0, newHeight, -excess);
			fTerminal.setDimensions(limit, fColumns);
			cursor -= excess;
		}
		fTerminal.setMaxHeight(Math.max(limit, fTerminal.getHeight()));
		// back to the line the cursor was on, wherever it is on the screen now
		if (!cursorBack) {
			// 47 and 1047 save no cursor: it stays on the row and column the program left it
			setCursor(programLine, programColumn);
			fWrapPending = programWrapPending && fCursorColumn == fColumns - 1;
			setStyle(programStyle);
			return;
		}
		setCursor(cursor - (fTerminal.getHeight() - fLines), fNormalCursorColumn);
		// and to a wrap that was pending there, if the line is as wide as it was
		fWrapPending = fNormalWrapPending && fColumns == fNormalColumns && fCursorColumn == fColumns - 1;
		// in the colours the shell was writing in, not the ones the program left selected
		setStyle(fNormalStyle);
	}

	@Override
	public int getBufferLineLimit() {
		synchronized (fTerminal) {
			// while the alternate screen shows, the model's limit is that screen's height
			return fNormalScreen != null ? fNormalMaxHeight : fTerminal.getMaxHeight();
		}
	}

	@Override
	public void setBufferLineLimit(int bufferLineLimit) {
		if (bufferLineLimit <= 0) {
			return;
		}
		synchronized (fTerminal) {
			if (fNormalScreen != null) {
				// the alternate screen keeps its cap; the normal screen gets the limit back
				fNormalMaxHeight = bufferLineLimit;
				return;
			}
			if (fTerminal.getHeight() > bufferLineLimit) {
				fTerminal.setDimensions(bufferLineLimit, fTerminal.getWidth());
			}
			fTerminal.setMaxHeight(bufferLineLimit);
		}
	}

	@Override
	public void clearAll() {
		synchronized (fTerminal) {
			if (fNormalScreen != null) {
				// Clearing while a full screen program shows clears what is behind it as
				// well, or leaving the program would bring back what the user cleared:
				// a blank normal screen, the cursor on the row of it where it was.
				int row = fNormalCursorLine - (fNormalScreen.getHeight() - fNormalLines);
				fNormalScreen = new TerminalTextDataStore();
				fNormalScreen.setDimensions(fNormalLines, fNormalColumns);
				fNormalCursorLine = Math.max(0, Math.min(row, fNormalLines - 1));
				fNormalWrapPending = false;
			}
			clearBuffer();
		}
	}

	private void clearBuffer() {
		synchronized (fTerminal) {
			// clear the history
			int n = fTerminal.getHeight();
			for (int line = 0; line < n; line++) {
				fTerminal.cleanLine(line);
			}
			fTerminal.setDimensions(fLines, fTerminal.getWidth());
			setStyle(getDefaultStyle());
			setCursor(0, 0);
		}
	}

	@Override
	public void setDimensions(int lines, int cols) {
		synchronized (fTerminal) {
			if (lines == fLines && cols == fColumns) {
				return; // nothing to do
			}
			// relative cursor line
			int cl = getCursorLine();
			int cc = getCursorColumn();
			if (fNormalScreen != null) {
				// The alternate screen has no history, so its buffer stays exactly as
				// high as the screen. On a shorter screen the lines above the cursor go
				// first, as many as it takes to keep the cursor on it; the cap follows
				// once the buffer is no higher than it, which the buffer requires.
				int drop = Math.max(0, cl + 1 - lines);
				if (drop > 0) {
					fTerminal.scroll(0, fTerminal.getHeight(), -drop);
				}
				fLines = lines;
				fColumns = cols;
				fTerminal.setDimensions(lines, cols);
				fTerminal.setMaxHeight(lines);
				setCursor(cl - drop, cc);
				return;
			}
			int height = fTerminal.getHeight();
			// absolute cursor line
			int acl = cl + height - fLines;
			int newLines = Math.max(lines, height);
			if (lines < fLines) {
				if (height == fLines) {
					// if the terminal has no history, then resize by
					// setting the size to the new size
					// TODO We are assuming that cursor line points at end of text
					newLines = Math.max(lines, cl + 1);
				}
			}
			fLines = lines;
			fColumns = cols;
			// make the terminal at least as high as we need lines
			fTerminal.setDimensions(newLines, fColumns);
			// compute relative cursor line
			cl = acl - (newLines - fLines);
			setCursor(cl, cc);
		}
	}

	/**
	 * @nooverride This method is not intended to be re-implemented or extended by clients.
	 * @noreference This method is not intended to be referenced by clients.
	 *      It used to be package protected, and it is public only for Unit Tests.
	 */
	public int toAbsoluteLine(int line) {
		synchronized (fTerminal) {
			return fTerminal.getHeight() - fLines + line;
		}
	}

	@Override
	public void insertCharacters(int charactersToInsert) {
		if (charactersToInsert <= 0) {
			return; // nothing moves, so no wide character may be broken either
		}
		synchronized (fTerminal) {
			int line = toAbsoluteLine(fCursorLine);
			int n = charactersToInsert;
			// A wide character is not left half on the line: one the insertion splits
			// is blanked whole, and so is one whose second cell it pushes off the end.
			if (isSecondCell(line, fCursorColumn)) {
				eraseCells(line, fCursorColumn - 1, fCursorColumn + 1);
			}
			if (fCursorColumn + n < fColumns && isSecondCell(line, fColumns - n)) {
				eraseCells(line, fColumns - n - 1, fColumns - n);
			}
			Map<Integer, String> clusters = clustersIn(line, fCursorColumn, fColumns - n);
			for (int col = fColumns - 1; col >= fCursorColumn + n; col--) {
				char c = fTerminal.getChar(line, col - n);
				TerminalStyle style = fTerminal.getStyle(line, col - n);
				fTerminal.setChar(line, col, c, style);
			}
			eraseCells(line, fCursorColumn, Math.min(fCursorColumn + n, fColumns));
			clusters.forEach((col, cluster) -> fTerminal.setCluster(line, col + n, cluster));
		}
	}

	@Override
	public void eraseToEndOfScreen() {
		synchronized (fTerminal) {
			eraseLineToEnd();
			for (int line = toAbsoluteLine(fCursorLine + 1); line < toAbsoluteLine(fLines); line++) {
				eraseLine(line);
			}
		}

	}

	@Override
	public void eraseToCursor() {
		synchronized (fTerminal) {
			for (int line = toAbsoluteLine(0); line < toAbsoluteLine(fCursorLine); line++) {
				eraseLine(line);
			}
			eraseLineToCursor();
		}
	}

	@Override
	public void eraseAll() {
		synchronized (fTerminal) {
			// What is on the screen goes into the history before it is erased, as in
			// Windows Terminal and VTE, so that clearing the screen does not destroy
			// what the user was reading. It matters on Windows, where ConPTY clears
			// the screen at the start of every connection. Blank lines at the bottom
			// of the screen are left out. Where the buffer is capped at the screen,
			// as on an alternate screen, the lines simply go.
			int used = fLines;
			while (used > 0 && isBlank(toAbsoluteLine(used - 1))) {
				used--;
			}
			// but not the row a line runs on to: the history ends where a line ends
			while (used > 0 && used < fLines && fTerminal.isWrappedLine(toAbsoluteLine(used - 1))) {
				used++;
			}
			for (int i = 0; i < used; i++) {
				fTerminal.addLine();
			}
			if (used > 0) {
				// the screen moved down the buffer: the cursor stays on its row of it
				setCursorLine(fCursorLine);
			}
			for (int line = toAbsoluteLine(0); line < toAbsoluteLine(fLines); line++) {
				eraseLine(line);
			}
		}
	}

	private boolean isBlank(int line) {
		char[] chars = fTerminal.getChars(line);
		if (chars != null) {
			for (char c : chars) {
				if (c != '\000' && c != ' ') {
					return false;
				}
			}
		}
		return true;
	}

	@Override
	public void eraseLine() {
		synchronized (fTerminal) {
			// a shell redrawing its prompt line keeps the prompt's mark
			int line = toAbsoluteLine(fCursorLine);
			int mark = fTerminal.getLineMark(line);
			eraseLine(line);
			fTerminal.setLineMark(line, mark);
		}
	}

	/**
	 * The link the text written from now on gets. A program can only make the buffer
	 * hold {@link #MAX_LINKS} different ones: past that, the ones no cell has any more
	 * are let go (looked for at most twice a second), and while it is still full new
	 * links are left off.
	 */
	@Override
	public void setLink(String uri) {
		synchronized (fTerminal) {
			if (uri == null) {
				fLink = null;
				return;
			}
			String known = fLinks.get(uri);
			if (known == null && fLinks.size() >= MAX_LINKS && System.nanoTime() - fLinksDropped > 500_000_000L) {
				fLinksDropped = System.nanoTime();
				dropUnusedLinks();
			}
			if (known == null && fLinks.size() < MAX_LINKS) {
				fLinks.put(uri, uri);
				known = uri;
			}
			fLink = known;
		}
	}

	private void dropUnusedLinks() {
		Set<String> used = Collections.newSetFromMap(new IdentityHashMap<>());
		for (ITerminalTextData data : fNormalScreen != null ? List.of(fTerminal, fNormalScreen) : List.of(fTerminal)) {
			for (int line = 0; line < data.getHeight(); line++) {
				char[] chars = data.getChars(line);
				for (int col = 0; chars != null && col < chars.length; col++) {
					String link = data.getLink(line, col);
					if (link != null) {
						used.add(link);
					}
				}
			}
		}
		fLinks.values().removeIf(link -> !used.contains(link));
	}

	@Override
	public void setShellMark(char command, int exitStatus) {
		synchronized (fTerminal) {
			int line = toAbsoluteLine(fCursorLine);
			switch (command) {
			case 'A':
				// an output mark stays: the command before printed nothing, its output ends here
				fTerminal.setLineMark(line, ITerminalTextDataReadOnly.MARK_PROMPT
						| fTerminal.getLineMark(line) & ITerminalTextDataReadOnly.MARK_OUTPUT);
				fPromptLine = line;
				fCommandOpen = false;
				break;
			case 'C':
				fTerminal.setLineMark(line, fTerminal.getLineMark(line) | ITerminalTextDataReadOnly.MARK_OUTPUT);
				fCommandOpen = fPromptLine >= 0;
				break;
			case 'D':
				// only once per command (Enter on an empty line runs nothing, a repeated D is ignored)
				if (!fCommandOpen) {
					break;
				}
				fCommandOpen = false;
				// the prompt has moved up by the lines dropped from the top since: the first prompt
				// at or above where it was is it, so the walk is as long as that move (linear in the output)
				int l = Math.min(fPromptLine, fTerminal.getHeight() - 1);
				while (l >= 0 && (fTerminal.getLineMark(l) & ITerminalTextDataReadOnly.MARK_PROMPT) == 0) {
					l--;
				}
				fPromptLine = l; // -1 when it has gone: later commands are not walked for
				if (l >= 0) {
					int mark = fTerminal.getLineMark(l)
							& ~(ITerminalTextDataReadOnly.MARK_FAILED | ITerminalTextDataReadOnly.MARK_SUCCEEDED);
					fTerminal.setLineMark(l, mark | (exitStatus != 0 ? ITerminalTextDataReadOnly.MARK_FAILED
							: ITerminalTextDataReadOnly.MARK_SUCCEEDED));
				}
				break;
			default:
				break;
			}
		}
	}

	private void eraseLine(int line) {
		fWrapPending = false; // also when there is nothing to fill
		fWrappedJustNow = false;
		fLead = null;
		fTerminal.cleanLine(line);
		if (eraseStyle() != null) {
			eraseCells(line, 0, fColumns);
		}
	}

	@Override
	public void eraseLineToEnd() {
		synchronized (fTerminal) {
			int line = toAbsoluteLine(fCursorLine);
			eraseWhole(line, fCursorColumn, fColumns);
			// Nothing is left out to the margin, so the line ends where the cursor is.
			fTerminal.clearWrappedLine(line);
		}
	}

	/**
	 * Erasing either half of a wide character erases all of it, in the same
	 * background as the rest of the range.
	 */
	private void eraseWhole(int line, int from, int to) {
		if (isSecondCell(line, from)) {
			from--;
		}
		if (isSecondCell(line, to)) {
			to++;
		}
		eraseCells(line, from, to);
	}

	/**
	 * Erasing leaves behind the background a program is writing in, not the one the
	 * terminal started with (xterm's "back color erase", which every terminal that
	 * supports colour implements). Colouring a run of a line by setting a background
	 * and erasing to the end of it is how a program paints anything wider than the
	 * characters it has to put there, and without this such a line keeps the
	 * terminal's own colour from its last character on. xterm applies this to every
	 * erase (EL, ED, ECH) and to the cells insertion and deletion uncover (ICH, DCH).
	 */
	private void eraseCells(int line, int from, int to) {
		fWrapPending = false; // erasing, inserting and deleting all forget a pending wrap
		fWrappedJustNow = false;
		fLead = null;
		if (from < to) {
			fTerminal.setChars(line, from, new char[to - from], eraseStyle());
		}
	}

	/**
	 * @return the style erased cells take: only the background carries over, what is
	 *         erased is not bold or underlined; null, as a cell that was never
	 *         written, when that background is the default one
	 */
	private TerminalStyle eraseStyle() {
		TerminalStyle plain = getDefaultStyle();
		if (plain == null || fStyle == null) {
			return fStyle;
		}
		TerminalStyle erased = plain.setBackground(fStyle);
		return erased.equals(plain) ? null : erased;
	}

	@Override
	public void eraseLineToCursor() {
		synchronized (fTerminal) {
			eraseWhole(toAbsoluteLine(fCursorLine), 0, fCursorColumn + 1);
		}
	}

	@Override
	public void insertLines(int n) {
		synchronized (fTerminal) {
			if (!isCusorInScrollingRegion()) {
				return;
			}
			assert n > 0;
			int line = toAbsoluteLine(fCursorLine);
			int nLines = Math.min(fTerminal.getHeight() - line, fScrollRegion.getBottomLine() - fCursorLine + 1);
			fTerminal.scroll(line, nLines, n);
		}
	}

	@Override
	public void deleteCharacters(int n) {
		if (n <= 0) {
			return; // nothing moves, so no wide character may be broken either
		}
		synchronized (fTerminal) {
			int line = toAbsoluteLine(fCursorLine);
			// deleting either half of a wide character leaves none of it
			int end = Math.min(fCursorColumn + n, fColumns);
			if (isSecondCell(line, fCursorColumn)) {
				eraseCells(line, fCursorColumn - 1, fCursorColumn);
			}
			if (isSecondCell(line, end)) {
				eraseCells(line, end, end + 1);
			}
			Map<Integer, String> clusters = clustersIn(line, fCursorColumn + n, fColumns);
			for (int col = fCursorColumn + n; col < fColumns; col++) {
				char c = fTerminal.getChar(line, col);
				TerminalStyle style = fTerminal.getStyle(line, col);
				fTerminal.setChar(line, col - n, c, style);
			}
			eraseCells(line, Math.max(fCursorColumn, fColumns - n), fColumns);
			clusters.forEach((col, cluster) -> fTerminal.setCluster(line, col - n, cluster));
		}
	}

	@Override
	public void deleteLines(int n) {
		synchronized (fTerminal) {
			if (!isCusorInScrollingRegion()) {
				return;
			}
			assert n > 0;
			int line = toAbsoluteLine(fCursorLine);
			int nLines = Math.min(fTerminal.getHeight() - line, fScrollRegion.getBottomLine() - fCursorLine + 1);
			fTerminal.scroll(line, nLines, -n);
		}
	}

	private boolean isCusorInScrollingRegion() {
		return fScrollRegion.contains(fCursorLine);
	}

	@Override
	public TerminalStyle getDefaultStyle() {
		synchronized (fTerminal) {
			return fDefaultStyle;
		}
	}

	@Override
	public void setDefaultStyle(TerminalStyle defaultStyle) {
		synchronized (fTerminal) {
			fDefaultStyle = defaultStyle;
		}
	}

	@Override
	public TerminalStyle getStyle() {
		synchronized (fTerminal) {
			if (fStyle == null) {
				return fDefaultStyle;
			}
			return fStyle;
		}
	}

	@Override
	public void setStyle(TerminalStyle style) {
		synchronized (fTerminal) {
			fStyle = style;
		}
	}

	@Override
	public void appendString(String buffer) {
		synchronized (fTerminal) {
			char[] chars = buffer.toCharArray();
			int line = toAbsoluteLine(fCursorLine);
			// zero width code points a cluster starts with (a prefix sign, a stray mark),
			// kept for the character after them in the cluster, which has cells to keep them
			// in; the last write may have left some for this one, written where it ended
			String lead = fLead != null && fLeadLine == line && fLeadColumn == fCursorColumn ? fLead : null;
			fLead = null;
			boolean[] starts = clusterStarts(lead != null ? lead : clusterBefore(line), buffer);
			int i = 0;
			while (i < chars.length) {
				int codePoint = Character.codePointAt(chars, i);
				int charsUsed = Character.charCount(codePoint);
				int width = CharWidth.of(codePoint);
				// What goes on with the cluster before it goes onto that, even one on the
				// last cell with a wrap pending: it belongs there, not on the next line.
				if (starts[i]) {
					lead = null;
				}
				if (!starts[i] && lead == null && continuesCluster(line, codePoint)) {
					line = toAbsoluteLine(fCursorLine); // a cluster grown wide may have wrapped
					i += charsUsed;
					continue;
				}
				if (width == 0) {
					// no cell of its own, and none before it to go onto
					if (lead == null || lead.length() < MAX_CLUSTER) {
						lead = (lead == null ? "" : lead) + Character.toString(codePoint); //$NON-NLS-1$
					}
					i += charsUsed;
					continue;
				}
				if (fWrapPending) {
					line = doLineWrap();
				}
				int room = fColumns - fCursorColumn;
				int col;
				int n = narrowRun(chars, starts, i, room);
				if (n > 0) {
					if (fInsertMode) {
						// room on the line the run goes to, which a wrap may have changed
						insertCharacters(n);
					}
					breakWideChar(line, fCursorColumn);
					breakWideChar(line, fCursorColumn + n - 1);
					fTerminal.setChars(line, fCursorColumn, chars, i, n, fStyle);
					col = fCursorColumn + n;
					i += n;
				} else {
					width = CharWidth.cells(codePoint);
					if (width > room) {
						if (fCursorColumn > 0) {
							// a wide character is never split across the right margin,
							// and the cell it leaves unused there is erased
							eraseCharacters(room);
							line = doLineWrap();
							continue;
						}
						// terminal narrower than the character itself
						width = room;
					}
					if (fInsertMode) {
						insertCharacters(width); // room in cells, not characters
					}
					breakWideChar(line, fCursorColumn);
					breakWideChar(line, fCursorColumn + width - 1);
					if (charsUsed > width) {
						// a one column terminal: the two chars of a surrogate pair cannot share its cell
						fTerminal.setChar(line, fCursorColumn, '\uFFFD', fStyle);
					} else if (charsUsed == 2) {
						fTerminal.setChars(line, fCursorColumn, chars, i, 2, fStyle);
					} else {
						fTerminal.setChar(line, fCursorColumn, chars[i], fStyle);
						if (width == 2) {
							fTerminal.setChar(line, fCursorColumn + 1, '\000', fStyle);
						}
					}
					if (lead != null && CharWidth.clusterCells(lead + Character.toString(codePoint)) == width) {
						fTerminal.setCluster(line, fCursorColumn, lead + Character.toString(codePoint));
					}
					lead = null;
					col = fCursorColumn + width;
					i += charsUsed;
				}
				if (fLink != null) {
					for (int c = fCursorColumn; c < col; c++) {
						fTerminal.setLink(line, c, fLink);
					}
				}
				line = advance(col);
			}
			if (lead != null) {
				fLead = lead;
				fLeadLine = line;
				fLeadColumn = fCursorColumn;
			}
		}
	}

	/** Longest cluster kept, in chars; the longest emoji sequences are about 15. */
	private static final int MAX_CLUSTER = 32;

	/**
	 * Zero width code points left over from the last write, and where it ended;
	 * any cursor move or erase forgets them.
	 */
	private String fLead;
	private int fLeadLine, fLeadColumn;
	/**
	 * What was just written ended on the last cell and the cursor moved on at once
	 * (immediate wrap): the character before the cursor is at the end of the line above.
	 */
	private boolean fWrappedJustNow;

	/**
	 * Moves the cursor past what was just written, which ends before <code>col</code>.
	 *
	 * @return the line the cursor is now on
	 */
	private int advance(int col) {
		if (col == fColumns) {
			if (fVT100LineWrapping) {
				// deferred line wrapping (eat_newline_glitch)
				setCursorColumn(col - 1);
				fWrapPending = true;
			} else {
				int line = doLineWrap();
				fWrappedJustNow = true;
				return line;
			}
		} else {
			// Writing that stops short of the margin says the line ends here.
			// A program that draws its own screen writes the same row again and
			// again, and a row that was folded in one frame is a row of its own
			// in the next, so the mark has to be able to come off.
			fTerminal.clearWrappedLine(toAbsoluteLine(fCursorLine));
			setCursorColumn(col);
		}
		return toAbsoluteLine(fCursorLine);
	}

	/** finds where a grapheme cluster ends, by the rules of UAX #29 */
	private final BreakIterator fGraphemes = BreakIterator.getCharacterInstance();

	/**
	 * Where a grapheme cluster starts in the text, by the Unicode rules (UAX #29),
	 * the cluster before the cursor going first as the context the text goes on
	 * from.
	 *
	 * @return for each char of the text, whether a cluster starts there
	 */
	private boolean[] clusterStarts(String before, String text) {
		boolean[] starts = new boolean[text.length()];
		if (before.isEmpty() || before.charAt(before.length() - 1) < 0x300) {
			if (text.chars().allMatch(c -> c < 0x300)) {
				// no rule joins two of these, and most text is made of nothing else
				Arrays.fill(starts, true);
				return starts;
			}
		}
		fGraphemes.setText(before + text);
		for (int b = fGraphemes.following(Math.max(before.length() - 1, 0)); b != BreakIterator.DONE; b = fGraphemes.next()) {
			if (b >= before.length() && b - before.length() < starts.length) {
				starts[b - before.length()] = true;
			}
		}
		if (before.isEmpty() && starts.length > 0) {
			starts[0] = true;
		}
		return starts;
	}

	/** @return the line of the character before the cursor: the one above right after an immediate wrap */
	private int lineBefore(int line) {
		return fWrappedJustNow && fCursorLine > 0 ? toAbsoluteLine(fCursorLine - 1) : line;
	}

	/** @return the first cell of the character before the cursor, on {@link #lineBefore(int)}, or -1 */
	private int columnBefore(int line) {
		int col = (fWrapPending || fWrappedJustNow && fCursorLine > 0 ? fColumns : fCursorColumn) - 1;
		return isSecondCell(lineBefore(line), col) ? col - 1 : col;
	}

	/** @return the grapheme cluster in the cells before the cursor, or "" when nothing was written there */
	private String clusterBefore(int line) {
		int col = columnBefore(line);
		line = lineBefore(line);
		if (col < 0) {
			return ""; //$NON-NLS-1$
		}
		char first = fTerminal.getChar(line, col);
		String cluster = fTerminal.getCluster(line, col);
		if (cluster != null) {
			return cluster;
		}
		if (Character.isHighSurrogate(first)) {
			return new String(new char[] { first, fTerminal.getChar(line, col + 1) });
		}
		return first == '\000' ? "" : String.valueOf(first); //$NON-NLS-1$
	}

	/**
	 * A grapheme cluster is what a reader takes for one character, however many code
	 * points it runs to: a letter with its accents, a Hangul syllable sent as jamo,
	 * an emoji with a skin tone or joined to others, a flag. Such a cluster takes
	 * the cells its code points would, but no more than two, summed as Windows
	 * Terminal sums them: a mark takes none, and VS16 (emoji presentation) makes it wide. A
	 * code point that takes cells of its own and leaves that count as it is, such
	 * as an Indic vowel sign, is simply written into them, where it is drawn; not a
	 * part of an emoji, though, since a selector or a joiner after it may still
	 * change the count. The rest goes onto the cluster: its cells keep the first
	 * character, which is what gets drawn, and the whole cluster is kept beside
	 * them for copying, as the program wrote it. The character drawn is composed
	 * with the marks (NFC) where they have a composed form, which is how an
	 * accent, Hangul jamo or a Japanese voicing mark show in one cell.
	 *
	 * @param codePoint one that goes on with the cluster before the cursor, by UAX #29
	 * @return whether it went onto that cluster
	 */
	private boolean continuesCluster(int line, int codePoint) {
		String cluster = clusterBefore(line);
		if (cluster.isEmpty()) {
			return false;
		}
		if (cluster.length() >= MAX_CLUSTER) {
			// a flood of marks must not grow one cell without bound: what takes no cell is dropped
			return CharWidth.cells(codePoint) == 0;
		}
		int col = columnBefore(line);
		int before = lineBefore(line);
		String joined = cluster + Character.toString(codePoint);
		int cells = CharWidth.clusterCells(cluster);
		int grown = CharWidth.clusterCells(joined);
		if (CharWidth.cells(codePoint) > 0 && grown == cells + CharWidth.cells(codePoint)
				&& !Character.isExtendedPictographic(codePoint) && !Character.isEmojiComponent(codePoint)) {
			return false; // written into cells of its own, it is drawn, and the columns come out the same
		}
		if (grown > cells && fColumns < 2) {
			return false; // no second cell to grow into
		}
		TerminalStyle style = fTerminal.getStyle(before, col);
		// the cell gets what is drawn: the character the marks compose with where they
		// have a composed form (NFC); the cluster keeps what the program wrote
		int base = Normalizer.normalize(joined, Normalizer.Form.NFC).codePointAt(0);
		if (grown == cells) {
			fTerminal.setChars(before, col, Character.toChars(base), 0, Character.charCount(base), style);
			fTerminal.setCluster(before, col, joined);
			return true;
		}
		if (fWrapPending || before != line) {
			// on the last cell: a wide character is never split across the margin, so
			// the cluster moves whole to the next line, the cell it leaves erased
			boolean wrap = fWrapPending;
			eraseCells(before, col, col + 1);
			if (wrap) {
				line = doLineWrap();
			}
			col = 0;
			if (fInsertMode) {
				insertCharacters(2);
			}
			breakWideChar(line, col);
		} else if (fInsertMode) {
			insertCharacters(1); // the second cell is room made, not a cell taken from what follows
		}
		breakWideChar(line, col + 1);
		fTerminal.setChar(line, col + 1, '\000', style);
		fTerminal.setChars(line, col, Character.toChars(base), 0, Character.charCount(base), style);
		fTerminal.setCluster(line, col, joined);
		// moved past only once it is all written: a wrap here may scroll the line away
		advance(col + 2);
		return true;
	}

	/**
	 * A wide character owns two cells. Overwriting either one leaves the other
	 * stranded: a filler with nothing in front of it, or a glyph that now spills
	 * over whatever was written next to it. Blanking the partner before the write
	 * goes in keeps the line honest, which is what a terminal is expected to do.
	 */
	private void breakWideChar(int line, int col) {
		if (col < 0 || col >= fColumns) {
			return;
		}
		char c = fTerminal.getChar(line, col);
		if (Character.isLowSurrogate(c)) {
			// second cell of a character beyond the BMP
			if (col > 0 && Character.isHighSurrogate(fTerminal.getChar(line, col - 1))) {
				blank(line, col - 1);
			}
		} else if (Character.isHighSurrogate(c)) {
			if (col + 1 < fColumns && Character.isLowSurrogate(fTerminal.getChar(line, col + 1))) {
				blank(line, col + 1);
			}
		} else if (c == '\000') {
			if (col > 0 && (CharWidth.of(fTerminal.getChar(line, col - 1)) == 2 || isWideCluster(line, col - 1))) {
				blank(line, col - 1);
			}
		} else if ((CharWidth.of(c) == 2 || isWideCluster(line, col)) && col + 1 < fColumns
				&& fTerminal.getChar(line, col + 1) == '\000') {
			blank(line, col + 1);
		}
	}

	/**
	 * @return whether the cell is the second of a wide character's two cells
	 */
	private boolean isSecondCell(int line, int col) {
		return col > 0 && col < fColumns && (CharWidth.isSecondCell(
				String.valueOf(new char[] { fTerminal.getChar(line, col - 1), fTerminal.getChar(line, col) }), 1)
				|| isWideCluster(line, col - 1));
	}

	/** @return whether a cluster grown wide, such as a narrow character with VS16, starts at the cell */
	private boolean isWideCluster(int line, int col) {
		String cluster = fTerminal.getCluster(line, col);
		return cluster != null && CharWidth.clusterCells(cluster) == 2;
	}

	/** The clusters starting in the cells from <code>from</code> to <code>to</code>, which a shift is about to move. */
	private Map<Integer, String> clustersIn(int line, int from, int to) {
		Map<Integer, String> clusters = new HashMap<>();
		for (int col = from; col < to; col++) {
			String cluster = fTerminal.getCluster(line, col);
			if (cluster != null) {
				clusters.put(col, cluster);
			}
		}
		return clusters;
	}

	private void blank(int line, int col) {
		fTerminal.setChar(line, col, ' ', fTerminal.getStyle(line, col));
	}

	/**
	 * Length of the run of characters starting at <code>offset</code> that each
	 * occupy exactly one cell and none of which can go on with a cluster before it,
	 * so that they can be copied in one block. Capped at
	 * <code>max</code> cells. Zero when the run does not start with such a
	 * character, which sends the caller down the code point by code point path.
	 */
	private static int narrowRun(char[] chars, boolean[] starts, int offset, int max) {
		int n = 0;
		while (n < max && offset + n < chars.length && starts[offset + n] && !Character.isSurrogate(chars[offset + n])
				&& CharWidth.of(chars[offset + n]) == 1) {
			n++;
		}
		return n;
	}

	private int doLineWrap() {
		int line;
		line = toAbsoluteLine(fCursorLine);
		fTerminal.setWrappedLine(line);
		doNewline();
		line = toAbsoluteLine(fCursorLine);
		setCursorColumn(0);
		return line;
	}

	/**
	 * MUST be called from a synchronized block!
	 */
	private void doNewline() {
		fWrapPending = false; // DEC STD 070: any move of the cursor forgets a pending wrap
		fWrappedJustNow = false;
		fLead = null;
		if (fCursorLine == fScrollRegion.getBottomLine()) {
			scrollUp(1);
		} else if (fCursorLine + 1 >= fLines) {
			int h = fTerminal.getHeight();
			fTerminal.addLine();
			if (h != fTerminal.getHeight()) {
				setCursorLine(fCursorLine + 1);
			}
		} else {
			setCursorLine(fCursorLine + 1);
		}
	}

	@Override
	public void processNewline() {
		synchronized (fTerminal) {
			doNewline();
		}
	}

	private void doReverseLineFeed() {
		fWrapPending = false;
		fWrappedJustNow = false;
		fLead = null;
		if (fCursorLine == fScrollRegion.getTopLine()) {
			scrollDown(1);
		} else {
			setCursorLine(fCursorLine - 1);
		}
	}

	@Override
	public void processReverseLineFeed() {
		synchronized (fTerminal) {
			doReverseLineFeed();
		}
	}

	@Override
	public int getCursorLine() {
		synchronized (fTerminal) {
			return fCursorLine;
		}
	}

	@Override
	public int getCursorColumn() {
		synchronized (fTerminal) {
			return fCursorColumn;
		}
	}

	@Override
	public void setCursor(int targetLine, int targetColumn) {
		synchronized (fTerminal) {
			setCursorLine(targetLine);
			setCursorColumn(targetColumn);
		}
	}

	@Override
	public void setCursorColumn(int targetColumn) {
		synchronized (fTerminal) {
			if (targetColumn < 0) {
				targetColumn = 0;
			} else if (targetColumn >= fColumns) {
				targetColumn = fColumns - 1;
			}
			fCursorColumn = targetColumn;
			fWrapPending = false;
			fWrappedJustNow = false;
			fLead = null;
			// We make the assumption that nobody is changing the
			// terminal cursor except this class!
			// This assumption gives a huge performance improvement
			fTerminal.setCursorColumn(targetColumn);
		}
	}

	@Override
	public void setCursorLine(int targetLine) {
		synchronized (fTerminal) {
			if (targetLine < 0) {
				targetLine = 0;
			} else if (targetLine >= fLines) {
				targetLine = fLines - 1;
			}
			fCursorLine = targetLine;
			fWrapPending = false;
			fWrappedJustNow = false;
			fLead = null;
			// We make the assumption that nobody is changing the
			// terminal cursor except this class!
			// This assumption gives a huge performance improvement
			fTerminal.setCursorLine(toAbsoluteLine(targetLine));
		}
	}

	@Override
	public int getLines() {
		synchronized (fTerminal) {
			return fLines;
		}
	}

	@Override
	public int getColumns() {
		synchronized (fTerminal) {
			return fColumns;
		}
	}

	@Override
	public void setVT100LineWrapping(boolean enable) {
		fVT100LineWrapping = enable;
	}

	@Override
	public boolean isVT100LineWrapping() {
		return fVT100LineWrapping;
	}

	@Override
	public void setInsertMode(boolean enable) {
		fInsertMode = enable;
	}

	@Override
	public void setScrollRegion(int top, int bottom) {
		if (top < 0 || bottom < 0) {
			fScrollRegion = ScrollRegion.FULL_WINDOW;
		} else if (top < bottom) {
			fScrollRegion = new ScrollRegion(top, bottom);
		}
	}

	@Override
	public void scrollUp(int n) {
		assert n > 0;
		synchronized (fTerminal) {
			int line = toAbsoluteLine(fScrollRegion.getTopLine());
			int nLines = Math.min(fTerminal.getHeight() - line, fScrollRegion.getHeight());
			fTerminal.scroll(line, nLines, -n);
		}
	}

	@Override
	public void scrollDown(int n) {
		assert n > 0;
		synchronized (fTerminal) {
			int line = toAbsoluteLine(fScrollRegion.getTopLine());
			int nLines = Math.min(fTerminal.getHeight() - line, fScrollRegion.getHeight());
			fTerminal.scroll(line, nLines, n);
		}
	}

	@Override
	public void eraseCharacters(int n) {
		if (n <= 0) {
			return; // nothing is erased, so no wide character may be broken either
		}
		synchronized (fTerminal) {
			eraseWhole(toAbsoluteLine(fCursorLine), fCursorColumn, Math.min(fCursorColumn + n, fColumns));
		}
	}
}
