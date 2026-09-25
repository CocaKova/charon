package com.cocakova.charon.presentation.terminal

/**
 * The predictive bridge, with no Android in it.
 *
 * An IME edits a text field; a terminal has a line editor on the far side of a wire
 * whose only cursor sits at the end of the line. This class holds the whole mapping
 * between those two worlds so it can be reasoned about — and tested — on its own.
 *
 * The model:
 * - [relayed] is what the *current composition* has already put on the wire.
 * - [wireTail] is committed text sitting between the composition and the end of the
 *   line. It is blank by construction (an autocorrect's trailing space); it is
 *   replayed after every diff so re-opening a finished word keeps its space.
 * - [offWire] means the IME is editing somewhere the end-of-line cursor cannot
 *   reach. Then we send *nothing* and ask for a resync. The one thing that must
 *   never happen is re-typing text the terminal already has — that is how first
 *   words doubled.
 */
class PredictiveWire {

    /** Bytes the wire should receive: [backspaces] DELs, then [insert]. */
    data class Op(val backspaces: Int, val insert: String) {
        val isEmpty: Boolean get() = backspaces == 0 && insert.isEmpty()

        companion object {
            val NONE = Op(0, "")
        }
    }

    var relayed: String = ""
        private set

    var wireTail: String = ""
        private set

    var offWire: Boolean = false
        private set

    /** Raised when the IME's edit could not be mirrored and the connection must
     *  restart. Fired at most once per off-wire episode. */
    var onNeedsResync: (() -> Unit)? = null

    /** True when the bridge is carrying anything a later commit could land on. */
    fun holdsState(mirrorLength: Int): Boolean = mirrorLength > 0 || relayed.isNotEmpty()

    /** The line left for the remote (Enter) or the connection restarted. */
    fun reset() {
        relayed = ""
        wireTail = ""
        offWire = false
    }

    /**
     * The IME re-opened a span of already-committed text for editing — the
     * autocorrect / tap-a-prediction path. If the span is the last thing on the
     * line (everything after it is blank) the wire's backspaces can reach it, so
     * we re-anchor the diff onto it. Otherwise we go off-wire.
     *
     * [mirror] is the field content, [lo]/[hi] the span.
     */
    fun composingRegion(mirror: String, lo: Int, hi: Int) {
        val tail = mirror.substring(hi.coerceIn(0, mirror.length))
        if (tail.isBlank()) {
            relayed = mirror.substring(lo.coerceIn(0, mirror.length), hi.coerceIn(0, mirror.length))
            wireTail = tail
            offWire = false
        } else {
            goOffWire()
        }
    }

    /**
     * A composition or commit is about to begin with no composing span open: it
     * lands at the IME's cursor, replacing the selection [start]..[end] if there is
     * one. That maps to the wire only when everything after the selection is blank —
     * otherwise the insert would happen mid-line, where the wire cursor cannot go.
     *
     * A selection is exactly a composing region the IME opened by other means: the
     * default `InputConnection.replaceText` (Android 14+) is
     * `finishComposingText → setSelection(start, end) → commitText`, so a word picked
     * that way must retract the selected text, not land beside it.
     */
    fun anchorAtCursor(mirror: String, start: Int, end: Int = start) {
        val lo = minOf(start, end).coerceIn(0, mirror.length)
        val hi = maxOf(start, end).coerceIn(0, mirror.length)
        val tail = mirror.substring(hi)
        if (tail.isBlank()) {
            relayed = mirror.substring(lo, hi)
            wireTail = tail
            offWire = false
        } else {
            goOffWire()
        }
    }

    /** The composition is now [text]: what the wire must do to agree. */
    fun compose(text: String): Op {
        val op = relay(text)
        relayed = if (offWire) "" else text
        return op
    }

    /** [text] replaces the composition and becomes committed; the wire cursor ends
     *  at the end of the line either way, so the bridge starts over afterwards. */
    fun commit(text: String): Op {
        val op = relay(text)
        settle()
        return op
    }

    /** The composition ends as it stands: everything relayed already landed. */
    fun finishComposing(): Op {
        settle()
        return Op.NONE
    }

    /**
     * The IME deleted [codePoints] of text ending at [at] — the selection start, or
     * the composition's start when one is open (a deletion never eats the
     * composition; that is `BaseInputConnection`'s rule, and the editable follows
     * it). Whatever sits between [at] and the line's end must come off the wire and
     * go back on around the deletion. That is safe only when it is the open
     * composition plus its blank tail — text this bridge itself put there. Anything
     * else is off-wire.
     */
    fun deleteBefore(mirror: String, at: Int, codePoints: Int): Op {
        if (offWire) return Op.NONE
        val after = mirror.substring(at.coerceIn(0, mirror.length))
        if (after.isNotEmpty() && after != relayed + wireTail) {
            goOffWire()
            return Op.NONE
        }
        if (codePoints <= 0) return Op.NONE
        return Op(codePoints + after.codePointCount(0, after.length), after)
    }

    /**
     * The IME deleted text *after* its cursor. The wire cursor sits at the line's
     * end, so there is never anything after it to reach: off-wire whenever the
     * deletion actually removes something.
     */
    fun deleteAfter(codePoints: Int) {
        if (codePoints > 0) goOffWire()
    }

    /**
     * A DEL key event went straight to the wire, bypassing the connection: the last
     * character of the line is gone. The mirror drops its last character to match
     * (true when there is one), and the bridge forgets that character too — from the
     * blank tail first, since that is what sits at the end of the line.
     */
    fun rawBackspace(mirrorLength: Int): Boolean {
        if (wireTail.isNotEmpty()) wireTail = wireTail.dropLastCodePoint()
        else if (relayed.isNotEmpty()) relayed = relayed.dropLastCodePoint()
        return mirrorLength > 0
    }

    /**
     * A printable key event went straight to the wire and landed at the end of the
     * line. Any open composition is now committed ahead of it — it can no longer
     * be retracted by a diff — so the bridge starts over after the new character.
     */
    fun rawTyped() {
        if (offWire) return
        settle()
    }

    private fun relay(new: String): Op {
        if (offWire) return Op.NONE
        return plan(relayed + wireTail, new + wireTail)
    }

    private fun settle() {
        relayed = ""
        wireTail = ""
        offWire = false
    }

    private fun goOffWire() {
        if (offWire) return
        offWire = true
        relayed = ""
        wireTail = ""
        onNeedsResync?.invoke()
    }

    companion object {
        private fun String.dropLastCodePoint(): String =
            if (length >= 2 && Character.isSurrogatePair(this[length - 2], this[length - 1])) dropLast(2)
            else dropLast(1)

        /**
         * Replace [old] with [new] on a line whose only cursor is at its end:
         * retract the differing tail with backspaces, then type the new one.
         * Backspaces are counted in code points — the remote line editor deletes
         * a character, not a UTF-16 unit.
         */
        fun plan(old: String, new: String): Op {
            var common = 0
            val max = minOf(old.length, new.length)
            while (common < max && old[common] == new[common]) common++
            // Never split a surrogate pair at the diff point.
            if (common > 0 && Character.isHighSurrogate(old[common - 1])) common--
            val retract = if (common < old.length) old.codePointCount(common, old.length) else 0
            val insert = if (new.length > common) new.substring(common) else ""
            return Op(retract, insert)
        }
    }
}
