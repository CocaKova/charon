package com.cocakova.charon.terminal

/**
 * The dredge — search in the held water, in two strokes so the reader thread is
 * never kept waiting: [snapshot] copies the text of every line the buffer keeps
 * (scrollback oldest-first, then the live grid) under the session lock, fast and
 * allocation-light; [find] runs the query over that copy anywhere else.
 *
 * - **Plain or pattern.** A plain query is a literal; a pattern is a regex. Either
 *   is smart-case: all lower case matches any case, a capital asks for exact case.
 * - **Soft wraps are one line.** Rows joined by a wrap are searched as the line the
 *   program printed, so a match may start on one row and end on the next.
 * - **Columns are cells.** A wide glyph is one character but two cells; hits come
 *   back in cells, so the wash lands on the words.
 * - **Hits keep their place.** A hit names its line by a lasting number
 *   ([ScreenBuffer.linesPushed] + selection-space row) that output doesn't shift.
 */
object SearchEngine {

    /** One match, from ([line], [start]) to ([endLine], [end]) inclusive, in cells. */
    data class Hit(val line: Long, val start: Int, val endLine: Long, val end: Int) {
        /** The hit's first row in selection space, given the buffer's push count now. */
        fun row(linesPushed: Long): Int = (line - linesPushed).toInt()
    }

    /** What the renderer needs: every hit plus which one the eye is on. */
    data class SearchState(val hits: List<Hit>, val current: Int)

    /** One row's text as copied: a cell map only when chars and cells part ways. */
    class RowText(val text: String, val cellOf: IntArray?, val continues: Boolean)

    /** Every held line's text, numbered from [firstLine]. */
    class Snapshot(val firstLine: Long, val rows: Array<RowText>)

    /** The cap: past this many sightings the dredge stops counting. */
    const val MAX_HITS = 10_000

    /** Copy the held text. Call under the lock that guards [screen]; it is quick. */
    fun snapshot(screen: ScreenBuffer): Snapshot {
        val first = -screen.scrollbackSize
        val count = screen.rows - first
        val copier = RowCopier(screen.cols)
        val rows = Array(count) { i ->
            val next = if (i + 1 < count) screen.relativeLine(first + i + 1) else null
            copier.copy(screen.relativeLine(first + i), continues = next?.isWrapped == true)
        }
        return Snapshot(screen.linesPushed + first, rows)
    }

    /** One reusable builder and cell map for a whole snapshot: no garbage per row. */
    private class RowCopier(cols: Int) {
        private val sb = StringBuilder(cols)
        private var map = IntArray(cols * 2 + 8)

        fun copy(line: Line, continues: Boolean): RowText {
            sb.setLength(0)
            var simple = true
            for (c in 0 until line.cols) {
                if (CellAttrs.hasStyle(line.attrs[c], CellAttrs.WIDE_CONTINUATION)) {
                    simple = false
                    continue
                }
                val before = sb.length
                val cp = line.codePoints[c]
                sb.appendCodePoint(if (cp == 0) Line.SPACE else cp)
                line.combiningAt(c)?.let { sb.append(it) }
                if (sb.length - before != 1 || before != c) simple = false
                if (map.size < sb.length) map = map.copyOf(sb.length * 2)
                for (k in before until sb.length) map[k] = c
            }
            var end = sb.length
            // A row the next one continues keeps its trailing blanks: they're the line's.
            if (!continues) while (end > 0 && sb[end - 1] == ' ') end--
            return RowText(sb.substring(0, end), if (simple) null else map.copyOf(end), continues)
        }
    }

    /**
     * The query as a matcher: smart-case, literal unless [pattern]. Null when a
     * pattern doesn't parse (the bar says so) or the query is empty.
     */
    fun compile(query: String, pattern: Boolean): Regex? {
        if (query.isEmpty()) return null
        val exact = query.any { it.isUpperCase() }
        val options = if (exact) emptySet() else setOf(RegexOption.IGNORE_CASE)
        return try {
            Regex(if (pattern) query else Regex.escape(query), options)
        } catch (_: Exception) {
            null
        }
    }

    /** Every place [matcher] surfaces in [snapshot], oldest first. No lock needed. */
    fun find(snapshot: Snapshot, matcher: Regex): List<Hit> {
        val hits = ArrayList<Hit>()
        val rows = snapshot.rows
        var i = 0
        val joined = StringBuilder()
        while (i < rows.size && hits.size < MAX_HITS) {
            // One printed line: this row plus every row a soft wrap carried it onto.
            var j = i
            while (j < rows.size - 1 && rows[j].continues) j++
            val text: String
            if (i == j) {
                text = rows[i].text
            } else {
                joined.setLength(0)
                for (k in i..j) joined.append(rows[k].text)
                text = joined.toString()
            }
            if (text.isNotEmpty()) {
                var located: IntArray? = null // char → (row index << 16 | col), built on first hit
                for (m in matcher.findAll(text)) {
                    if (m.range.isEmpty()) continue
                    val at = located ?: locate(rows, i, j, text.length).also { located = it }
                    val a = at[m.range.first]
                    val b = at[m.range.last]
                    hits += Hit(
                        snapshot.firstLine + (a ushr 16), a and 0xFFFF,
                        snapshot.firstLine + (b ushr 16), b and 0xFFFF,
                    )
                    if (hits.size >= MAX_HITS) break
                }
            }
            i = j + 1
        }
        return hits
    }

    /** For each char of the printed line rows [from]..[to]: its row index and cell, packed. */
    private fun locate(rows: Array<RowText>, from: Int, to: Int, length: Int): IntArray {
        val out = IntArray(length)
        var o = 0
        for (r in from..to) {
            val row = rows[r]
            val map = row.cellOf
            for (k in row.text.indices) {
                if (o >= length) break
                out[o++] = (r shl 16) or (map?.getOrNull(k) ?: k)
            }
        }
        return out
    }

    /** Convenience for one-shot callers (tests): copy and search in one go, plain text. */
    fun find(screen: ScreenBuffer, query: String): List<Hit> {
        val matcher = compile(query, pattern = false) ?: return emptyList()
        return find(snapshot(screen), matcher)
    }

    /**
     * After a refresh, which hit the eye stays on: the same words if they're still
     * there, else the first one after where it was, else the newest.
     */
    fun follow(previous: SearchState, hits: List<Hit>): Int {
        if (hits.isEmpty()) return -1
        val was = previous.hits.getOrNull(previous.current) ?: return hits.lastIndex
        if (previous.current == previous.hits.lastIndex) return hits.lastIndex
        val same = hits.indexOfFirst { it.line == was.line && it.start == was.start }
        if (same >= 0) return same
        val after = hits.indexOfFirst { it.line > was.line || (it.line == was.line && it.start > was.start) }
        return if (after >= 0) after else hits.lastIndex
    }
}
