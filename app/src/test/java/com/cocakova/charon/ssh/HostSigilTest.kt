package com.cocakova.charon.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostSigilTest {
    @Test
    fun theSameKeyDrawsTheSameSigilAndARekeyedOneDoesNot() {
        val a = HostSigil.braille("SHA256:abc123")
        assertEquals(a, HostSigil.braille("SHA256:abc123"))
        assertNotEquals(a, HostSigil.braille("SHA256:abc124"))
    }

    @Test
    fun itIsTwoRowsOfEightBrailleCells() {
        val s = HostSigil.braille("SHA256:xyz")
        assertEquals(2, s.size)
        assertTrue(s.all { row -> row.length == 8 && row.all { it.code in 0x2800..0x28FF } })
        assertTrue(s.joinToString("").any { it.code != 0x2800 })
    }
}
