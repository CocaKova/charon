package com.cocakova.charon.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val E = "\u001B"

/**
 * The keyboard rising is a height shrink. On the primary screen the cursor's line
 * must stay on the glass: lines leave over the top into scrollback (as if the screen
 * had scrolled), and the keyboard falling pulls them back.
 */
class ResizeTest {

    private fun term(cols: Int = 12, rows: Int = 10) = TerminalEmulator(cols, rows, 100)

    /** A full screen: lines L0..L(n-2), then a prompt on the last row. */
    private fun fullScreenWithPrompt(t: TerminalEmulator, rows: Int = 10) {
        for (i in 0 until rows - 1) t.write("L$i\r\n")
        t.write("me@host:~$ ")
    }

    private fun texts(t: TerminalEmulator) = t.screen.toText()

    @Test
    fun shrinkPushesTopLinesIntoScrollbackAndKeepsThePrompt() {
        val t = term()
        fullScreenWithPrompt(t)
        assertEquals(9, t.cursorY)
        t.resize(12, 6)
        assertEquals(5, t.cursorY, "the cursor rides with its line")
        assertEquals("me@host:~$", t.screen.line(5).toText())
        assertEquals(11, t.cursorX)
        assertEquals(4, t.primary.scrollbackSize)
        assertEquals("L3", t.primary.scrollbackLine(3).toText())
        assertEquals(listOf("L4", "L5", "L6", "L7", "L8", "me@host:~$"), texts(t))
    }

    @Test
    fun growPullsBackWhatTheShrinkPushed() {
        val t = term()
        fullScreenWithPrompt(t)
        val before = texts(t)
        t.resize(12, 6)
        t.resize(12, 10)
        assertEquals(before, texts(t))
        assertEquals(9, t.cursorY)
        assertEquals(0, t.primary.scrollbackSize)
    }

    @Test
    fun blankRowsBelowTheCursorGoFirst() {
        val t = term()
        t.write("one\r\ntwo\r\n$ ")
        t.resize(12, 5)
        assertEquals(2, t.cursorY)
        assertEquals(0, t.primary.scrollbackSize, "nothing needed to leave over the top")
        assertEquals(listOf("one", "two", "$", "", ""), texts(t))
    }

    @Test
    fun contentBelowTheCursorGivesWayOnlyAfterTheTopIsSpent() {
        val t = term(rows = 6)
        // Text on the bottom row, cursor parked on row 1 (a TUI-less redraw, a `less` that quit).
        t.write("top\r\nhere$E[6;1Hbottom$E[2;5H")
        assertEquals(1, t.cursorY)
        t.resize(12, 3)
        // One row may leave over the top (the cursor's line must stay); the rest goes from below.
        assertEquals(0, t.cursorY)
        assertEquals("here", t.screen.line(0).toText())
        assertEquals("top", t.primary.scrollbackLine(0).toText())
        assertEquals(1, t.primary.scrollbackSize)
    }

    @Test
    fun aBackgroundPaintedRowIsNotBlank() {
        val t = term(rows = 4)
        // a / the prompt (cursor) / a row painted blue with no text / a blank row.
        t.write("a\r\n$ $E[3;1H$E[44m$E[K$E[0m$E[2;3H")
        assertEquals(1, t.cursorY)
        t.resize(12, 2)
        // Only the true blank row may be dropped from below; the painted row is a
        // drawing, so the top row leaves over the top instead.
        assertEquals("a", t.primary.scrollbackLine(0).toText())
        assertEquals("$", t.screen.line(0).toText())
        assertEquals(CellAttrs.MODE_PALETTE, CellAttrs.bgMode(t.screen.line(1).attrs[0]))
        assertEquals(0, t.cursorY)
    }

    @Test
    fun aClearForgivesTheDebt() {
        val t = term()
        fullScreenWithPrompt(t)
        t.resize(12, 6)
        t.write("$E[H$E[2J$ ")
        t.resize(12, 10)
        assertEquals("$", t.screen.line(0).toText(), "history must not drop back above a wiped screen")
        assertEquals(0, t.cursorY)
        assertEquals(4, t.primary.scrollbackSize)
    }

    @Test
    fun theAlternateScreenKeepsItsTopRows() {
        val t = term()
        fullScreenWithPrompt(t)
        t.write("$E[?1049h")
        t.write("tui-top$E[10;1Htui-bottom")
        t.resize(12, 6)
        assertEquals("tui-top", t.screen.line(0).toText())
        assertEquals(5, t.cursorY)
        // The primary underneath kept its prompt line; leaving the TUI lands on it.
        t.write("$E[?1049l")
        assertEquals("me@host:~$", t.screen.line(t.cursorY).toText())
        assertEquals(11, t.cursorX)
    }

    @Test
    fun theKeyboardCycleIsStableAcrossOutput() {
        val t = term()
        fullScreenWithPrompt(t)
        t.resize(12, 6)
        t.write("\r\nout1\r\nout2\r\n$ ")
        t.resize(12, 10)
        // Bottom-anchored like a taller terminal would have been all along.
        assertEquals("$", t.screen.line(9).toText())
        assertEquals("out2", t.screen.line(8).toText())
        assertEquals(9, t.cursorY)
        assertTrue(t.primary.scrollbackSize >= 3)
    }

    @Test
    fun repeatedCyclesNeverLoseOrDuplicateLines() {
        val t = term()
        fullScreenWithPrompt(t)
        val before = texts(t)
        repeat(20) {
            t.resize(12, 4 + it % 5)
            t.resize(12, 10)
        }
        assertEquals(before, texts(t))
        assertEquals(0, t.primary.scrollbackSize)
    }
}
