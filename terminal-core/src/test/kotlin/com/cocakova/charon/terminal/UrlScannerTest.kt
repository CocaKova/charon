package com.cocakova.charon.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Plain passages: URLs printed as text, found under a finger, across soft wraps. */
class UrlScannerTest {

    @Test
    fun `a dev server's local line is one link`() {
        val hit = UrlScanner.at("  ➜  Local:   http://localhost:5173/", 20)
        assertEquals("http://localhost:5173/", hit?.url)
    }

    @Test
    fun `sentence punctuation and unpaired closers stay outside`() {
        assertEquals(listOf("https://x.dev/a"), UrlScanner.find("see https://x.dev/a.").map { it.url })
        assertEquals(listOf("https://x.dev/a"), UrlScanner.find("(see https://x.dev/a)").map { it.url })
        assertEquals(
            listOf("https://en.wikipedia.org/wiki/Styx_(river)"),
            UrlScanner.find("https://en.wikipedia.org/wiki/Styx_(river),").map { it.url },
        )
        assertEquals(listOf("https://q.dev/?a=1&b=2#top"), UrlScanner.find("'https://q.dev/?a=1&b=2#top'").map { it.url })
    }

    @Test
    fun `file links and bare schemes`() {
        assertEquals("file://box/home/me/notes.md", UrlScanner.find("file://box/home/me/notes.md").single().url)
        assertEquals(emptyList(), UrlScanner.find("http:// nothing here"))
        assertEquals(emptyList(), UrlScanner.find("no links, only words"))
    }

    private fun term(cols: Int, rows: Int = 4) = TerminalEmulator(cols, rows)
    private fun lineAt(t: TerminalEmulator): (Int) -> Line? = { r -> if (r in 0 until t.rows) t.screen.relativeLine(r) else null }

    @Test
    fun `a link the grid broke over two rows is still one link`() {
        val t = term(20)
        t.write("go: https://example.com/a/long/path now")
        // row 0: "go: https://example." row 1: "com/a/long/path now"
        val fromSecondRow = UrlScanner.linkAt(lineAt(t), 1, 3)
        assertEquals("https://example.com/a/long/path", fromSecondRow?.url)
        assertEquals(0, fromSecondRow?.fromRow)
        assertEquals(4, fromSecondRow?.fromCol)
        assertEquals(1, fromSecondRow?.toRow)
        val fromFirstRow = UrlScanner.linkAt(lineAt(t), 0, 6)
        assertEquals(fromSecondRow?.url, fromFirstRow?.url)
        assertNull(UrlScanner.linkAt(lineAt(t), 0, 1)) // on "go:"
    }

    @Test
    fun `wide characters count once`() {
        val t = term(40)
        t.write("界界 http://h.dev/x 界")
        // 界 is two cells: the URL starts at column 5, not 3.
        val link = UrlScanner.linkAt(lineAt(t), 0, 6)
        assertEquals("http://h.dev/x", link?.url)
        assertEquals(5, link?.fromCol)
        assertEquals(18, link?.toCol)
        assertNull(UrlScanner.linkAt(lineAt(t), 0, 1))
    }

    @Test
    fun `a hard line break ends the link`() {
        val t = term(30)
        t.write("http://a.dev/one\r\ntwo")
        assertEquals("http://a.dev/one", UrlScanner.linkAt(lineAt(t), 0, 3)?.url)
        assertNull(UrlScanner.linkAt(lineAt(t), 1, 1))
    }

    @Test
    fun `long-press keeps a query string whole`() {
        val t = term(40)
        t.write("open https://q.dev/?a=1&b=2#top now")
        val w = TextSelection.wordAt(t.screen.line(0), 10)
        assertEquals(5..30, w)
    }
}
