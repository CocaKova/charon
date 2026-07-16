package com.cocakova.charon.fleet

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanSweepTest {

    @Test
    fun `candidates cover the slash-24 minus ourselves`() {
        val berths = LanSweep.candidates("192.168.50.77")
        assertEquals(253, berths.size)
        assertTrue("192.168.50.1" in berths)
        assertTrue("192.168.50.254" in berths)
        assertTrue("192.168.50.77" !in berths)
        assertTrue("192.168.50.0" !in berths)
        assertTrue("192.168.50.255" !in berths)
    }

    @Test
    fun `garbage ip yields no candidates`() {
        assertEquals(emptyList<String>(), LanSweep.candidates("nonsense"))
    }

    @Test
    fun `sweep finds the ships that answer, in berth order`() = runTest {
        val open = setOf("192.168.50.9", "192.168.50.201", "192.168.50.44")
        val finds = LanSweep.sweep(
            ownIp = "192.168.50.77",
            dial = { ip, port, _ ->
                assertEquals(22, port)
                if (ip in open) 3L else null
            },
            resolve = { null },
        )
        assertEquals(listOf("192.168.50.9", "192.168.50.44", "192.168.50.201"), finds.map { it.ip })
    }

    @Test
    fun `progress reaches the full harbor`() = runTest {
        var lastDialed = 0
        var lastFound = 0
        LanSweep.sweep(
            ownIp = "10.0.0.5",
            dial = { ip, _, _ -> if (ip.endsWith(".7")) 2L else null },
            resolve = { null },
            onProgress = { dialed, found -> lastDialed = dialed; lastFound = found },
        )
        assertEquals(253, lastDialed)
        assertEquals(1, lastFound)
    }
}
