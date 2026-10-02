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

    /**
     * Rows a height-shrink pushed over the top into scrollback and a later grow has
     * not yet pulled back. The phone's keyboard rising and falling is a shrink and a
     * grow of the same height: paying the debt back on the grow puts the screen you
     * were reading back exactly where it was. A clear (ED 2/3) forgives it — history
     * must not drop back above a screen you just wiped.
     */
    private var resizeDebt = 0

    /**
     * Resize without reflow. Columns truncate or pad. Rows, when [cursorRow] is given
     * and this buffer keeps history (the primary screen):
     *
     *  - **shrinking** keeps the cursor's line on the glass. Blank rows below the
     *    cursor go first; then lines leave over the top into scrollback, exactly as
     *    if the screen had scrolled; only content below the cursor that still can't
     *    fit is dropped. The keyboard rising no longer eats the prompt.
     *  - **growing** first pulls back what a shrink pushed into history, then adds
     *    blank rows at the bottom.
     *
     * Returns how many rows the content moved up (negative = down), so the caller
     * carries the cursor with its text. A buffer without history (the alternate
     * screen, whose program redraws on SIGWINCH anyway) keeps its top rows.
     */
    fun resize(newCols: Int, newRows: Int, cursorRow: Int = -1): Int {
        if (newCols != cols) {
            for (l in lines) l.resize(newCols)
            for (l in scrollback) l.resize(newCols)
            cols = newCols
        }
        if (newRows == rows) return 0
        val anchored = cursorRow >= 0 && maxScrollback > 0
        if (!anchored) {
            val newLines = Array(newRows) { r -> if (r < rows) lines[r] else Line(cols) }
            lines = newLines
            rows = newRows
            return 0
        }
        val cursor = cursorRow.coerceIn(0, rows - 1)
        return if (newRows < rows) shrinkAround(newRows, cursor) else growBack(newRows)
    }

    private fun shrinkAround(newRows: Int, cursor: Int): Int {
        val excess = rows - newRows
        var trailingBlank = 0
        var r = rows - 1
        while (trailingBlank < excess && r > cursor && lines[r].isBlank()) {
            trailingBlank++
            r--
        }
        val remaining = excess - trailingBlank
        // Never push the cursor's own line away; whatever still can't fit after that
        // sits below the cursor, and is the one thing a shrink may drop.
        val pushTop = minOf(remaining, cursor)
        for (i in 0 until pushTop) pushScrollback(lines[i])
        val old = lines
        lines = Array(newRows) { old[pushTop + it] }
        rows = newRows
        resizeDebt = (resizeDebt + pushTop).coerceAtMost(scrollback.size)
        return pushTop
    }

    private fun growBack(newRows: Int): Int {
        val extra = newRows - rows
        val pull = minOf(extra, resizeDebt, scrollback.size)
        val pulled = ArrayList<Line>(pull)
        repeat(pull) { pulled.add(0, scrollback.removeLast()) }
        resizeDebt -= pull
        val old = lines
        lines = Array(newRows) { i ->
            when {
                i < pull -> pulled[i]
                i - pull < old.size -> old[i - pull]
                else -> Line(cols)
            }
        }
        rows = newRows
        return -pull
    }

    fun clearAll(fillAttr: Long) {
        for (l in lines) l.clear(fillAttr)
        resizeDebt = 0
    }

    fun clearScrollback() {
        scrollback.clear()
        resizeDebt = 0
    }

    /** Every line we still hold, history first. Used to sweep anchored apparitions. */
    inline fun forEachLine(action: (Line) -> Unit) {
        for (i in 0 until scrollbackSize) action(scrollbackLine(i))
        for (r in 0 until rows) action(line(r))
    }

    private fun pushScrollback(line: Line) {
        scrollback.addLast(line)
        while (scrollback.size > maxScrollback) scrollback.removeFirst()
    }

    /** Visible grid as text lines (tests/goldens). */
    fun toText(): List<String> = lines.map { it.toText() }
}
