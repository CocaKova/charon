package com.cocakova.charon.fleet

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FleetWatchTest {

    private fun target(id: String, host: String = id) = SoundingTarget(id, host, 22)

    @Test
    fun `soundings split reachable from dark water`() = runTest {
        val watch = FleetWatch(dial = { host, _, _ -> if (host == "up") 12L else null })
        watch.soundAll(listOf(target("up"), target("down")))
        val chart = watch.soundings.value
        assertEquals(Reach.REACHABLE, chart.getValue("up").reach)
        assertEquals(12L, chart.getValue("up").latencyMs)
        assertEquals(Reach.UNREACHABLE, chart.getValue("down").reach)
        assertNull(chart.getValue("down").latencyMs)
    }

    @Test
    fun `a dial that throws reads as dark water, not a crash`() = runTest {
        val watch = FleetWatch(dial = { _, _, _ -> error("resolver exploded") })
        watch.soundAll(listOf(target("a")))
        assertEquals(Reach.UNREACHABLE, watch.soundings.value.getValue("a").reach)
    }

    @Test
    fun `a released mooring drops off the chart on the next pass`() = runTest {
        val watch = FleetWatch(dial = { _, _, _ -> 5L })
        watch.soundAll(listOf(target("keep"), target("release")))
        assertEquals(2, watch.soundings.value.size)
        watch.soundAll(listOf(target("keep")))
        assertEquals(setOf("keep"), watch.soundings.value.keys)
    }

    @Test
    fun `resounding replaces the old sounding`() = runTest {
        var up = true
        val watch = FleetWatch(dial = { _, _, _ -> if (up) 8L else null })
        watch.soundAll(listOf(target("a")))
        assertEquals(Reach.REACHABLE, watch.soundings.value.getValue("a").reach)
        up = false
        watch.soundAll(listOf(target("a")))
        assertEquals(Reach.UNREACHABLE, watch.soundings.value.getValue("a").reach)
    }
}
