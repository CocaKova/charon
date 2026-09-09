package com.cocakova.charon.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchEngineTest {

    /** A screen with [rows] on the live grid and [hist] lines pushed into scrollback. */
    private fun screenOf(
        cols: Int,
        rows: Int,
        hist: List<String>,
        vararg live: String,
    ): ScreenBuffer {
        val term = TerminalEmulator(cols, rows, scrollbackLines = 64)
        // History first, so the evictions land in scrollback in order.
        for (line in hist + live) {
            term.write((line + "\r\n").toByteArray())
        }
        return term.screen
    }

    @Test
    fun `finds a hit on the live grid with inclusive columns`() {
        val s = screenOf(20, 4, emptyList(), "hello world", "second line")
        val hits = SearchEngine.find(s, "world")
        assertEquals(1, hits.size)
        assertEquals(SearchEngine.Hit(0, 6, 10), hits[0])
    }

    @Test
    fun `case folds without a flag`() {
        val s = screenOf(20, 4, emptyList(), "HeLLo WoRLD")
        val hits = SearchEngine.find(s, "hello world")
        assertEquals(1, hits.size)
        assertEquals(0, hits[0].start)
    }

    @Test
    fun `repeated hits on one line come back left to right non overlapping`() {
        val s = screenOf(30, 4, emptyList(), "ab ab ab")
        val hits = SearchEngine.find(s, "ab")
        assertEquals(listOf(0, 3, 6), hits.map { it.start })
    }

    @Test
    fun `scrollback rows are negative and oldest first, the live grid zero up`() {
        // 2 history lines evicted by 3 live rows' trailing LFs on a 4-row grid.
        val s = screenOf(20, 4, listOf("alpha one", "beta two"), "a", "b", "c")
        assertEquals(2, s.scrollbackSize)
        val alpha = SearchEngine.find(s, "alpha")
        val live = SearchEngine.find(s, "c")
        assertEquals(-2, alpha.single().row) // oldest scrollback = -scrollbackSize
        assertEquals(2, live.single().row)   // third live row
    }

    @Test
    fun `the newest scrollback row is minus one`() {
        val s = screenOf(20, 4, listOf("alpha one", "beta two"), "a", "b", "c")
        assertEquals(-1, SearchEngine.find(s, "beta").single().row)
    }

    @Test
    fun `no match is an empty list, and a blank query is too`() {
        val s = screenOf(20, 4, emptyList(), "hello")
        assertTrue(SearchEngine.find(s, "zzz").isEmpty())
        assertTrue(SearchEngine.find(s, "").isEmpty())
    }

    @Test
    fun `a hit inside the alternate screen is found after the swap`() {
        val term = TerminalEmulator(20, 4, scrollbackLines = 64)
        term.write("hello world\r\n".toByteArray())
        term.write("[?1049h".toByteArray()) // alt screen on
        term.write("[1;1H".toByteArray())
        term.write("mark here".toByteArray())
        val hits = SearchEngine.find(term.screen, "mark")
        assertEquals(1, hits.size)
        assertEquals(0, hits[0].row)
        // The primary-screen line is gone while the alt screen holds — by design.
        assertTrue(SearchEngine.find(term.screen, "hello").isEmpty())
    }
}
