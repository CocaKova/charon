package com.cocakova.charon.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CellAttrsTest {

    @Test
    fun defaultHasDefaultColorsAndNoStyles() {
        val a = CellAttrs.DEFAULT
        assertEquals(CellAttrs.MODE_DEFAULT, CellAttrs.fgMode(a))
        assertEquals(CellAttrs.MODE_DEFAULT, CellAttrs.bgMode(a))
        assertFalse(CellAttrs.hasStyle(a, CellAttrs.BOLD))
        assertFalse(CellAttrs.hasStyle(a, CellAttrs.WIDE_CONTINUATION))
    }

    @Test
    fun fgRgbRoundTrips() {
        val a = CellAttrs.withFgRgb(CellAttrs.DEFAULT, 0xDEADBE)
        assertEquals(CellAttrs.MODE_RGB, CellAttrs.fgMode(a))
        assertEquals(0xDEADBE, CellAttrs.fgColor(a))
        // bg untouched
        assertEquals(CellAttrs.MODE_DEFAULT, CellAttrs.bgMode(a))
    }

    @Test
    fun bgRgbRoundTrips() {
        val a = CellAttrs.withBgRgb(CellAttrs.DEFAULT, 0x123456)
        assertEquals(CellAttrs.MODE_RGB, CellAttrs.bgMode(a))
        assertEquals(0x123456, CellAttrs.bgColor(a))
        assertEquals(CellAttrs.MODE_DEFAULT, CellAttrs.fgMode(a))
    }

    @Test
    fun paletteRoundTripsAndMasksTo8Bits() {
        val a = CellAttrs.withFgPalette(CellAttrs.DEFAULT, 196)
        assertEquals(CellAttrs.MODE_PALETTE, CellAttrs.fgMode(a))
        assertEquals(196, CellAttrs.fgColor(a))

        val b = CellAttrs.withBgPalette(CellAttrs.DEFAULT, 0x1FF) // out of range → masked
        assertEquals(0xFF, CellAttrs.bgColor(b))
    }

    @Test
    fun recoloringClearsPreviousPayload() {
        var a = CellAttrs.withFgRgb(CellAttrs.DEFAULT, 0xFFFFFF)
        a = CellAttrs.withFgPalette(a, 7)
        assertEquals(CellAttrs.MODE_PALETTE, CellAttrs.fgMode(a))
        assertEquals(7, CellAttrs.fgColor(a))
        a = CellAttrs.withDefaultFg(a)
        assertEquals(CellAttrs.MODE_DEFAULT, CellAttrs.fgMode(a))
        assertEquals(0, CellAttrs.fgColor(a))
    }

    @Test
    fun stylesAreIndependentOfColorsAndEachOther()  {
        var a = CellAttrs.withFgRgb(CellAttrs.DEFAULT, 0xABCDEF)
        a = CellAttrs.withStyle(a, CellAttrs.BOLD)
        a = CellAttrs.withStyle(a, CellAttrs.UNDERLINE)
        a = CellAttrs.withBgPalette(a, 4)

        assertTrue(CellAttrs.hasStyle(a, CellAttrs.BOLD))
        assertTrue(CellAttrs.hasStyle(a, CellAttrs.UNDERLINE))
        assertFalse(CellAttrs.hasStyle(a, CellAttrs.ITALIC))
        assertEquals(0xABCDEF, CellAttrs.fgColor(a))
        assertEquals(4, CellAttrs.bgColor(a))

        a = CellAttrs.withoutStyle(a, CellAttrs.BOLD)
        assertFalse(CellAttrs.hasStyle(a, CellAttrs.BOLD))
        assertTrue(CellAttrs.hasStyle(a, CellAttrs.UNDERLINE))
    }

    @Test
    fun allStyleFlagsAreDistinct() {
        val flags = listOf(
            CellAttrs.BOLD, CellAttrs.FAINT, CellAttrs.ITALIC, CellAttrs.UNDERLINE,
            CellAttrs.BLINK, CellAttrs.INVERSE, CellAttrs.INVISIBLE, CellAttrs.STRIKETHROUGH,
            CellAttrs.WIDE, CellAttrs.WIDE_CONTINUATION, CellAttrs.PROTECTED,
        )
        assertEquals(flags.size, flags.distinct().size)
        // and none of them collide with color/mode bits
        for (f in flags) {
            assertEquals(CellAttrs.MODE_DEFAULT, CellAttrs.fgMode(f))
            assertEquals(CellAttrs.MODE_DEFAULT, CellAttrs.bgMode(f))
            assertEquals(0, CellAttrs.fgColor(f))
            assertEquals(0, CellAttrs.bgColor(f))
        }
    }
}
