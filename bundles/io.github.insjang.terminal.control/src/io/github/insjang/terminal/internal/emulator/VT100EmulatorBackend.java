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

import java.text.Normalizer;

import io.github.insjang.terminal.internal.model.CharWidth;
import io.github.insjang.terminal.model.ITerminalTextData;
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
	private boolean fVT100LineWrapping;
	private ScrollRegion fScrollRegion = ScrollRegion.FULL_WINDOW;

	public VT100EmulatorBackend(ITerminalTextData terminal) {
		fTerminal = terminal;
	}

	@Override
	public void enableAlternateScreen(boolean enable) {
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
				fNormalMaxHeight = fTerminal.getMaxHeight();
				// clearAll leaves the buffer the size of the screen, which is what the
				// alternate screen is: no history to scroll back through. Capping the
				// buffer there keeps it so: scrolling drops the top line instead of
				// growing the buffer, as a program on this screen expects.
				clearAll();
				fTerminal.setMaxHeight(fLines);
			} else {
				fTerminal.copy(fNormalScreen);
				fNormalScreen = null;
				// the margins were the full screen program's, not the shell's
				fScrollRegion = ScrollRegion.FULL_WINDOW;
				fTerminal.setMaxHeight(Math.max(fNormalMaxHeight, fTerminal.getHeight()));
				// The window may have been resized while the program had the screen, and
				// the buffer put back is the one from before. Narrower, and every write
				// past its margin throws; shorter than the screen, and the top of the
				// screen sits above its first line, so every line number comes out negative.
				if (fTerminal.getHeight() < fLines || fTerminal.getWidth() != fColumns) {
					fTerminal.setDimensions(Math.max(fTerminal.getHeight(), fLines), fColumns);
				}
				// back to the line the cursor was on, wherever it is on the screen now
				setCursor(fNormalCursorLine - (fTerminal.getHeight() - fLines), fNormalCursorColumn);
			}
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
		synchronized (fTerminal) {
			int line = toAbsoluteLine(fCursorLine);
			int n = charactersToInsert;
			for (int col = fColumns - 1; col >= fCursorColumn + n; col--) {
				char c = fTerminal.getChar(line, col - n);
				TerminalStyle style = fTerminal.getStyle(line, col - n);
				fTerminal.setChar(line, col, c, style);
			}
			eraseCells(line, fCursorColumn, Math.min(fCursorColumn + n, fColumns));
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
			for (int i = 0; i < used; i++) {
				fTerminal.addLine();
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
			eraseLine(toAbsoluteLine(fCursorLine));
		}
	}

	private void eraseLine(int line) {
		fTerminal.cleanLine(line);
		eraseCells(line, 0, fColumns);
	}

	@Override
	public void eraseLineToEnd() {
		synchronized (fTerminal) {
			int line = toAbsoluteLine(fCursorLine);
			eraseCells(line, fCursorColumn, fColumns);
			// Nothing is left out to the margin, so the line ends where the cursor is.
			fTerminal.clearWrappedLine(line);
		}
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
		// Only the background carries over; what is erased is not bold or underlined.
		TerminalStyle plain = getDefaultStyle();
		TerminalStyle erased = plain == null || fStyle == null ? fStyle : plain.setBackground(fStyle);
		for (int col = from; col < to; col++) {
			fTerminal.setChar(line, col, '\000', erased);
		}
	}

	@Override
	public void eraseLineToCursor() {
		synchronized (fTerminal) {
			eraseCells(toAbsoluteLine(fCursorLine), 0, fCursorColumn + 1);
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
		synchronized (fTerminal) {
			int line = toAbsoluteLine(fCursorLine);
			for (int col = fCursorColumn + n; col < fColumns; col++) {
				char c = fTerminal.getChar(line, col);
				TerminalStyle style = fTerminal.getStyle(line, col);
				fTerminal.setChar(line, col - n, c, style);
			}
			eraseCells(line, Math.max(fCursorColumn, fColumns - n), fColumns);
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
			if (fInsertMode) {
				insertCharacters(cellWidth(buffer)); // room in cells, not characters
			}
			int line = toAbsoluteLine(fCursorLine);
			int i = 0;
			while (i < chars.length) {
				int codePoint = Character.codePointAt(chars, i);
				int charsUsed = Character.charCount(codePoint);
				int width = CharWidth.of(codePoint);
				// What takes no cell of its own goes onto the character before it, even
				// one on the last cell with a wrap pending: it belongs there, not on the
				// next line.
				if (joinsCluster(line, codePoint)) {
					i += charsUsed;
					continue;
				}
				if (width == 0) {
					// composed into the character before it where the two have one form;
					// otherwise kept with it as a cluster, as a keycap is
					if (!combine(line, codePoint)) {
						attachToCluster(line, codePoint);
					}
					i += charsUsed;
					continue;
				}
				if (fWrapPending) {
					line = doLineWrap();
				}
				int room = fColumns - fCursorColumn;
				int col;
				int n = narrowRun(chars, i, room);
				if (n > 0) {
					breakWideChar(line, fCursorColumn);
					breakWideChar(line, fCursorColumn + n - 1);
					fTerminal.setChars(line, fCursorColumn, chars, i, n, fStyle);
					col = fCursorColumn + n;
					i += n;
				} else {
					// the cells it takes: a surrogate pair cannot share a cell, so it always takes two
					width = cellWidth(codePoint);
					if (width > room) {
						if (fCursorColumn > 0) {
							// a wide character is never split across the right margin
							line = doLineWrap();
							continue;
						}
						// terminal narrower than the character itself
						width = room;
					}
					breakWideChar(line, fCursorColumn);
					breakWideChar(line, fCursorColumn + width - 1);
					if (charsUsed == 2) {
						fTerminal.setChars(line, fCursorColumn, chars, i, 2, fStyle);
					} else {
						fTerminal.setChar(line, fCursorColumn, chars[i], fStyle);
						if (width == 2) {
							fTerminal.setChar(line, fCursorColumn + 1, '\000', fStyle);
						}
					}
					col = fCursorColumn + width;
					i += charsUsed;
				}
				// wrap needed?
				if (col == fColumns) {
					if (fVT100LineWrapping) {
						// deferred line wrapping (eat_newline_glitch)
						setCursorColumn(col - 1);
						fWrapPending = true;
					} else {
						line = doLineWrap();
					}
				} else {
					// Writing that stops short of the margin says the line ends here.
					// A program that draws its own screen writes the same row again and
					// again, and a row that was folded in one frame is a row of its own
					// in the next, so the mark has to be able to come off.
					fTerminal.clearWrappedLine(line);
					setCursorColumn(col);
				}
			}
		}
	}

	private static final int ZWJ = 0x200D, VS15 = 0xFE0E, VS16 = 0xFE0F;
	/** a zero width joiner was the last thing written: whatever comes next joins the cluster before it */
	private boolean fJoinPending;
	/** Longest cluster kept, in chars; the longest emoji sequences are about 15. */
	private static final int MAX_CLUSTER = 32;

	/**
	 * A grapheme cluster takes two cells however many characters it runs to, which
	 * is how Windows Terminal and the programs that lay text out for it count. What
	 * joins the cluster before the cursor and so takes no cells of its own: what
	 * follows a zero width joiner, a skin tone, a presentation selector, a mark. A
	 * presentation selector after a narrow character (a heart, a digit) makes the
	 * cluster wide first, taking the cell after it. The cells keep the first
	 * character to draw; the whole cluster is kept beside them for copying.
	 *
	 * @return whether the character was taken into a cluster
	 */
	private boolean joinsCluster(int line, int codePoint) {
		boolean join = fJoinPending;
		fJoinPending = false;
		if (codePoint == ZWJ) {
			fJoinPending = attachToCluster(line, codePoint);
			return true;
		}
		if (isRegionalIndicator(codePoint) && !join) {
			// two indicators make a flag, so the second joins the first
			String before = clusterBefore(line);
			join = before != null && before.codePointCount(0, before.length()) == 1
					&& isRegionalIndicator(before.codePointAt(0));
		}
		boolean modifier = codePoint == VS15 || codePoint == VS16 || (codePoint >= 0x1F3FB && codePoint <= 0x1F3FF);
		if (!join && !modifier) {
			return false;
		}
		if (attachToCluster(line, codePoint)) {
			return true;
		}
		if (codePoint != VS16 || fWrapPending || fCursorColumn == 0 || fCursorColumn >= fColumns) {
			return false;
		}
		// A narrow character asked to be shown as an emoji: it gets a second cell.
		int col = fCursorColumn - 1;
		char base = fTerminal.getChar(line, col);
		if (base == 0 || base == ' ') {
			return false;
		}
		breakWideChar(line, fCursorColumn);
		fTerminal.setChar(line, fCursorColumn, '\000', fTerminal.getStyle(line, col));
		fTerminal.setCluster(line, col, base + new String(Character.toChars(VS16)));
		setCursorColumn(fCursorColumn + 1);
		return true;
	}

	private static boolean isRegionalIndicator(int codePoint) {
		return codePoint >= 0x1F1E6 && codePoint <= 0x1F1FF;
	}

	/** @return whether there was a two-cell cluster before the cursor for the character to go into */
	private boolean attachToCluster(int line, int codePoint) {
		String cluster = clusterBefore(line);
		if (cluster == null) {
			return false;
		}
		if (cluster.length() >= MAX_CLUSTER) {
			return true; // still takes no cell, but is dropped: a flood of marks must not grow one cell without bound
		}
		fTerminal.setCluster(line, endColumn() - 2, cluster + new String(Character.toChars(codePoint)));
		return true;
	}

	/** Where the next character goes: past the margin while a wrap is pending, the cursor being held on the last cell. */
	private int endColumn() {
		return fWrapPending ? fColumns : fCursorColumn;
	}

	/** The cluster in the two cells before the cursor, or null when there is no two-cell character there. */
	private String clusterBefore(int line) {
		int col = endColumn() - 2;
		if (col < 0) {
			return null;
		}
		String cluster = fTerminal.getCluster(line, col);
		if (cluster != null) {
			return cluster;
		}
		char first = fTerminal.getChar(line, col), second = fTerminal.getChar(line, col + 1);
		if (Character.isHighSurrogate(first) && Character.isLowSurrogate(second)) {
			return new String(new char[] { first, second });
		}
		if (CharWidth.of(first) == 2 && second == '\000') {
			return String.valueOf(first);
		}
		return null;
	}

	/**
	 * A mark owns no cell of its own, so it has to go onto the character it followed
	 * or be lost. A cell holds one character, which is enough whenever the two have
	 * a single composed form - the accents, the Hangul jamo, the Japanese voicing
	 * marks. What has no such form is still dropped, there being nowhere to put it.
	 */
	private boolean combine(int line, int mark) {
		int col = endColumn() - 1;
		if (col > 0 && fTerminal.getChar(line, col) == '\000') {
			col--; // the mark follows a wide character, and that is the cell it lives in
		}
		if (col < 0) {
			return false;
		}
		char base = fTerminal.getChar(line, col);
		if (base == 0 || base == ' ') {
			return false;
		}
		String composed = Normalizer.normalize(base + new String(Character.toChars(mark)), Normalizer.Form.NFC);
		if (composed.length() != 1) {
			return false;
		}
		fTerminal.setChar(line, col, composed.charAt(0), fTerminal.getStyle(line, col));
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
			if (col > 0 && CharWidth.of(fTerminal.getChar(line, col - 1)) == 2) {
				blank(line, col - 1);
			}
		} else if (CharWidth.of(c) == 2 && col + 1 < fColumns && fTerminal.getChar(line, col + 1) == '\000') {
			blank(line, col + 1);
		}
	}

	/**
	 * Cells a code point takes on the screen: its display width, except that a
	 * printable character beyond the BMP always takes two, since its two chars
	 * cannot share a cell.
	 */
	private static int cellWidth(int codePoint) {
		int width = CharWidth.of(codePoint);
		return width != 0 && Character.charCount(codePoint) == 2 ? 2 : width;
	}

	private static int cellWidth(String text) {
		return text.codePoints().map(VT100EmulatorBackend::cellWidth).sum();
	}

	private void blank(int line, int col) {
		fTerminal.setChar(line, col, ' ', fTerminal.getStyle(line, col));
	}

	/**
	 * Length of the run of characters starting at <code>offset</code> that each
	 * occupy exactly one cell, so that they can be copied in one block. Capped at
	 * <code>max</code> cells. Zero when the run does not start with such a
	 * character, which sends the caller down the code point by code point path.
	 */
	private static int narrowRun(char[] chars, int offset, int max) {
		int n = 0;
		while (n < max && offset + n < chars.length && !Character.isSurrogate(chars[offset + n])
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
		synchronized (fTerminal) {
			int line = toAbsoluteLine(fCursorLine);
			eraseCells(line, fCursorColumn, Math.min(fCursorColumn + n, fColumns));
		}
	}
}
