package com.cocakova.charon.terminal

/**
 * Pulls plain text out of the grid for copy. Pure and testable — the UI supplies a
 * start and end cell (inclusive, any order) in selection space: row 0..rows-1 is
 * the live grid, negative rows reach into scrollback (see ScreenBuffer.relativeLine),
 * so one selection can run from deep history down onto the live screen. Soft-wrapped
 * rows are joined without a newline (so a wrapped command line copies as one line);
 * hard line breaks become '\n'. Per-row trailing spaces are trimmed, matching how
 * every terminal copies.
 */
object TextSelection {

    data class Cell(val row: Int, val col: Int)

    fun extract(screen: ScreenBuffer, a: Cell, b: Cell): String {
        val (start, end) = order(a, b)
        val firstRow = start.row.coerceIn(-screen.scrollbackSize, screen.rows - 1)
        val lastRow = end.row.coerceIn(-screen.scrollbackSize, screen.rows - 1)

        return buildString {
            for (row in firstRow..lastRow) {
                val line = screen.relativeLine(row)
                val from = if (row == firstRow) start.col.coerceIn(0, screen.cols - 1) else 0
                val to = if (row == lastRow) end.col.coerceIn(0, screen.cols - 1) else screen.cols - 1
                append(rowText(line, from, to))
                if (row != lastRow) {
                    // A newline only where the next row is NOT a soft-wrap continuation.
                    if (!screen.relativeLine(row + 1).isWrapped) append('\n')
                }
            }
        }
    }

    private fun rowText(line: Line, from: Int, to: Int): String = buildString {
        var col = from
        while (col <= to) {
            if (CellAttrs.hasStyle(line.attrs[col], CellAttrs.WIDE_CONTINUATION)) {
                col++
                continue
            }
            append(line.textAt(col))
            col++
        }
    }.trimEnd()

    private fun order(a: Cell, b: Cell): Pair<Cell, Cell> =
        if (a.row < b.row || (a.row == b.row && a.col <= b.col)) a to b else b to a

    /**
     * The word-class run around [col] on [line] (inclusive columns), for long-press
     * word selection. A "word" is letters, digits, and the punctuation that keeps
     * paths, URLs and identifiers whole; landing on other punctuation or whitespace
     * selects just that cell.
     */
    fun wordAt(line: Line, col: Int): IntRange {
        val c = col.coerceIn(0, line.cols - 1)
        if (!isWordChar(line.codePoints[c])) return c..c
        var start = c
        while (start > 0 && isWordChar(line.codePoints[start - 1])) start--
        var end = c
        while (end < line.cols - 1 && isWordChar(line.codePoints[end + 1])) end++
        return start..end
    }

    private fun isWordChar(cp: Int): Boolean {
        if (cp <= 0x20) return false
        val ch = cp.toChar()
        return Character.isLetterOrDigit(cp) || ch in "_-./~@:+"
    }
}
