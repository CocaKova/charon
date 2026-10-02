package com.cocakova.charon.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContrastTest {
    private val paper = 0xEFEBE2
    private val night = 0x0A0F14

    @Test
    fun `white on paper is walked toward ink until it reads`() {
        assertTrue(Contrast.ratio(0xE5E5E5, paper) < 1.3)
        val lifted = Contrast.floor(0xE5E5E5, paper, 3.0)
        assertTrue(Contrast.ratio(lifted, paper) >= 3.0)
        assertTrue(Contrast.ratio(lifted, paper) < 3.6) // just enough, not black
    }

    @Test
    fun `a colour that already reads is left exactly as it was`() {
        assertEquals(0x1F2B33, Contrast.floor(0x1F2B33, paper, 3.0))
        assertEquals(0x3ECFB2, Contrast.floor(0x3ECFB2, night, 3.0))
    }

    @Test
    fun `dark on night is lifted toward light, and hidden text stays hidden`() {
        val lifted = Contrast.floor(0x101418, night, 3.0)
        assertTrue(Contrast.ratio(lifted, night) >= 3.0)
        assertEquals(paper, Contrast.floor(paper, paper, 3.0))
    }
}
