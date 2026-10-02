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
        assertEquals(SearchEngine.Hit(s.linesPushed, 6, s.linesPushed, 10), hits[0])
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
        assertEquals(-2, alpha.single().row(s.linesPushed)) // oldest scrollback = -scrollbackSize
        assertEquals(2, live.single().row(s.linesPushed))   // third live row
    }

    @Test
    fun `the newest scrollback row is minus one`() {
        val s = screenOf(20, 4, listOf("alpha one", "beta two"), "a", "b", "c")
        assertEquals(-1, SearchEngine.find(s, "beta").single().row(s.linesPushed))
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
        assertEquals(0, hits[0].row(term.screen.linesPushed))
        // The primary-screen line is gone while the alt screen holds — by design.
        assertTrue(SearchEngine.find(term.screen, "hello").isEmpty())
    }

    private fun patternHits(s: ScreenBuffer, q: String) =
        SearchEngine.find(SearchEngine.snapshot(s), SearchEngine.compile(q, pattern = true)!!)

    @Test
    fun `a pattern is a regex, and a bad one doesn't parse`() {
        val s = screenOf(30, 4, emptyList(), "error 42 at line 7", "warn 3")
        assertEquals(listOf(6, 17), patternHits(s, "\\d+").filter { it.row(s.linesPushed) == 0 }.map { it.start })
        assertEquals(listOf(0), patternHits(s, "^warn").map { it.start })
        assertEquals(null, SearchEngine.compile("(unclosed", pattern = true))
        // As plain text the same query is a literal, parens and all.
        assertTrue(SearchEngine.compile("(unclosed", pattern = false) != null)
    }

    @Test
    fun `smart case - a capital asks for exact case`() {
        val s = screenOf(30, 4, emptyList(), "Error error ERROR")
        assertEquals(3, SearchEngine.find(s, "error").size)
        assertEquals(listOf(0), SearchEngine.find(s, "Error").map { it.start })
    }

    @Test
    fun `a match the grid wrapped over two rows is one hit spanning both`() {
        val term = TerminalEmulator(10, 4, scrollbackLines = 64)
        term.write("12345needle678\r\n".toByteArray())
        // row 0 "12345needl", row 1 "e678"
        val hit = SearchEngine.find(term.screen, "needle").single()
        val base = term.screen.linesPushed
        assertEquals(0, hit.row(base))
        assertEquals(5, hit.start)
        assertEquals(base + 1, hit.endLine)
        assertEquals(0, hit.end)
    }

    @Test
    fun `wide glyphs are two cells - hits land on the words`() {
        val term = TerminalEmulator(30, 4, scrollbackLines = 64)
        term.write("界界 needle 界".toByteArray())
        val hit = SearchEngine.find(term.screen, "needle").single()
        assertEquals(5, hit.start)
        assertEquals(10, hit.end)
    }

    @Test
    fun `a hit keeps its lasting number while output scrolls the grid`() {
        val term = TerminalEmulator(20, 3, scrollbackLines = 64)
        term.write("find me\r\n".toByteArray())
        val before = SearchEngine.find(term.screen, "find").single()
        term.write("a\r\nb\r\nc\r\nd\r\n".toByteArray())
        val after = SearchEngine.find(term.screen, "find").single()
        assertEquals(before.line, after.line)
        assertTrue(after.row(term.screen.linesPushed) < 0) // now in scrollback
    }

    @Test
    fun `the snapshot is a copy - the scan can run while the grid moves on`() {
        val term = TerminalEmulator(20, 3, scrollbackLines = 64)
        term.write("needle\r\n".toByteArray())
        val snap = SearchEngine.snapshot(term.screen)
        term.write("\u001b[2J\u001b[3J".toByteArray())
        assertEquals(1, SearchEngine.find(snap, SearchEngine.compile("needle", false)!!).size)
        assertTrue(SearchEngine.find(term.screen, "needle").isEmpty())
    }

    @Test
    fun `after a refresh the eye stays on its words, or the newest if it was there`() {
        fun h(line: Long, start: Int) = SearchEngine.Hit(line, start, line, start + 1)
        val prev = SearchEngine.SearchState(listOf(h(1, 0), h(5, 2), h(9, 0)), current = 1)
        assertEquals(2, SearchEngine.follow(prev, listOf(h(0, 0), h(1, 0), h(5, 2), h(9, 0))))
        assertEquals(1, SearchEngine.follow(prev, listOf(h(1, 0), h(6, 0), h(9, 0)))) // gone: the next one
        val atNewest = prev.copy(current = 2)
        assertEquals(3, SearchEngine.follow(atNewest, listOf(h(1, 0), h(5, 2), h(9, 0), h(12, 0))))
    }
}
