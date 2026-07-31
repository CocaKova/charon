package com.cocakova.charon.presentation.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The predictive bridge, driven the way an IME drives it.
 *
 * Each test plays a real Gboard call sequence against a [mirror] that stands in for
 * the editable, and asserts what reached the wire. Two bugs live here and both are
 * pinned: a word typed once must never land twice, and a word the IME offers to
 * replace must actually be replaceable.
 */
class PredictiveWireTest {

    /** The wire's own transcript: what the far side ends up holding. */
    private class Wire {
        val sb = StringBuilder()

        fun apply(op: PredictiveWire.Op) {
            repeat(op.backspaces) { if (sb.isNotEmpty()) sb.deleteCharAt(sb.length - 1) }
            sb.append(op.insert)
        }

        override fun toString() = sb.toString()
    }

    private val wire = PredictiveWire()
    private val far = Wire()

    private fun compose(text: String) = far.apply(wire.compose(text))
    private fun commit(text: String) = far.apply(wire.commit(text))

    @Test
    fun typingAWordSendsOneCharacterAtATime() {
        wire.anchorAtCursor("", 0)
        compose("g")
        compose("gi")
        compose("git")
        assertEquals("git", far.toString())
    }

    @Test
    fun committingTheComposedWordOnlySendsWhatIsNew() {
        wire.anchorAtCursor("", 0)
        compose("git")
        commit("git ")
        assertEquals("git ", far.toString())
    }

    /**
     * The doubling bug. The IME finishes a word, then re-opens it to autocorrect —
     * the bridge must diff against what it already sent, not re-type the word.
     */
    @Test
    fun reopeningAFinishedWordCorrectsItInPlace() {
        wire.anchorAtCursor("", 0)
        compose("git")
        commit("git ")
        assertEquals("git ", far.toString())

        // Gboard re-opens "git" for correction; the mirror reads "git ".
        wire.composingRegion("git ", 0, 3)
        assertEquals("git", wire.relayed)
        assertEquals(" ", wire.wireTail)

        compose("gut")
        assertEquals("gut ", far.toString())
    }

    /** Tapping a prediction: the IME replaces the open composition wholesale. */
    @Test
    fun tappingAPredictionReplacesThePartialWord() {
        wire.anchorAtCursor("", 0)
        compose("gi")
        commit("git ")
        assertEquals("git ", far.toString())
    }

    /** ...and does so for a word that was already committed, trailing space kept. */
    @Test
    fun tappingAPredictionOnACommittedWordKeepsItsSpace() {
        wire.anchorAtCursor("", 0)
        compose("teh")
        commit("teh ")
        wire.composingRegion("teh ", 0, 3)
        commit("the")
        assertEquals("the ", far.toString())
    }

    /** A second word starts fresh — the first must not be re-sent. */
    @Test
    fun theSecondWordDoesNotDragTheFirstAlong() {
        wire.anchorAtCursor("", 0)
        compose("git")
        commit("git ")
        wire.anchorAtCursor("git ", 4)
        compose("s")
        compose("st")
        commit("status")
        assertEquals("git status", far.toString())
    }

    @Test
    fun backspaceOverCommittedTextAtLineEndRetracts() {
        wire.anchorAtCursor("", 0)
        compose("git")
        commit("git ")
        far.apply(wire.deleteBefore("git ", 4, 1))
        assertEquals("git", far.toString())
        assertFalse(wire.offWire)
    }

    @Test
    fun aDeletionAwayFromTheLineEndGoesOffWireInsteadOfGuessing() {
        var resyncs = 0
        wire.onNeedsResync = { resyncs++ }
        wire.anchorAtCursor("", 0)
        compose("git status")
        val before = far.toString()

        // Cursor sits mid-line; the wire's only cursor cannot reach there.
        far.apply(wire.deleteBefore("git status", 3, 1))
        assertEquals(before, far.toString())
        assertTrue(wire.offWire)
        assertEquals(1, resyncs)
    }

    @Test
    fun anEditMidLineNeverRetypesTheTail() {
        wire.anchorAtCursor("", 0)
        compose("git status")
        commit("git status")
        // The IME re-opens "git", which has real text after it.
        wire.composingRegion("git status", 0, 3)
        assertTrue(wire.offWire)

        val before = far.toString()
        compose("gut")
        commit("gut")
        assertEquals(before, far.toString())
    }

    @Test
    fun offWireClearsOnceTheLineSettles() {
        wire.anchorAtCursor("", 0)
        compose("git status")
        wire.composingRegion("git status", 0, 3)
        assertTrue(wire.offWire)
        commit("gut")
        assertFalse(wire.offWire)
        assertEquals("", wire.relayed)
    }

    @Test
    fun resetForgetsEverythingWhenTheLineLeaves() {
        wire.anchorAtCursor("", 0)
        compose("git")
        wire.reset()
        assertEquals("", wire.relayed)
        assertEquals("", wire.wireTail)
        assertFalse(wire.offWire)

        // A fresh line starts from nothing: the old word must not be retracted.
        wire.anchorAtCursor("", 0)
        val op = wire.compose("l")
        assertEquals(0, op.backspaces)
        assertEquals("l", op.insert)
    }

    /** A swiped word arrives as one composition, not a keystroke at a time. */
    @Test
    fun glideTypedWordArrivesWhole() {
        wire.anchorAtCursor("", 0)
        val op = wire.compose("status")
        assertEquals(0, op.backspaces)
        assertEquals("status", op.insert)
        far.apply(op)

        // Swiping again replaces the guess, retracting only the differing tail.
        val corrected = wire.compose("statuses")
        assertEquals(0, corrected.backspaces)
        assertEquals("es", corrected.insert)
    }

    @Test
    fun backspacesAreCountedInCodePointsNotUtf16Units() {
        // A single emoji is one character to the remote line editor, two chars here.
        val op = PredictiveWire.plan("hi🚀", "hi")
        assertEquals(1, op.backspaces)
        assertEquals("", op.insert)
    }

    @Test
    fun aSurrogatePairIsNeverSplitAtTheDiffPoint() {
        val op = PredictiveWire.plan("hi🚀", "hi😊")
        assertEquals(1, op.backspaces)
        assertEquals("😊", op.insert)
    }

    @Test
    fun holdsStateSeesBothMirrorAndWire() {
        assertFalse(wire.holdsState(0))
        assertTrue(wire.holdsState(3))
        wire.anchorAtCursor("", 0)
        wire.compose("g")
        assertTrue(wire.holdsState(0))
    }

    @Test
    fun aRawBackspaceOnlyMirrorsWhenNoCompositionIsOpen() {
        assertTrue(wire.mirrorsRawBackspace(3))
        wire.anchorAtCursor("", 0)
        wire.compose("g")
        assertFalse(wire.mirrorsRawBackspace(3))
    }
}
