package com.cocakova.charon.terminal

/**
 * One row of the grid: code points + packed attrs per cell. Combining characters are
 * rare, so they live in a lazily-allocated overflow map keyed by column rather than
 * widening every cell.
 *
 * [isWrapped] marks a line that is the continuation of the previous one (soft wrap) —
 * recorded from day one so scrollback reflow and rectangular-vs-logical copy can be
 * built later without a data-model migration.
 */
class Line(cols: Int) {
    var codePoints = IntArray(cols)
        private set
    var attrs = LongArray(cols)
        private set
    var isWrapped = false

    private var combining: HashMap<Int, String>? = null

    val cols: Int get() = codePoints.size

    init {
        fill(0, cols, SPACE, CellAttrs.DEFAULT)
    }

    fun set(col: Int, codePoint: Int, attr: Long) {
        codePoints[col] = codePoint
        attrs[col] = attr
        combining?.remove(col)
    }

    fun appendCombining(col: Int, codePoint: Int) {
        val map = combining ?: HashMap<Int, String>(4).also { combining = it }
        map[col] = (map[col] ?: "") + String(Character.toChars(codePoint))
    }

    fun combiningAt(col: Int): String? = combining?.get(col)

    /** Full cell text at [col] (base char + any combining marks). */
    fun textAt(col: Int): String {
        val base = String(Character.toChars(codePoints[col]))
        val marks = combiningAt(col) ?: return base
        return base + marks
    }

    fun fill(from: Int, until: Int, codePoint: Int, attr: Long) {
        codePoints.fill(codePoint, from, until)
        attrs.fill(attr, from, until)
        combining?.keys?.removeAll { it in from until until }
    }

    fun clear(attr: Long = CellAttrs.DEFAULT) {
        fill(0, cols, SPACE, attr)
        isWrapped = false
    }

    /** Shift cells right by [n] starting at [col] (ICH); vacated cells get [attr]. */
    fun insertCells(col: Int, n: Int, attr: Long) {
        if (n <= 0) return
        val shift = n.coerceAtMost(cols - col)
        codePoints.copyInto(codePoints, col + shift, col, cols - shift)
        attrs.copyInto(attrs, col + shift, col, cols - shift)
        remapCombining(col, shift, insert = true)
        fill(col, col + shift, SPACE, attr)
    }

    /** Shift cells left by [n] starting at [col] (DCH); vacated tail gets [attr]. */
    fun deleteCells(col: Int, n: Int, attr: Long) {
        if (n <= 0) return
        val shift = n.coerceAtMost(cols - col)
        codePoints.copyInto(codePoints, col, col + shift, cols)
        attrs.copyInto(attrs, col, col + shift, cols)
        remapCombining(col, shift, insert = false)
        fill(cols - shift, cols, SPACE, attr)
    }

    /** Truncate or pad to [newCols]; content beyond is dropped (no reflow in v1). */
    fun resize(newCols: Int) {
        if (newCols == cols) return
        val newCp = IntArray(newCols)
        val newAt = LongArray(newCols)
        val copy = minOf(cols, newCols)
        codePoints.copyInto(newCp, 0, 0, copy)
        attrs.copyInto(newAt, 0, 0, copy)
        if (newCols > cols) {
            newCp.fill(SPACE, cols, newCols)
            newAt.fill(CellAttrs.DEFAULT, cols, newCols)
        }
        codePoints = newCp
        attrs = newAt
        combining?.keys?.removeAll { it >= newCols }
    }

    private fun remapCombining(col: Int, shift: Int, insert: Boolean) {
        val map = combining ?: return
        if (map.isEmpty()) return
        val remapped = HashMap<Int, String>(map.size)
        for ((c, s) in map) {
            when {
                c < col -> remapped[c] = s
                insert -> if (c + shift < cols) remapped[c + shift] = s
                c >= col + shift -> remapped[c - shift] = s
            }
        }
        combining = remapped
    }

    /** Plain-text content, trailing spaces trimmed (for tests/selection/goldens). */
    fun toText(): String = buildString {
        for (c in 0 until cols) {
            if (CellAttrs.hasStyle(attrs[c], CellAttrs.WIDE_CONTINUATION)) continue
            append(textAt(c))
        }
    }.trimEnd()

    companion object {
        const val SPACE = 0x20
    }
}
