package com.cocakova.charon.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val E = "\u001B"

/** The deep colors: SGR 4:n underline styles and SGR 58/59 underline color. */
class CellExtTest {

    private fun term() = TerminalEmulator(20, 4, 50)
    private fun style(t: TerminalEmulator, col: Int, row: Int = 0): Int {
        val line = t.screen.line(row)
        return CellExt.underlineStyle(line.attrs[col], line.extAt(col))
    }
    private fun ext(t: TerminalEmulator, col: Int, row: Int = 0) = t.screen.line(row).extAt(col)

    // ---------------------------------------------------------------- packing

    @Test
    fun zeroExtMeansPlainSingleUnderlineWhenFlagged() {
        assertEquals(CellExt.UL_NONE, CellExt.underlineStyle(CellAttrs.DEFAULT, 0L))
        assertEquals(CellExt.UL_SINGLE, CellExt.underlineStyle(CellAttrs.UNDERLINE, 0L))
        // single folds to the free zero so plain `SGR 4` never allocates
        assertEquals(0L, CellExt.withUnderlineStyle(0L, CellExt.UL_SINGLE))
    }

    @Test
    fun stylesRoundTripAndNeedTheFlag() {
        for (s in CellExt.UL_DOUBLE..CellExt.UL_DASHED) {
            val e = CellExt.withUnderlineStyle(0L, s)
            assertEquals(s, CellExt.underlineStyle(CellAttrs.UNDERLINE, e))
            assertEquals(CellExt.UL_NONE, CellExt.underlineStyle(CellAttrs.DEFAULT, e))
        }
    }

    @Test
    fun underlineColorAndLinkLiveSideBySide() {
        var e = CellExt.withUnderlineStyle(0L, CellExt.UL_CURLY)
        e = CellExt.withUlRgb(e, 0xFF0080)
        e = CellExt.withLink(e, CellExt.MAX_LINK_ID)
        assertEquals(CellAttrs.MODE_RGB, CellExt.ulMode(e))
        assertEquals(0xFF0080, CellExt.ulColor(e))
        assertEquals(CellExt.MAX_LINK_ID, CellExt.linkId(e))
        assertEquals(CellExt.UL_CURLY, CellExt.underlineStyle(CellAttrs.UNDERLINE, e))
        e = CellExt.withUlPalette(e, 300) // masks to 8 bits
        assertEquals(CellAttrs.MODE_PALETTE, CellExt.ulMode(e))
        assertEquals(300 and 0xFF, CellExt.ulColor(e))
        e = CellExt.withDefaultUl(e)
        assertEquals(CellAttrs.MODE_DEFAULT, CellExt.ulMode(e))
        assertEquals(CellExt.MAX_LINK_ID, CellExt.linkId(e))
        assertEquals(0L, (e and CellExt.UNDERLINE_BITS) and 0x7L.inv())
    }

    // ---------------------------------------------------------------- SGR 4:n

    @Test
    fun colonStylesLand() {
        val t = term()
        t.write("$E[4:2mA$E[4:3mB$E[4:4mC$E[4:5mD$E[4:1mE$E[4:0mF")
        assertEquals(CellExt.UL_DOUBLE, style(t, 0))
        assertEquals(CellExt.UL_CURLY, style(t, 1))
        assertEquals(CellExt.UL_DOTTED, style(t, 2))
        assertEquals(CellExt.UL_DASHED, style(t, 3))
        assertEquals(CellExt.UL_SINGLE, style(t, 4))
        assertEquals(CellExt.UL_NONE, style(t, 5))
    }

    @Test
    fun legacyUnderlineKeepsWorkingAndCostsNoSideArray() {
        val t = term()
        t.write("$E[4mA$E[24mB")
        assertEquals(CellExt.UL_SINGLE, style(t, 0))
        assertEquals(CellExt.UL_NONE, style(t, 1))
        assertNull(t.screen.line(0).ext, "plain underline must not allocate extended attrs")
    }

    @Test
    fun semicolonThreeIsItalicNotCurly() {
        val t = term()
        t.write("$E[4;3mA")
        assertEquals(CellExt.UL_SINGLE, style(t, 0))
        assertTrue(CellAttrs.hasStyle(t.screen.line(0).attrs[0], CellAttrs.ITALIC))
    }

    @Test
    fun sgr21IsDoubleAnd24ClearsAnyStyle() {
        val t = term()
        t.write("$E[21mA$E[4:3m$E[24mB$E[4mC")
        assertEquals(CellExt.UL_DOUBLE, style(t, 0))
        assertEquals(CellExt.UL_NONE, style(t, 1))
        // 24 dropped the curl too: a later plain 4 is single again
        assertEquals(CellExt.UL_SINGLE, style(t, 2))
    }

    @Test
    fun unknownStyleFoldsToSingle() {
        val t = term()
        t.write("$E[4:9mA")
        assertEquals(CellExt.UL_SINGLE, style(t, 0))
    }

    // ---------------------------------------------------------------- SGR 58/59

    @Test
    fun underlineColorColonRgbWithEmptyColorspace() {
        val t = term()
        t.write("$E[4:3m$E[58:2::255:0:0mA")
        val e = ext(t, 0)
        assertEquals(CellAttrs.MODE_RGB, CellExt.ulMode(e))
        assertEquals(0xFF0000, CellExt.ulColor(e))
        assertEquals(CellExt.UL_CURLY, style(t, 0))
    }

    @Test
    fun underlineColorAllForms() {
        val t = term()
        t.write("$E[58:2:1:2:3mA$E[58:5:196mB$E[58;5;42mC$E[58;2;10;20;30mD$E[59mE")
        assertEquals(0x010203, CellExt.ulColor(ext(t, 0)))
        assertEquals(CellAttrs.MODE_PALETTE, CellExt.ulMode(ext(t, 1)))
        assertEquals(196, CellExt.ulColor(ext(t, 1)))
        assertEquals(42, CellExt.ulColor(ext(t, 2)))
        assertEquals(CellAttrs.MODE_RGB, CellExt.ulMode(ext(t, 3)))
        assertEquals(0x0A141E, CellExt.ulColor(ext(t, 3)))
        assertEquals(CellAttrs.MODE_DEFAULT, CellExt.ulMode(ext(t, 4)))
    }

    @Test
    fun semicolonUnderlineColorConsumesItsArgsOnly() {
        val t = term()
        // 58;5;1 must not be read as bold (1) — and the trailing 1 IS bold
        t.write("$E[58;5;1;1mA")
        val line = t.screen.line(0)
        assertEquals(1, CellExt.ulColor(line.extAt(0)))
        assertTrue(CellAttrs.hasStyle(line.attrs[0], CellAttrs.BOLD))
        assertEquals(CellAttrs.MODE_DEFAULT, CellAttrs.fgMode(line.attrs[0]))
    }

    @Test
    fun sgrResetClearsStyleAndColor() {
        val t = term()
        t.write("$E[4:3;58:5:9mA$E[0mB$E[4:3;58:5:9mC${E}[mD")
        assertEquals(0L, ext(t, 1))
        assertEquals(CellExt.UL_NONE, style(t, 1))
        assertEquals(0L, ext(t, 3))
    }

    @Test
    fun decscRestoresTheUnderlinePen() {
        val t = term()
        t.write("$E[4:3;58:5:9m${E}7$E[0mA")
        assertEquals(0L, ext(t, 0))
        // DECRC puts the cursor back on A and the curl back in the pen: B wears it
        t.write("${E}8B")
        assertEquals(CellExt.UL_CURLY, style(t, 0))
        assertEquals(9, CellExt.ulColor(ext(t, 0)))
    }

    // ---------------------------------------------------------------- line storage

    @Test
    fun eraseAndEditsCarryTheSideArray() {
        val t = term()
        t.write("$E[4:3mABC$E[0mD")
        val line = t.screen.line(0)
        assertTrue(line.ext != null)
        // ICH at col 0 shifts the curl right; the inserted blank is plain
        t.write("$E[1G$E[@")
        assertEquals(CellExt.UL_NONE, style(t, 0))
        assertEquals(CellExt.UL_CURLY, style(t, 1))
        assertEquals(CellExt.UL_CURLY, style(t, 3))
        // DCH pulls it back
        t.write("$E[P")
        assertEquals(CellExt.UL_CURLY, style(t, 0))
        assertEquals(CellExt.UL_NONE, style(t, 3))
        // EL erases the extended half too
        t.write("$E[2K")
        assertEquals(0L, ext(t, 0))
        assertNull(t.screen.line(0).ext, "a cleared line drops its side array")
    }

    @Test
    fun wideGlyphCarriesTheCurlAcrossBothCells() {
        val t = term()
        t.write("$E[4:3m中")
        assertEquals(CellExt.UL_CURLY, style(t, 0))
        assertEquals(CellExt.UL_CURLY, style(t, 1))
    }

    @Test
    fun resizeKeepsAndPadsExtended() {
        val t = term()
        t.write("$E[4:4mAB")
        t.resize(30, 4)
        assertEquals(CellExt.UL_DOTTED, style(t, 1))
        assertEquals(0L, ext(t, 29))
        t.resize(1, 4)
        assertEquals(1, t.screen.line(0).ext!!.size)
    }
}
