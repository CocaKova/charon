package com.cocakova.charon.terminal

import java.util.BitSet

/**
 * Marked passages — the OSC 8 hyperlinks a terminal's cells point at. Cells carry a
 * small integer ([CellExt.linkId]), never a String; this table interns each URI once
 * and hands the id out to every cell written while the link is open.
 *
 * Identity follows the spec: two opens with the same `id=` param *and* the same URI
 * are one link (a link split across lines, or redrawn in pieces by a TUI, highlights
 * as a whole). Opens without an `id=` are keyed by URI alone — the same target twice
 * is the same link, which is also what keeps `ls --hyperlink` redraws from minting a
 * fresh entry per frame.
 *
 * Remote-controlled, so everything is capped: URI and id length, entry count, and
 * the total characters held. A full table refuses new links (the text still prints,
 * just unlinked) until the emulator sweeps out ids no cell references any more.
 */
class HyperlinkTable(
    private val capacity: Int = MAX_LINKS,
    private val charBudget: Int = MAX_TOTAL_CHARS,
) {
    // Slot i holds link id i's key: the URI, or "<id>\u0000<URI>" for an id'd link.
    // URIs never contain control chars (refused below), so the NUL split is safe.
    private val keys = arrayOfNulls<String>(capacity + 1)
    private val index = HashMap<String, Int>()
    private var nextHint = 1
    private var chars = 0

    /** Live entries. */
    val size: Int get() = index.size

    /** True when a new URI would be refused — the emulator's cue to sweep. */
    val isFull: Boolean get() = index.size >= capacity || chars >= charBudget

    /**
     * The id for ([uri], [idParam]), interning it if new. 0 when the link is refused:
     * empty, overlong, carrying control characters, or the table is full.
     */
    fun intern(uri: String, idParam: String?): Int {
        if (uri.isEmpty() || uri.length > MAX_URI_LENGTH || uri.any { it < ' ' || it in '\u007F'..'\u009F' }) return 0
        val id = idParam?.takeIf { it.isNotEmpty() }
        if (id != null && (id.length > MAX_ID_LENGTH || id.any { it < ' ' })) return 0
        val key = if (id == null) uri else id + '\u0000' + uri
        index[key]?.let { return it }
        if (index.size >= capacity || chars + key.length > charBudget) return 0
        val slot = freeSlot()
        keys[slot] = key
        index[key] = slot
        chars += key.length
        return slot
    }

    /** The URI behind [id], or null for 0 / a swept id. */
    fun uri(id: Int): String? {
        if (id <= 0 || id > capacity) return null
        val key = keys[id] ?: return null
        val nul = key.indexOf('\u0000')
        return if (nul < 0) key else key.substring(nul + 1)
    }

    /** Drop every entry whose id isn't set in [live]; ids are reusable afterwards. */
    fun retainOnly(live: BitSet) {
        for (slot in 1..capacity) {
            val key = keys[slot] ?: continue
            if (live.get(slot)) continue
            keys[slot] = null
            index.remove(key)
            chars -= key.length
        }
        nextHint = 1
    }

    fun clear() {
        keys.fill(null)
        index.clear()
        chars = 0
        nextHint = 1
    }

    private fun freeSlot(): Int {
        var s = nextHint
        while (keys[s] != null) s = if (s == capacity) 1 else s + 1
        nextHint = if (s == capacity) 1 else s + 1
        return s
    }

    companion object {
        /** Entries held at once. Far past a screen of `ls --hyperlink`; ids fit [CellExt]. */
        const val MAX_LINKS = 4096

        /** The spec asks terminals to take at least 2083 bytes; a bit of headroom. */
        const val MAX_URI_LENGTH = 4096

        /** The spec's suggested cap for `id=`. */
        const val MAX_ID_LENGTH = 250

        /** Total characters across every key — a hostile stream can't make this 16 MB. */
        const val MAX_TOTAL_CHARS = 1 shl 20
    }
}
