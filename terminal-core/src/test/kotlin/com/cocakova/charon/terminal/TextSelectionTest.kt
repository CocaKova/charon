package com.cocakova.charon.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

class TextSelectionTest {

    /** Feed [rows] into a fresh screen via an emulator so wrap flags/attrs are real. */
    private fun screenOf(cols: Int, vararg rows: String): ScreenBuffer {
        val term = TerminalEmulator(cols, rows.size)
        for ((r, text) in rows.withIndex()) {
            term.write("\u001B[${r + 1};1H".toByteArray()) // cursor to row start
            term.write(text.toByteArray())
        }
        return term.screen
    }

    @Test
    fun `single row selection trims trailing spaces`() {
        val s = screenOf(20, "hello world")
        val out = TextSelection.extract(
            s, TextSelection.Cell(0, 0), TextSelection.Cell(0, 19),
        )
        assertEquals("hello world", out)
    }

    @Test
    fun `partial columns`() {
        val s = screenOf(20, "hello world")
        val out = TextSelection.extract(
            s, TextSelection.Cell(0, 6), TextSelection.Cell(0, 10),
        )
        assertEquals("world", out)
    }

    @Test
    fun `reversed endpoints normalize`() {
        val s = screenOf(20, "hello world")
        val out = TextSelection.extract(
            s, TextSelection.Cell(0, 10), TextSelection.Cell(0, 6),
        )
        assertEquals("world", out)
    }

    @Test
    fun `hard line break joins with newline`() {
        val s = screenOf(20, "alpha", "beta")
        val out = TextSelection.extract(
            s, TextSelection.Cell(0, 0), TextSelection.Cell(1, 19),
        )
        assertEquals("alpha\nbeta", out)
    }

    @Test
    fun `soft wrap joins without newline`() {
        // 5-wide, 2-row grid: a 9-char run fills row 0 and autowraps onto row 1,
        // which the emulator flags isWrapped.
        val term = TerminalEmulator(5, 2)
        term.write("abcdefghi".toByteArray())
        val out = TextSelection.extract(
            term.screen, TextSelection.Cell(0, 0), TextSelection.Cell(1, 4),
        )
        assertEquals("abcdefghi", out)
    }

    @Test
    fun `selection reads scrollback when scrolled up`() {
        // 2-row grid: writing four lines pushes the first two into scrollback.
        val term = TerminalEmulator(10, 2)
        term.write("line1\r\nline2\r\nline3\r\nline4".toByteArray())
        // Live view shows line3/line4; scroll up 2 to reach line1/line2.
        val out = TextSelection.extract(
            term.screen, TextSelection.Cell(0, 0), TextSelection.Cell(1, 9), scrollOffset = 2,
        )
        assertEquals("line1\nline2", out)
    }

    @Test
    fun `wordAt selects an identifier-ish run and paths stay whole`() {
        val s = screenOf(40, "cat /etc/hosts now")
        val line = s.line(0)
        // inside "/etc/hosts"
        val w = TextSelection.wordAt(line, 8)
        assertEquals("/etc/hosts", TextSelection.extract(s,
            TextSelection.Cell(0, w.first), TextSelection.Cell(0, w.last)))
        // on the space between words -> just that cell
        val sp = TextSelection.wordAt(line, 3)
        assertEquals(3..3, sp)
    }
}
