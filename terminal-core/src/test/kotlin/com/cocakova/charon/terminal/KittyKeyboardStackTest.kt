package com.cocakova.charon.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

private const val E = "\u001B"

/** The Kitty keyboard protocol's mode stack, as driven over the wire. */
class KittyKeyboardStackTest {

    private class Rig {
        val responses = mutableListOf<String>()
        val term = TerminalEmulator(20, 5, 10, onResponse = { responses.add(it) })
        fun feed(s: String) = term.write(s)
        fun query(): String {
            feed("$E[?u")
            return responses.last()
        }
    }

    @Test
    fun startsLegacyAndQueryReportsZero() {
        val r = Rig()
        assertEquals(0, r.term.kittyKeyboardFlags)
        assertEquals("$E[?0u", r.query())
    }

    @Test
    fun pushSetsFlagsAndPopRestoresThePrevious() {
        val r = Rig()
        r.feed("$E[>1u")
        assertEquals(1, r.term.kittyKeyboardFlags)
        r.feed("$E[>5u")
        assertEquals("$E[?5u", r.query())
        r.feed("$E[<u") // pop defaults to one entry
        assertEquals(1, r.term.kittyKeyboardFlags)
        r.feed("$E[<1u")
        assertEquals(0, r.term.kittyKeyboardFlags)
    }

    @Test
    fun pushWithoutFlagsPushesZero() {
        val r = Rig()
        r.feed("$E[>3u$E[>u")
        assertEquals(0, r.term.kittyKeyboardFlags)
        r.feed("$E[<u")
        assertEquals(3, r.term.kittyKeyboardFlags)
    }

    @Test
    fun popPastTheBottomEmptiesTheStack() {
        val r = Rig()
        r.feed("$E[>1u$E[>3u$E[<10u")
        assertEquals(0, r.term.kittyKeyboardFlags)
        r.feed("$E[<u") // popping an empty stack is harmless
        assertEquals("$E[?0u", r.query())
    }

    @Test
    fun explicitZeroPopCountPopsOne() {
        val r = Rig()
        r.feed("$E[>1u$E[>3u$E[<0u")
        assertEquals(1, r.term.kittyKeyboardFlags)
    }

    @Test
    fun fullStackEvictsTheOldestEntry() {
        val r = Rig()
        // Fill past capacity with distinct values, then unwind: the first entry is gone.
        for (i in 0 until KittyKeyboardStack.CAPACITY + 1) r.feed("$E[>${(i % 31) + 1}u")
        r.feed("$E[<${KittyKeyboardStack.CAPACITY - 1}u")
        assertEquals(2, r.term.kittyKeyboardFlags) // entry #1 (value 1) was evicted
        r.feed("$E[<u")
        assertEquals(0, r.term.kittyKeyboardFlags)
    }

    @Test
    fun setModesReplaceOrAndClear() {
        val r = Rig()
        r.feed("$E[>1u")
        r.feed("$E[=8;2u") // OR in
        assertEquals(9, r.term.kittyKeyboardFlags)
        r.feed("$E[=1;3u") // clear bits
        assertEquals(8, r.term.kittyKeyboardFlags)
        r.feed("$E[=5u") // mode defaults to 1: replace
        assertEquals(5, r.term.kittyKeyboardFlags)
        r.feed("$E[<u") // set modified the top entry, it didn't push one
        assertEquals(0, r.term.kittyKeyboardFlags)
    }

    @Test
    fun setOnAnEmptyStackCreatesTheEntry() {
        val r = Rig()
        r.feed("$E[=3u")
        assertEquals(3, r.term.kittyKeyboardFlags)
        r.feed("$E[<u")
        assertEquals(0, r.term.kittyKeyboardFlags)
    }

    @Test
    fun unknownSetModeIsIgnored() {
        val r = Rig()
        r.feed("$E[>1u$E[=8;7u")
        assertEquals(1, r.term.kittyKeyboardFlags)
    }

    @Test
    fun undefinedBitsAreMasked() {
        val r = Rig()
        r.feed("$E[>255u")
        assertEquals(31, r.term.kittyKeyboardFlags)
    }

    @Test
    fun mainAndAlternateScreensKeepSeparateStacks() {
        val r = Rig()
        r.feed("$E[>1u")
        r.feed("$E[?1049h") // into the alternate screen: its own, empty stack
        assertEquals(0, r.term.kittyKeyboardFlags)
        r.feed("$E[>15u")
        assertEquals("$E[?15u", r.query())
        r.feed("$E[?1049l") // back out: the shell's flags, untouched by the TUI
        assertEquals(1, r.term.kittyKeyboardFlags)
        r.feed("$E[?1049h") // the alternate stack is kept across visits, as in kitty
        assertEquals(15, r.term.kittyKeyboardFlags)
    }

    @Test
    fun risClearsBothStacks() {
        val r = Rig()
        r.feed("$E[>1u$E[?1049h$E[>3u")
        r.feed("${E}c")
        assertEquals(0, r.term.kittyKeyboardFlags)
        r.feed("$E[?1049h")
        assertEquals(0, r.term.kittyKeyboardFlags)
    }

    @Test
    fun decstrClearsBothStacks() {
        val r = Rig()
        r.feed("$E[>1u$E[?1049h$E[>3u")
        r.feed("$E[!p")
        assertEquals(0, r.term.kittyKeyboardFlags)
        r.feed("$E[?1049l")
        assertEquals(0, r.term.kittyKeyboardFlags)
    }

    @Test
    fun plainCsiUStillRestoresTheCursor() {
        val r = Rig()
        r.feed("$E[2;3H$E[s$E[5;5H$E[u")
        assertEquals(2, r.term.cursorX)
        assertEquals(1, r.term.cursorY)
        assertEquals(0, r.term.kittyKeyboardFlags)
    }

    @Test
    fun xtversionStillNamesTheFerry() {
        val r = Rig()
        r.feed("$E[>q")
        assertEquals("${E}P>|Charon(1.0)$E\\", r.responses.last())
    }
}
