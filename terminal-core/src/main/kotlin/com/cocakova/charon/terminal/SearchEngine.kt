package com.cocakova.charon.terminal

/**
 * The dredge — search in the held water. One pass over every line the buffer
 * keeps (scrollback oldest-first, then the live grid) finds each place the query
 * surfaces. Matching is case-insensitive substring, non-overlapping left to
 * right, which is what a phone eye wants; rows come back in selection space
 * (negative = scrollback, 0.. = the live grid — see ScreenBuffer.relativeLine)
 * so a hit maps straight onto the viewport through the scroll offset, the same
 * space the selection and copy already use.
 */
object SearchEngine {

    /** One match: [row] in selection space, columns [start]..[end] inclusive. */
    data class Hit(val row: Int, val start: Int, val end: Int)

    /** What the renderer needs: every hit plus which one the eye is on. */
    data class SearchState(val hits: List<Hit>, val current: Int)

    /** Every place [query] surfaces across scrollback and the live grid. */
    fun find(screen: ScreenBuffer, query: String): List<Hit> {
        if (query.isEmpty()) return emptyList()
        val hits = ArrayList<Hit>()
        for (row in -screen.scrollbackSize until screen.rows) {
            val text = screen.relativeLine(row).toText()
            if (text.isEmpty()) continue
            var from = 0
            while (true) {
                val at = text.indexOf(query, from, ignoreCase = true)
                if (at < 0) break
                hits += Hit(row, at, at + query.length - 1)
                from = at + query.length
            }
        }
        return hits
    }
}
