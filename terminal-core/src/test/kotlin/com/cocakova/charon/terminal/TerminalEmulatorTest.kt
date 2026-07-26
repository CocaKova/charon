package com.cocakova.charon.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val E = "\u001B"

class TerminalEmulatorTest {

    private class Rig(cols: Int = 10, rows: Int = 5, scrollback: Int = 100) {
        val responses = mutableListOf<String>()
        val titles = mutableListOf<String>()
        var bells = 0
        val term = TerminalEmulator(
            cols, rows, scrollback,
            onResponse = { responses.add(it) },
            onBell = { bells++ },
            onTitle = { titles.add(it) },
        )

        fun feed(s: String) = term.write(s)
        fun text(): List<String> = term.screen.toText()
        fun row(r: Int): String = term.screen.line(r).toText()
    }

    // ---------------------------------------------------------------- basics

    @Test
    fun printAdvancesCursor() {
        val r = Rig()
        r.feed("abc")
        assertEquals("abc", r.row(0))
        assertEquals(3, r.term.cursorX)
        assertEquals(0, r.term.cursorY)
    }

    @Test
    fun crlfMovesToNextLineStart() {
        val r = Rig()
        r.feed("ab\r\ncd")
        assertEquals("ab", r.row(0))
        assertEquals("cd", r.row(1))
    }

    @Test
    fun backspaceStopsAtColumnZero() {
        val r = Rig()
        r.feed("a")
        assertEquals(0, r.term.cursorX)
    }

    @Test
    fun bellRings() {
        val r = Rig()
        r.feed("\u0007")
        assertEquals(1, r.bells)
    }

    // ---------------------------------------------------------------- deferred wrap

    @Test
    fun deferredWrap_fullLineThenCrlfLeavesNoBlankLine() {
        // THE classic emulator bug: exactly cols chars then CRLF must land on row 1,
        // not row 2.
        val r = Rig(cols = 10)
        r.feed("0123456789\r\nnext")
        assertEquals("0123456789", r.row(0))
        assertEquals("next", r.row(1))
        assertEquals("", r.row(2))
    }

    @Test
    fun deferredWrap_overflowWrapsAndMarksContinuation() {
        val r = Rig(cols = 10)
        r.feed("0123456789AB")
        assertEquals("0123456789", r.row(0))
        assertEquals("AB", r.row(1))
        assertTrue(r.term.screen.line(1).isWrapped)
        assertFalse(r.term.screen.line(0).isWrapped)
    }

    @Test
    fun deferredWrap_cursorReportsLastColumnWhilePending() {
        val r = Rig(cols = 10)
        r.feed("0123456789")
        assertEquals(9, r.term.cursorX)
        assertEquals(0, r.term.cursorY)
    }

    @Test
    fun autowrapOff_pinsAtLastColumn() {
        val r = Rig(cols = 10)
        r.feed("$E[?7l0123456789XYZ")
        assertEquals("012345678Z", r.row(0))
        assertEquals("", r.row(1))
    }

    // ---------------------------------------------------------------- tabs

    @Test
    fun tabsUseDefaultStops() {
        val r = Rig(cols = 20)
        r.feed("\tx")
        assertEquals(9, r.term.cursorX) // stop at 8, then the printed x
    }

    @Test
    fun customTabStopsViaHtsAndTbc() {
        val r = Rig(cols = 20)
        r.feed("$E[3g")            // clear all stops
        r.feed("$E[5G${E}H")       // set a stop at column 5 (index 4)
        r.feed("\r\t")
        assertEquals(4, r.term.cursorX)
        r.feed("\t")
        assertEquals(19, r.term.cursorX) // no more stops: right margin
    }

    // ---------------------------------------------------------------- cursor movement

    @Test
    fun cupIsOneBasedAndClamped() {
        val r = Rig(cols = 10, rows = 5)
        r.feed("$E[3;4H")
        assertEquals(3, r.term.cursorX)
        assertEquals(2, r.term.cursorY)
        r.feed("$E[99;99H")
        assertEquals(9, r.term.cursorX)
        assertEquals(4, r.term.cursorY)
    }

    @Test
    fun cursorUpStopsAtScrollRegionTop() {
        val r = Rig(rows = 5)
        r.feed("$E[2;4r")     // region rows 2-4 (indices 1-3)
        r.feed("$E[3;1H")     // inside region
        r.feed("$E[9A")       // way up
        assertEquals(1, r.term.cursorY) // stopped at region top
    }

    // ---------------------------------------------------------------- erase

    @Test
    fun eraseDisplayModes() {
        val r = Rig(cols = 5, rows = 3)
        r.feed("aaaaa\r\nbbbbb\r\nccccc")
        r.feed("$E[2;3H$E[0J") // erase from cursor to end
        assertEquals("aaaaa", r.row(0))
        assertEquals("bb", r.row(1))
        assertEquals("", r.row(2))
        r.feed("$E[2J")
        assertEquals(listOf("", "", ""), r.text())
    }

    @Test
    fun eraseLineModes() {
        val r = Rig(cols = 5)
        r.feed("abcde")
        r.feed("$E[3G$E[1K") // erase start..cursor (inclusive)
        assertEquals("   de", r.row(0))
        r.feed("$E[2K")
        assertEquals("", r.row(0))
    }

    @Test
    fun eraseUsesCurrentBackground() {
        val r = Rig(cols = 5)
        r.feed("$E[44m$E[2J") // blue bg, clear all
        val a = r.term.screen.line(0).attrs[0]
        assertEquals(CellAttrs.MODE_PALETTE, CellAttrs.bgMode(a))
        assertEquals(4, CellAttrs.bgColor(a))
        assertEquals(CellAttrs.MODE_DEFAULT, CellAttrs.fgMode(a)) // fg not smeared
    }

    // ---------------------------------------------------------------- SGR

    @Test
    fun sgrPaletteAndReset() {
        val r = Rig()
        r.feed("$E[31mx$E[0my")
        val line = r.term.screen.line(0)
        assertEquals(1, CellAttrs.fgColor(line.attrs[0]))
        assertEquals(CellAttrs.MODE_PALETTE, CellAttrs.fgMode(line.attrs[0]))
        assertEquals(CellAttrs.MODE_DEFAULT, CellAttrs.fgMode(line.attrs[1]))
    }

    @Test
    fun sgrTruecolorSemicolonAndColonForms() {
        val r = Rig()
        r.feed("$E[38;2;10;20;30ma$E[38:2:40:50:60mb")
        val line = r.term.screen.line(0)
        assertEquals(0x0A141E, CellAttrs.fgColor(line.attrs[0]))
        assertEquals(0x28323C, CellAttrs.fgColor(line.attrs[1]))
        assertEquals(CellAttrs.MODE_RGB, CellAttrs.fgMode(line.attrs[1]))
    }

    @Test
    fun sgr256AndBrightColors() {
        val r = Rig()
        r.feed("$E[48;5;196ma$E[91mb")
        val line = r.term.screen.line(0)
        assertEquals(196, CellAttrs.bgColor(line.attrs[0]))
        assertEquals(9, CellAttrs.fgColor(line.attrs[1])) // bright red = palette 9
    }

    @Test
    fun sgrStyleFlags() {
        val r = Rig()
        r.feed("$E[1;4;7mx$E[22;24;27my")
        val line = r.term.screen.line(0)
        assertTrue(CellAttrs.hasStyle(line.attrs[0], CellAttrs.BOLD))
        assertTrue(CellAttrs.hasStyle(line.attrs[0], CellAttrs.UNDERLINE))
        assertTrue(CellAttrs.hasStyle(line.attrs[0], CellAttrs.INVERSE))
        assertFalse(CellAttrs.hasStyle(line.attrs[1], CellAttrs.BOLD))
        assertFalse(CellAttrs.hasStyle(line.attrs[1], CellAttrs.UNDERLINE))
        assertFalse(CellAttrs.hasStyle(line.attrs[1], CellAttrs.INVERSE))
    }

    // ---------------------------------------------------------------- scrolling

    @Test
    fun scrollAtBottomPushesToScrollback() {
        val r = Rig(cols = 5, rows = 3)
        r.feed("one\r\ntwo\r\nthree\r\nfour")
        assertEquals("two", r.row(0))
        assertEquals("four", r.row(2))
        assertEquals(1, r.term.primary.scrollbackSize)
        assertEquals("one", r.term.primary.scrollbackLine(0).toText())
    }

    @Test
    fun altScreenKeepsNoHistoryAndRestoresPrimary() {
        val r = Rig(cols = 8, rows = 3)
        r.feed("shell$E[?1049h")
        assertTrue(r.term.usingAlt)
        assertEquals(listOf("", "", ""), r.text()) // alt starts clear
        r.feed("vim!")
        r.feed("\r\n\r\n\r\n\r\n") // scrolling alt produces no history
        assertEquals(0, r.term.alt.scrollbackSize)
        r.feed("$E[?1049l")
        assertFalse(r.term.usingAlt)
        assertEquals("shell", r.row(0)) // primary content intact
        assertEquals(5, r.term.cursorX) // cursor restored
    }

    @Test
    fun scrollRegionScrollsOnlyItself() {
        val r = Rig(cols = 5, rows = 5)
        r.feed("top\r\naaa\r\nbbb\r\nccc\r\nbot")
        r.feed("$E[2;4r")   // region rows 2-4
        r.feed("$E[4;1H\n") // LF at region bottom
        assertEquals("top", r.row(0))
        assertEquals("bbb", r.row(1))
        assertEquals("ccc", r.row(2))
        assertEquals("", r.row(3))
        assertEquals("bot", r.row(4))
    }

    @Test
    fun reverseIndexAtTopScrollsDown() {
        val r = Rig(cols = 5, rows = 3)
        r.feed("one\r\ntwo\r\nthr")
        r.feed("$E[1;1H${E}M")
        assertEquals("", r.row(0))
        assertEquals("one", r.row(1))
        assertEquals("two", r.row(2))
    }

    @Test
    fun insertAndDeleteLines() {
        val r = Rig(cols = 5, rows = 4)
        r.feed("aaa\r\nbbb\r\nccc\r\nddd")
        r.feed("$E[2;1H$E[1L") // insert line at row 2
        assertEquals(listOf("aaa", "", "bbb", "ccc"), r.text())
        r.feed("$E[2;1H$E[2M") // delete two lines at row 2
        assertEquals(listOf("aaa", "ccc", "", ""), r.text())
    }

    // ---------------------------------------------------------------- origin mode

    @Test
    fun originModeMakesCupRegionRelative() {
        val r = Rig(cols = 10, rows = 6)
        r.feed("$E[3;5r$E[?6h") // region rows 3-5, origin mode
        r.feed("$E[1;1Hx")
        assertEquals("x", r.row(2)) // row 1 == region top == absolute row 3
        r.feed("$E[6n")
        assertEquals("$E[1;2R", r.responses.last()) // CPR is region-relative too
    }

    // ---------------------------------------------------------------- reports

    @Test
    fun deviceStatusAndAttributes() {
        val r = Rig()
        r.feed("$E[5n")
        assertEquals("${E}[0n", r.responses.last())
        r.feed("$E[c")
        assertEquals("$E[?62;1;6;9;15;22c", r.responses.last())
        r.feed("$E[>c")
        assertEquals("$E[>41;377;0c", r.responses.last())
        r.feed("$E[x")
        assertEquals("$E[2;1;1;128;128;1;0x", r.responses.last())
        r.feed("$E[1x")
        assertEquals("$E[3;1;1;128;128;1;0x", r.responses.last())
    }

    @Test
    fun reverseWraparoundBackspace() {
        val r = Rig(cols = 10, rows = 5)
        r.feed("$E[?45h$E[?7h")
        // BS at left edge climbs to previous row's last column.
        r.feed("$E[2;1H\u0008")
        assertEquals(9, r.term.cursorX)
        assertEquals(0, r.term.cursorY)
        // Within a scroll region, the top row wraps to the region's bottom.
        r.feed("$E[2;4r$E[2;1H\u0008")
        assertEquals(9, r.term.cursorX)
        assertEquals(3, r.term.cursorY)
        r.feed("$E[r")
        // A pending-wrap cell only annuls the wrap: cursor column is unchanged.
        r.feed("$E[1;9Hab\u0008")
        assertEquals(9, r.term.cursorX)
        assertEquals(0, r.term.cursorY)
        // CUB walks backwards across the soft wrap.
        r.feed("$E[1;9Habcd$E[4D")
        assertEquals(8, r.term.cursorX)
        assertEquals(0, r.term.cursorY)
        // Without mode 45, BS stops at the left edge.
        r.feed("$E[?45l$E[2;1H\u0008")
        assertEquals(0, r.term.cursorX)
        assertEquals(1, r.term.cursorY)
    }

    @Test
    fun decidAndStatusReports() {
        val r = Rig()
        r.feed("${E}Z")
        assertEquals("$E[?62;1;6;9;15;22c", r.responses.last())
        r.feed("$E[?15n")
        assertEquals("$E[?13n", r.responses.last())
        r.feed("$E[?25n")
        assertEquals("$E[?20n", r.responses.last())
        r.feed("$E[?26n")
        assertEquals("$E[?27;1;0;0n", r.responses.last())
    }

    @Test
    fun decstrResetsSavedCursorState() {
        val r = Rig()
        r.feed("$E[6;6H${E}7") // move + DECSC
        r.feed("$E[!p")        // DECSTR must forget the save (DEC STD-070)
        r.feed("$E[10;10H${E}8") // DECRC after reset restores home, not 6;6
        assertEquals(0, r.term.cursorX)
        assertEquals(0, r.term.cursorY)
    }

    @Test
    fun windowSizeReport() {
        val r = Rig(cols = 10, rows = 5)
        r.feed("$E[18t")
        assertEquals("$E[8;5;10t", r.responses.last())
    }

    @Test
    fun oscColorQueryReports16BitRgb() {
        val r = Rig()
        r.term.defaultBg = 0x000000
        r.feed("$E]11;?\u0007")
        assertEquals("$E]11;rgb:0000/0000/0000$E\\", r.responses.last())
    }

    @Test
    fun titleChangesViaOsc() {
        val r = Rig()
        r.feed("$E]0;hello styx\u0007")
        assertEquals("hello styx", r.term.title)
        assertEquals(listOf("hello styx"), r.titles)
    }

    // ---------------------------------------------------------------- charsets

    @Test
    fun decSpecialGraphicsDrawsBoxes() {
        val r = Rig()
        r.feed("$E(0lqk$E(B")
        assertEquals("┌─┐", r.row(0))
    }

    @Test
    fun shiftOutUsesG1() {
        val r = Rig()
        r.feed("$E)0")       // G1 := DEC special
        r.feed("q\u000Eq\u000Fq") // q, SO q (─), SI q
        assertEquals("q─q", r.row(0))
    }

    // ---------------------------------------------------------------- wide & combining

    @Test
    fun wideCharOccupiesTwoCells() {
        val r = Rig()
        r.feed("漢x")
        val line = r.term.screen.line(0)
        assertEquals(0x6F22, line.codePoints[0])
        assertTrue(CellAttrs.hasStyle(line.attrs[0], CellAttrs.WIDE))
        assertTrue(CellAttrs.hasStyle(line.attrs[1], CellAttrs.WIDE_CONTINUATION))
        assertEquals('x'.code, line.codePoints[2])
        assertEquals("漢x", r.row(0))
    }

    @Test
    fun overwritingHalfAWideCharBlanksThePair() {
        val r = Rig()
        r.feed("漢$E[1Gz") // overwrite left half
        val line = r.term.screen.line(0)
        assertEquals('z'.code, line.codePoints[0])
        assertFalse(CellAttrs.hasStyle(line.attrs[1], CellAttrs.WIDE_CONTINUATION))
        assertEquals(Line.SPACE, line.codePoints[1])
    }

    @Test
    fun wideCharAtLastColumnWrapsWhole() {
        val r = Rig(cols = 5)
        r.feed("abcd漢")
        assertEquals("abcd", r.row(0))
        assertEquals("漢", r.row(1))
        assertTrue(r.term.screen.line(1).isWrapped)
    }

    @Test
    fun combiningMarkAttachesToPreviousCell() {
        val r = Rig()
        r.feed("e\u0301x") // e + COMBINING ACUTE
        assertEquals("éx", java.text.Normalizer.normalize(r.row(0), java.text.Normalizer.Form.NFC))
        assertEquals("e\u0301", r.term.screen.line(0).textAt(0))
    }

    // ---------------------------------------------------------------- edit ops

    @Test
    fun insertAndDeleteChars() {
        val r = Rig(cols = 8)
        r.feed("abcdef$E[3G$E[2@") // ICH 2 at column 3
        assertEquals("ab  cdef", r.row(0))
        r.feed("$E[3G$E[2P") // DCH 2
        assertEquals("abcdef", r.row(0))
    }

    @Test
    fun eraseChars() {
        val r = Rig(cols = 8)
        r.feed("abcdef$E[2G$E[3X")
        assertEquals("a   ef", r.row(0))
    }

    @Test
    fun repeatLastCharacter() {
        val r = Rig(cols = 10)
        r.feed("ab$E[3b")
        assertEquals("abbbb", r.row(0))
    }

    // ---------------------------------------------------------------- reset & misc

    @Test
    fun decAlignmentFillsWithE() {
        val r = Rig(cols = 4, rows = 2)
        r.feed("$E#8")
        assertEquals(listOf("EEEE", "EEEE"), r.text())
        assertEquals(0, r.term.cursorX)
    }

    @Test
    fun fullResetClearsEverything() {
        val r = Rig(cols = 6, rows = 3)
        r.feed("$E[31;44mhi$E[2;3r$E[?6h$E[?25l$E]0;t\u0007")
        r.feed("${E}c")
        assertEquals(listOf("", "", ""), r.text())
        assertTrue(r.term.cursorVisible)
        assertFalse(r.term.originMode)
        assertEquals("", r.term.title)
        r.feed("x")
        assertEquals(CellAttrs.DEFAULT, r.term.screen.line(0).attrs[0])
    }

    @Test
    fun modesToggle() {
        val r = Rig()
        r.feed("$E[?1h$E[?2004h$E[?1006h$E[?1000h$E[?25l")
        assertTrue(r.term.cursorKeysApp)
        assertTrue(r.term.bracketedPaste)
        assertTrue(r.term.mouseSgr)
        assertEquals(1000, r.term.mouseMode)
        assertFalse(r.term.cursorVisible)
        r.feed("$E[?1l$E[?2004l$E[?1000l$E[?25h")
        assertFalse(r.term.cursorKeysApp)
        assertFalse(r.term.bracketedPaste)
        assertEquals(0, r.term.mouseMode)
        assertTrue(r.term.cursorVisible)
    }

    @Test
    fun resizeTruncatesAndClampsCursor() {
        val r = Rig(cols = 10, rows = 5)
        r.feed("0123456789$E[5;10H")
        r.term.resize(6, 3)
        assertEquals("012345", r.row(0))
        assertEquals(5, r.term.cursorX)
        assertEquals(2, r.term.cursorY)
    }

    @Test
    fun insertModeShiftsExistingText() {
        val r = Rig(cols = 8)
        r.feed("abc$E[1G$E[4hXY$E[4l")
        assertEquals("XYabc", r.row(0))
    }
}
