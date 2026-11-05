/*******************************************************************************
 * Copyright (c) 2007, 2018 Wind River Systems, Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 * Michael Scharf (Wind River) - initial API and implementation
 * Michael Scharf (Wind River) - [205260] Terminal does not take the font from the preferences
 * Michael Scharf (Wind River) - [206328] Terminal does not draw correctly with proportional fonts
 * Anton Leherbauer (Wind River) - [294468] Fix scroller and text line rendering
 * Martin Oberhuber (Wind River) - [265352][api] Allow setting fonts programmatically
 *******************************************************************************/
package io.github.insjang.terminal.internal.textcanvas;

import java.text.Normalizer;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.core.runtime.Platform;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Drawable;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import io.github.insjang.terminal.connector.Logger;
import io.github.insjang.terminal.internal.control.impl.TerminalPlugin;
import io.github.insjang.terminal.internal.model.CharWidth;
import io.github.insjang.terminal.internal.preferences.ITerminalConstants;
import io.github.insjang.terminal.model.ITerminalTextDataReadOnly;
import io.github.insjang.terminal.model.LineSegment;
import io.github.insjang.terminal.model.TerminalColor;
import io.github.insjang.terminal.model.TerminalStyle;

/**
 *
 */
public class TextLineRenderer implements ILinelRenderer {
	private static final boolean DEBUG_HOVER = Platform.getDebugBoolean(Logger.TRACE_DEBUG_LOG_HOVER);
	/** width in pixels of the vertical bar cursor */
	private static final int BAR_CURSOR_WIDTH = 2;
	private final ITextCanvasModel fModel;
	private final StyleMap fStyleMap;

	public TextLineRenderer(Supplier<Drawable> usageContextProvider, ITextCanvasModel model) {
		fModel = model;
		fStyleMap = new StyleMap(usageContextProvider);
	}

	@Override
	public int getCellWidth() {
		return fStyleMap.getFontWidth();
	}

	@Override
	public int getCellHeight() {
		return fStyleMap.getFontHeight();
	}

	@Override
	public void drawLine(ITextCanvasModel model, GC gc, int line, int x, int y, int colFirst, int colLast) {
		int width = getCellWidth() * (colLast - colFirst);
		int height = getCellHeight();
		if (width <= 0 || height <= 0) {
			return;
		}
		if (line < 0 || line >= getTerminalText().getHeight() || colFirst >= getTerminalText().getWidth()
				|| colFirst - colLast == 0) {
			fillBackground(gc, x, y, width, height);
		} else {
			colLast = Math.min(colLast, getTerminalText().getWidth());
			LineSegment[] segments = getTerminalText().getLineSegments(line, colFirst, colLast - colFirst);
			// Every background goes down before any text. A glyph can reach a pixel past
			// its last cell, and the background of the segment after it would cut that off.
			for (LineSegment segment : segments) {
				setupGC(gc, segment.getStyle());
				gc.fillRectangle(x + (segment.getColumn() - colFirst) * getCellWidth(), y,
						segment.getText().length() * getCellWidth(), height);
			}
			for (LineSegment segment : segments) {
				setupGC(gc, segment.getStyle());
				drawText(gc, line, x, y, colFirst, segment.getColumn(), segment.getText(), true);
			}
			if (colFirst == 0 && (getTerminalText().getLineMark(line) & ITerminalTextDataReadOnly.MARK_FAILED) != 0) {
				// the prompt of a command that failed (OSC 133;D): a red bar at the left edge
				gc.setBackground(fStyleMap.getForegroundColor(TerminalStyle.getStyle(TerminalColor.RED, TerminalColor.BLACK)));
				gc.fillRectangle(x, y, Math.max(2, getCellWidth() / 4), height);
			}
			drawCursor(model, gc, line, x, y, colFirst);
			if (fModel.hasHoverSelection(line)) {
				if (DEBUG_HOVER) {
					System.out.format("hover: %s  contains hover selection\n", line); //$NON-NLS-1$
				}
				Point hsStart = fModel.getHoverSelectionStart();
				Point hsEnd = fModel.getHoverSelectionEnd();
				int colStart = line == hsStart.y ? hsStart.x : 0;
				int colEnd = line == hsEnd.y ? hsEnd.x : getTerminalText().getWidth();
				if (colStart < colEnd) {
					Color defaultFg = fStyleMap.getForegroundColor(null);
					gc.setForeground(defaultFg);
					drawUnderline(gc, x, y, colStart, colEnd);
				}
			}
			if (fModel.hasLineSelection(line)) {
				TerminalStyle style = TerminalStyle.getStyle(TerminalColor.SELECTION_FOREGROUND,
						TerminalColor.SELECTION_BACKGROUND);
				setupGC(gc, style);
				Point start = model.getSelectionStart();
				Point end = model.getSelectionEnd();
				// Everything the selection covers on this line goes down first. Drawing
				// it as the text is drawn leaves out whatever the text does not reach:
				// the cells past the last character a program put on the line, and the
				// cells where the font draws a glyph narrower than the one it sits in.
				int from = Math.max(start.y == line ? start.x : 0, colFirst);
				int to = end.y == line ? Math.min(end.x + 1, colLast) : colLast;
				if (to > from) {
					gc.fillRectangle(x + (from - colFirst) * getCellWidth(), y, (to - from) * getCellWidth(),
							getCellHeight());
				}
				char[] chars = model.getTerminalText().getChars(line);
				if (chars != null) {
					int offset = 0;
					if (start.y == line) {
						offset = start.x;
					}
					offset = Math.max(offset, colFirst);
					int len;
					if (end.y == line) {
						len = end.x - offset + 1;
					} else {
						len = chars.length - offset + 1;
					}
					len = Math.min(len, chars.length - offset);
					if (len > 0) {
						String text = new String(chars, offset, len);
						drawText(gc, line, x, y, colFirst, offset, text);
					}
				}
			}
		}
	}

	private void fillBackground(GC gc, int x, int y, int width, int height) {
		Color bg = gc.getBackground();
		gc.setBackground(getDefaultBackgroundColor());
		gc.fillRectangle(x, y, width, height);
		gc.setBackground(bg);

	}

	@Override
	public Color getDefaultBackgroundColor() {
		return fStyleMap.getBackgroundColor(null);
	}

	private void drawCursor(ITextCanvasModel model, GC gc, int row, int x, int y, int colFirst) {
		if (!model.isCursorOn()) {
			return;
		}
		int cursorLine = model.getCursorLine();

		if (row == cursorLine) {
			int cursorColumn = model.getCursorColumn();
			if (cursorColumn < getTerminalText().getWidth()) {
				// On a wide character the cursor covers both of its cells, whichever
				// one it is on, as in xterm: half a glyph cannot be drawn reversed.
				char[] chars = getTerminalText().getChars(row);
				String line = chars == null ? "" : new String(chars); //$NON-NLS-1$
				if (cursorColumn < line.length()
						&& (CharWidth.isSecondCell(line, cursorColumn) || isWideCluster(row, cursorColumn - 1))) {
					cursorColumn--;
				}
				int cells = cursorColumn + 1 < line.length() && CharWidth.isSecondCell(line, cursorColumn + 1)
						|| isWideCluster(row, cursorColumn) ? 2 : 1;
				TerminalStyle style = getTerminalText().getStyle(row, cursorColumn);
				if (style == null) {
					// TODO make the cursor color customizable
					style = TerminalStyle.getStyle(TerminalColor.FOREGROUND, TerminalColor.BACKGROUND);
				}
				if (isBarCursor()) {
					gc.setBackground(fStyleMap.getForegroundColor(style));
					gc.fillRectangle(x + (cursorColumn - colFirst) * getCellWidth(), y, BAR_CURSOR_WIDTH, getCellHeight());
					return;
				}
				style = style.setReverse(!style.isReverse());
				setupGC(gc, style);
				String text = cells == 2 ? line.substring(cursorColumn, cursorColumn + 2)
						: String.valueOf(getTerminalText().getChar(row, cursorColumn));
				Rectangle clipping = gc.getClipping();
				gc.setClipping(clipping.intersection(new Rectangle(x + (cursorColumn - colFirst) * getCellWidth(), y,
						cells * getCellWidth(), getCellHeight())));
				drawText(gc, row, x, y, colFirst, cursorColumn, text);
				gc.setClipping(clipping);
			}
		}
	}

	private static boolean isBarCursor() {
		TerminalPlugin plugin = TerminalPlugin.getDefault();
		return plugin != null && plugin.getPreferenceStore().getBoolean(ITerminalConstants.PREF_BAR_CURSOR);
	}

	/**
	 * @return the grapheme cluster that starts in the cell, to be drawn whole over
	 *         the cells it takes when they are no more than <code>room</code>; null
	 *         when the cell is drawn as it is, the cluster composing into the one
	 *         character it holds (an accent, Hangul jamo)
	 */
	private String clusterAt(int line, int col, int room) {
		String cluster = getTerminalText().getCluster(line, col);
		if (cluster == null || CharWidth.clusterCells(cluster) > room) {
			return null;
		}
		String composed = Normalizer.normalize(cluster, Normalizer.Form.NFC);
		return composed.codePointCount(0, composed.length()) == 1 ? null : cluster;
	}

	/** @return whether a cluster grown wide, such as a heart with VS16, starts in the cell */
	private boolean isWideCluster(int line, int col) {
		String cluster = col < 0 ? null : getTerminalText().getCluster(line, col);
		return cluster != null && CharWidth.clusterCells(cluster) == 2;
	}

	private void drawText(GC gc, int line, int x, int y, int colFirst, int col, String text) {
		drawText(gc, line, x, y, colFirst, col, text, false);
	}

	/**
	 * @param filled the background of the text is already there, so it is drawn
	 *            over it and leaves alone whatever reaches into its cells
	 */
	private void drawText(GC gc, int line, int x, int y, int colFirst, int col, String text, boolean filled) {
		int offset = (col - colFirst) * getCellWidth();
		if (fStyleMap.isFontProportional()) {
			// draw the background
			// TODO why does this not work???????
			//			gc.fillRectangle(x,y,fStyleMap.getFontWidth()*text.length(),fStyleMap.getFontHeight());
			int xx = x + offset;
			for (int i = 0; i < text.length(); i++) {
				char c = text.charAt(i);
				// a cluster is drawn whole, over the cells it takes
				String cluster = clusterAt(line, col + i, text.length() - i);
				int cells = cluster != null ? CharWidth.clusterCells(cluster) : cellsAt(text, i);
				// a character beyond the BMP is drawn whole, both surrogates at once
				int n = cluster != null ? cells : Character.charCount(text.codePointAt(i));
				// TODO why do I have to draw the background character by character??????
				gc.fillRectangle(xx, y, cells * fStyleMap.getFontWidth(), fStyleMap.getFontHeight());
				if (cluster != null) {
					gc.drawString(Normalizer.normalize(cluster, Normalizer.Form.NFC), fStyleMap.getCharOffset(c) + xx, y,
							false);
				} else if (c != ' ' && c != '\000') {
					gc.drawString(text.substring(i, i + n), fStyleMap.getCharOffset(c) + xx, y, false);
				}
				xx += cells * fStyleMap.getFontWidth();
				i += n - 1;
			}
		} else {
			// One call keeps whatever the font does with the run, ligatures included,
			// but only while it advances exactly one cell per column. A character the
			// font does not have is drawn from somewhere else and rarely does, and
			// then everything after it on the line sits in the wrong column.
			String drawn = withoutFillers(text);
			if (!hasCluster(line, col, text.length()) && gc.textExtent(drawn).x == text.length() * getCellWidth()) {
				gc.drawString(drawn, x + offset, y, filled);
			} else {
				drawCellByCell(gc, line, col, x + offset, y, text, filled);
			}
		}
	}

	/** @return whether a cluster to be drawn whole starts in any of the cells */
	private boolean hasCluster(int line, int col, int cells) {
		for (int i = 0; i < cells; i++) {
			if (clusterAt(line, col + i, cells - i) != null) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Puts every character at the start of its own cell, so the columns hold no
	 * matter what the font makes of it. A wide character is left to cover the cell
	 * of the filler that follows it.
	 */
	private void drawCellByCell(GC gc, int line, int col, int x, int y, String text, boolean filled) {
		// The whole run at once, because the characters are drawn over it one at a
		// time and the cells between them would otherwise keep what was there before.
		if (!filled) {
			gc.fillRectangle(x, y, text.length() * getCellWidth(), getCellHeight());
		}
		Font terminalFont = gc.getFont();
		Font fallback = fStyleMap.getFallbackFont();
		for (int i = 0; i < text.length();) {
			// A character beyond the BMP is two chars in two cells, and has to be
			// drawn whole: half of a surrogate pair is no character at all. So is a
			// cluster, which the font may draw as one glyph (a flag, a family).
			String cluster = clusterAt(line, col + i, text.length() - i);
			int n = cluster != null ? CharWidth.clusterCells(cluster) : Character.charCount(text.codePointAt(i));
			char c = text.charAt(i);
			if (cluster != null || c != ' ' && c != '\000') {
				String one = cluster != null ? Normalizer.normalize(cluster, Normalizer.Form.NFC) : text.substring(i, i + n);
				// A character the terminal font has no glyph for is the reason this run is
				// being drawn a cell at a time, and it is drawn better by a font that has
				// one than by whatever the platform reaches for.
				int cells = cluster != null ? n : cellsAt(text, i);
				boolean missing = fallback != null && gc.textExtent(one).x != cells * getCellWidth();
				if (missing) {
					gc.setFont(fallback);
				}
				gc.drawString(one, x + i * getCellWidth(), y, true);
				if (missing) {
					gc.setFont(terminalFont);
				}
			}
			i += n;
		}
	}

	/**
	 * Cells taken up by the character at <code>index</code>: none for the filler of
	 * a wide character, since the character it belongs to already covers it, two
	 * for a wide character, one for anything else.
	 */
	private static int cellsAt(String text, int index) {
		if (CharWidth.isFiller(text, index)) {
			return 0;
		}
		int codePoint = text.codePointAt(index);
		// a surrogate pair takes two cells whatever its width, as it is stored
		return Character.charCount(codePoint) == 2 || CharWidth.of(codePoint) == 2 ? 2 : 1;
	}

	/**
	 * The text as it should be handed to a fixed width font: fillers dropped, since
	 * the wide character before them already spans their cell, and every other null
	 * turned into the space it stands for. What is left lines up column for column,
	 * as long as the font draws a wide character in exactly two cells.
	 */
	private static String withoutFillers(String text) {
		StringBuilder drawn = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c != '\000') {
				drawn.append(c);
			} else if (!CharWidth.isFiller(text, i)) {
				drawn.append(' ');
			}
		}
		return drawn.toString();
	}

	/**
	 *
	 * @param gc
	 * @param colStart Starting text column to underline (inclusive)
	 * @param colEnd Ending text column to underline (inclusive)
	 */
	private void drawUnderline(GC gc, int xOffset, int yOffset, int colStart, int colEnd) {
		int y = yOffset + getCellHeight() - 1;
		int x = xOffset + getCellWidth() * colStart;

		// x2 is the right side of last column being underlined.
		int x2 = (colEnd + 1) * getCellWidth() - 1;
		gc.drawLine(x, y, x2, y);
	}

	private void setupGC(GC gc, TerminalStyle style) {
		Color foregroundColor = fStyleMap.getForegroundColor(style);
		gc.setForeground(foregroundColor);
		Color backgroundColor = fStyleMap.getBackgroundColor(style);
		gc.setBackground(backgroundColor);

		Font f = fStyleMap.getFont(style);
		if (f != gc.getFont()) {
			gc.setFont(f);
		}
	}

	ITerminalTextDataReadOnly getTerminalText() {
		return fModel.getTerminalText();
	}

	@Override
	public void updateFont(String fontName) {
		fStyleMap.updateFont(fontName);
	}

	@Override
	public void updateColors(Map<TerminalColor, Color> map) {
		fStyleMap.updateColors(map);
	}

	@Override
	public void setInvertedColors(boolean invert) {
		fStyleMap.setInvertedColors(invert);

	}

	@Override
	public boolean isInvertedColors() {
		return fStyleMap.isInvertedColors();
	}
}
