package com.cocakova.charon.terminal

/**
 * Plain passages: URLs printed as text, with no OSC 8 mark (`npm run dev`'s
 * "Local: http://localhost:5173/", a build log's report link). Found only when a
 * finger asks — never scanned per frame — and read across soft wraps, so a link
 * the grid broke over two rows is still one link.
 */
object UrlScanner {

    /** A URL found in a run of text: [start] inclusive, [end] exclusive. */
    data class Hit(val url: String, val start: Int, val end: Int)

    /** A URL under a cell, with where it begins and ends in selection space. */
    data class PlainLink(val url: String, val fromRow: Int, val fromCol: Int, val toRow: Int, val toCol: Int)

    private val URL = Regex("""(?i)\b(?:https?|file|ftp)://[^\s<>"'`{}|\\^\u0000-\u001f]+""")

    /** Every URL in [text], trailing sentence punctuation and unpaired closers left out. */
    fun find(text: String): List<Hit> = URL.findAll(text).mapNotNull { m ->
        val end = m.range.first + trimmedLength(m.value)
        val url = text.substring(m.range.first, end)
        if (url.substringAfter("://").isEmpty()) null else Hit(url, m.range.first, end)
    }.toList()

    /** The URL covering [offset] in [text], if any. */
    fun at(text: String, offset: Int): Hit? = find(text).firstOrNull { offset >= it.start && offset < it.end }

    private fun trimmedLength(raw: String): Int {
        var n = raw.length
        while (n > 0) {
            val c = raw[n - 1]
            val drop = when (c) {
                '.', ',', ';', ':', '!', '?', '\'', '"' -> true
                ')' -> raw.count(n) { it == '(' } < raw.count(n) { it == ')' }
                ']' -> raw.count(n) { it == '[' } < raw.count(n) { it == ']' }
                else -> false
            }
            if (!drop) break
            n--
        }
        return n
    }

    private inline fun String.count(until: Int, pred: (Char) -> Boolean): Int {
        var k = 0
        for (i in 0 until until) if (pred(this[i])) k++
        return k
    }

    /**
     * The plain URL under ([row], [col]) in selection space. [lineAt] answers a
     * selection-space row (null past either end); rows joined by soft wrap
     * ([Line.isWrapped] marks a continuation) are read as one run. Wide cells count
     * once; their continuation cells point at the same character.
     */
    fun linkAt(lineAt: (Int) -> Line?, row: Int, col: Int): PlainLink? {
        lineAt(row) ?: return null
        var first = row
        while (row - first < MAX_JOIN && lineAt(first)?.isWrapped == true && lineAt(first - 1) != null) first--
        var last = row
        while (last - row < MAX_JOIN && lineAt(last + 1)?.isWrapped == true) last++

        val text = StringBuilder()
        // For each character offset, the cell it came from.
        val rows = ArrayList<Int>()
        val cols = ArrayList<Int>()
        var tapped = -1
        for (r in first..last) {
            val line = lineAt(r) ?: break
            for (c in 0 until line.cols) {
                val cont = CellAttrs.hasStyle(line.attrs[c], CellAttrs.WIDE_CONTINUATION)
                if (r == row && c == col) tapped = if (cont) text.length - 1 else text.length
                if (cont) continue
                val cp = line.codePoints[c]
                val s = if (cp == 0) " " else line.textAt(c)
                repeat(s.length) { rows += r; cols += c }
                text.append(s)
            }
        }
        if (tapped < 0) return null
        val hit = at(text.toString(), tapped) ?: return null
        return PlainLink(hit.url, rows[hit.start], cols[hit.start], rows[hit.end - 1], cols[hit.end - 1])
    }

    /** How many rows a soft-wrapped run may span either side of the tapped one. */
    private const val MAX_JOIN = 8
}
