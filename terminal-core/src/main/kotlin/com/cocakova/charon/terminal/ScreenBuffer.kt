package com.cocakova.charon.terminal

/**
 * The visible grid plus scrollback. Scrollback is a bounded deque: lines pushed off the
 * top of a full-screen scroll land here (primary screen only — the alternate screen
 * never keeps history).
 */
class ScreenBuffer(
    initialCols: Int,
    initialRows: Int,
    private val maxScrollback: Int,
) {
    var cols: Int = initialCols
        private set
    var rows: Int = initialRows
        private set

    private var lines = Array(rows) { Line(cols) }
    private val scrollback = ArrayDeque<Line>()

    val scrollbackSize: Int get() = scrollback.size

    fun line(row: Int): Line = lines[row]

    /** Scrollback line, 0 = oldest. */
    fun scrollbackLine(index: Int): Line = scrollback[index]

    /**
     * The line to draw at visible [row] when the view is scrolled back by
     * [scrollOffset] rows (0 = live bottom). The virtual space is scrollback
     * (oldest first) followed by the live grid; this walks that space so the
     * renderer, selection and copy all agree on what a visible row means.
     */
    fun viewLine(scrollOffset: Int, row: Int): Line {
        val off = scrollOffset.coerceIn(0, scrollback.size)
        val v = scrollback.size - off + row
        return if (v < scrollback.size) scrollback[v] else lines[v - scrollback.size]
    }

    /**
     * The line at [row] in selection space, which is anchored to the live grid:
     * 0..rows-1 is the grid itself, negative rows reach back into scrollback
     * (-1 = the newest scrollback line, -scrollbackSize = the oldest). Selections
     * live in this space so they stay glued to their text while the view scrolls.
     */
    fun relativeLine(row: Int): Line =
        if (row >= 0) lines[row] else scrollback[scrollback.size + row]

    /**
     * Scroll the region [top, bottom] (inclusive) up by [n]. When the region starts at
     * the top of a primary screen, evicted lines go to scrollback; otherwise they die.
     * Vacated lines at the bottom are cleared with [fillAttr].
     */
    fun scrollRegionUp(top: Int, bottom: Int, n: Int, fillAttr: Long, keepHistory: Boolean) {
        val count = n.coerceIn(0, bottom - top + 1)
        if (count == 0) return
        repeat(count) {
            val evicted = lines[top]
            for (r in top until bottom) lines[r] = lines[r + 1]
            lines[bottom] = if (keepHistory && top == 0) {
                pushScrollback(evicted)
                Line(cols).also { if (fillAttr != CellAttrs.DEFAULT) it.clear(fillAttr) }
            } else {
                evicted.also { it.clear(fillAttr) }
            }
        }
    }

    /** Scroll the region [top, bottom] down by [n]; vacated top lines cleared. */
    fun scrollRegionDown(top: Int, bottom: Int, n: Int, fillAttr: Long) {
        val count = n.coerceIn(0, bottom - top + 1)
        if (count == 0) return
        repeat(count) {
            val recycled = lines[bottom]
            for (r in bottom downTo top + 1) lines[r] = lines[r - 1]
            lines[top] = recycled.also { it.clear(fillAttr) }
        }
    }

    /** Resize without reflow: truncate/pad columns, drop/add rows at the bottom. */
    fun resize(newCols: Int, newRows: Int) {
        if (newCols != cols) {
            for (l in lines) l.resize(newCols)
            for (l in scrollback) l.resize(newCols)
            cols = newCols
        }
        if (newRows != rows) {
            val newLines = Array(newRows) { r -> if (r < rows) lines[r] else Line(cols) }
            lines = newLines
            rows = newRows
        }
    }

    fun clearAll(fillAttr: Long) {
        for (l in lines) l.clear(fillAttr)
    }

    fun clearScrollback() {
        scrollback.clear()
    }

    private fun pushScrollback(line: Line) {
        scrollback.addLast(line)
        while (scrollback.size > maxScrollback) scrollback.removeFirst()
    }

    /** Visible grid as text lines (tests/goldens). */
    fun toText(): List<String> = lines.map { it.toText() }
}
