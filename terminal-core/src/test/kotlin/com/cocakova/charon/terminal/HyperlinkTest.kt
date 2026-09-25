package com.cocakova.charon.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val E = "\u001B"
private const val ST = "\u001B\\"

/** Marked passages: OSC 8 hyperlinks, the table behind them, and their caps. */
class HyperlinkTest {

    private fun term(cols: Int = 20, rows: Int = 3, scrollback: Int = 50) =
        TerminalEmulator(cols, rows, scrollback)

    private fun link(t: TerminalEmulator, col: Int, row: Int = 0): Int =
        CellExt.linkId(t.screen.line(row).extAt(col))

    private fun uri(t: TerminalEmulator, col: Int, row: Int = 0): String? =
        t.hyperlinks.uri(link(t, col, row))

    private fun open(uri: String, params: String = "") = "$E]8;$params;$uri$ST"
    private val close = "$E]8;;$ST"

    // ---------------------------------------------------------------- the wire

    @Test
    fun openPrintClose() {
        val t = term()
        t.write("a${open("https://example.com")}link$close b")
        assertEquals("alink b", t.screen.line(0).toText())
        assertEquals(0, link(t, 0))
        for (c in 1..4) assertEquals("https://example.com", uri(t, c))
        assertEquals(0, link(t, 5))
        assertEquals(0, link(t, 6))
    }

    @Test
    fun belTerminatorAndBareCloseWork() {
        val t = term()
        t.write("$E]8;;https://a.test\u0007x$E]8;;\u0007y$E]8\u0007")
        assertEquals("https://a.test", uri(t, 0))
        assertEquals(0, link(t, 1))
    }

    @Test
    fun uriKeepsItsSemicolons() {
        val t = term()
        t.write("${open("https://x.test/a;b=c;d")}z$close")
        assertEquals("https://x.test/a;b=c;d", uri(t, 0))
    }

    @Test
    fun sgrResetDoesNotCloseTheLink() {
        val t = term()
        t.write("${open("https://x.test")}$E[1ma$E[0mb$close")
        assertEquals("https://x.test", uri(t, 1))
    }

    @Test
    fun linkRidesAlongsideAnUndercurl() {
        val t = term()
        t.write("${open("https://x.test")}$E[4:3;58:5:1ma$close")
        val line = t.screen.line(0)
        assertEquals(CellExt.UL_CURLY, CellExt.underlineStyle(line.attrs[0], line.extAt(0)))
        assertEquals(1, CellExt.ulColor(line.extAt(0)))
        assertEquals("https://x.test", uri(t, 0))
    }

    @Test
    fun linkIsNotAnUnderlineOnItsOwn() {
        val t = term()
        t.write("${open("https://x.test")}a$close")
        val line = t.screen.line(0)
        assertEquals(CellExt.UL_NONE, CellExt.underlineStyle(line.attrs[0], line.extAt(0)))
    }

    // ---------------------------------------------------------------- identity

    @Test
    fun sameIdAndUriIsOneLinkAcrossLines() {
        val t = term()
        t.write("${open("https://x.test", "id=w1")}ab$close\r\n")
        t.write("${open("https://x.test", "foo=bar:id=w1")}cd$close")
        assertEquals(link(t, 0, 0), link(t, 0, 1))
        assertNotEquals(0, link(t, 0, 0))
    }

    @Test
    fun sameIdDifferentUriAreDifferentLinks() {
        val t = term()
        t.write("${open("https://x.test/1", "id=w1")}a${open("https://x.test/2", "id=w1")}b$close")
        assertNotEquals(link(t, 0), link(t, 1))
    }

    @Test
    fun idAndAnonymousSameUriAreDifferentLinks() {
        val t = term()
        t.write("${open("https://x.test", "id=q")}a${open("https://x.test")}b${open("https://x.test")}c$close")
        assertNotEquals(link(t, 0), link(t, 1))
        // anonymous twice: one entry, not one per open
        assertEquals(link(t, 1), link(t, 2))
        assertEquals(2, t.hyperlinks.size)
    }

    // ---------------------------------------------------------------- lifetime

    @Test
    fun scrollbackKeepsItsLinks() {
        val t = term(rows = 2)
        t.write("${open("https://kept.test")}old$close\r\n")
        repeat(5) { t.write("line $it\r\n") }
        val sb = t.screen.scrollbackLine(0)
        assertEquals("old", sb.toText())
        assertEquals("https://kept.test", t.hyperlinks.uri(CellExt.linkId(sb.extAt(0))))
    }

    @Test
    fun eraseDropsTheLink() {
        val t = term()
        t.write("${open("https://x.test")}abc$close$E[1G$E[K")
        assertEquals(0, link(t, 0))
    }

    @Test
    fun softAndHardResetCloseAnOpenLink() {
        val t = term()
        t.write("${open("https://x.test")}a$E[!pb")
        assertEquals(0, link(t, 1))
        t.write("${open("https://y.test")}c${E}cd")
        assertEquals(0, link(t, 0))
        assertEquals(0, t.hyperlinks.size)
    }

    // ---------------------------------------------------------------- defences

    @Test
    fun overlongUriPrintsUnlinked() {
        val t = term()
        val long = "https://x.test/" + "a".repeat(HyperlinkTable.MAX_URI_LENGTH)
        t.write("${open(long)}z$close")
        assertEquals("z", t.screen.line(0).toText())
        assertEquals(0, link(t, 0))
    }

    @Test
    fun tableRefusesWhenFullAndControlChars() {
        val table = HyperlinkTable(capacity = 3)
        assertEquals(1, table.intern("a", null))
        assertEquals(2, table.intern("b", null))
        assertEquals(3, table.intern("c", null))
        assertTrue(table.isFull)
        assertEquals(0, table.intern("d", null))
        assertEquals(2, table.intern("b", null), "an existing link still resolves when full")
        assertEquals(0, table.intern("bad\u0085uri", null))
        assertEquals(0, table.intern("x", "i".repeat(HyperlinkTable.MAX_ID_LENGTH + 1)))
    }

    @Test
    fun charBudgetCapsTheTable() {
        val table = HyperlinkTable(capacity = 100, charBudget = 10)
        assertNotEquals(0, table.intern("12345", null))
        assertEquals(0, table.intern("123456", null))
        assertNotEquals(0, table.intern("abcde", null))
        assertTrue(table.isFull)
    }

    @Test
    fun sweepFreesIdsNoCellWears() {
        val table = HyperlinkTable(capacity = 3)
        table.intern("a", null); table.intern("b", "k"); table.intern("c", null)
        table.retainOnly(java.util.BitSet().apply { set(2) })
        assertEquals(1, table.size)
        assertNull(table.uri(1))
        assertEquals("b", table.uri(2))
        val fresh = table.intern("d", null)
        assertTrue(fresh == 1 || fresh == 3)
        assertEquals("d", table.uri(fresh))
    }

    @Test
    fun emulatorSweepsWhenTheTableFills() {
        // A redraw-heavy app: thousands of distinct links, each overwritten in place.
        // Only the one on screen is live, so the table must never wedge full.
        val t = term(scrollback = 0)
        repeat(HyperlinkTable.MAX_LINKS + 50) { i ->
            t.write("$E[H${open("https://x.test/$i")}q$close")
        }
        val last = "https://x.test/${HyperlinkTable.MAX_LINKS + 49}"
        assertEquals(last, uri(t, 0))
        assertTrue(t.hyperlinks.size < HyperlinkTable.MAX_LINKS)
    }

    @Test
    fun fuzzedOsc8NeverThrows() {
        val t = term()
        val rnd = java.util.Random(8)
        repeat(2000) {
            val sb = StringBuilder("$E]8;")
            repeat(rnd.nextInt(40)) { sb.append((rnd.nextInt(0x7F - 0x20) + 0x20).toChar()) }
            sb.append(if (rnd.nextBoolean()) ST else "\u0007")
            sb.append("x")
            t.write(sb.toString())
        }
    }
}
