package com.cocakova.charon.cargo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lading machine on its own clock: every end leads to a dock or to nothing. */
class CargoWatchTest {

    private val s = 1_000_000_000L

    private fun glean(vararg rows: String) = CargoLading.glean(rows.toList())

    @Test
    fun onlyNewRowsAreSightings() {
        val w = CargoWatch()
        w.arm("apt", 0)
        w.glean(glean("Unpacking a (1) ..."), 0)
        assertEquals(CargoWatch.Phase.SAILING, w.view(1 * s)?.phase)
        // The same rows, read again and again, are history.
        for (i in 2..10) w.glean(glean("Unpacking a (1) ..."), i * s)
        assertNull(w.view(10 * s))
        w.glean(glean("Unpacking a (1) ...", "Unpacking b (2) ..."), 11 * s)
        assertEquals("b", w.view(11 * s)?.item)
    }

    @Test
    fun progressSteersAndDocks() {
        val w = CargoWatch()
        w.progress(1, 40, 0)
        assertEquals(CargoWatch.PROGRESS_MANAGER, w.manager)
        assertEquals(40, w.view(0)?.percent)
        // A program holding its bar still is still in progress.
        assertEquals(40, w.view(30 * s)?.percent)
        w.progress(1, 100, 31 * s)
        w.progress(0, null, 32 * s)
        assertEquals(CargoWatch.Phase.ASHORE, w.view(32 * s)?.phase)
        w.tick(40 * s)
        assertNull(w.view(40 * s))
        assertFalse(w.awake(40 * s))
    }

    @Test
    fun progressErrorRunsAground() {
        val w = CargoWatch()
        w.progress(1, 60, 0)
        w.progress(2, 60, 1 * s)
        assertEquals(CargoWatch.Phase.AGROUND, w.view(1 * s)?.phase)
        assertNull(w.view(10 * s))
    }

    @Test
    fun progressRemovedShortOfFullJustGoes() {
        val w = CargoWatch()
        w.progress(1, 30, 0)
        w.progress(0, null, 1 * s)
        assertNull(w.view(1 * s))
        assertFalse(w.armed)
    }

    @Test
    fun indeterminateProgressPatrols() {
        val w = CargoWatch()
        w.progress(3, null, 0)
        val v = w.view(0)
        assertEquals(CargoWatch.Phase.SAILING, v?.phase)
        assertNull(v?.percent)
    }

    @Test
    fun aWatchWhoseCargoNeverMovedEndsWithoutADock() {
        val w = CargoWatch()
        w.arm("apt", 0)
        w.commandDone(1, 5 * s)
        assertNull(w.view(5 * s))
        assertFalse(w.awake(5 * s))
    }

    @Test
    fun theSafetyNetIsANetNotTheRule() {
        val w = CargoWatch()
        w.arm("apt", 0)
        w.glean(glean("Unpacking a (1) ..."), 0)
        w.tick(CargoWatch.SAFETY_NET_NS - s)
        assertTrue(w.armed)
        w.tick(CargoWatch.SAFETY_NET_NS + s)
        assertFalse(w.armed)
    }

    @Test
    fun expiredViewsSayWhenTheyExpire() {
        val w = CargoWatch()
        w.arm("apt", 0)
        w.glean(glean("Unpacking a (1) ..."), 2 * s)
        assertEquals(2 * s + CargoWatch.FRESH_NS, w.view(2 * s)?.until)
        w.commandDone(0, 3 * s)
        assertEquals(3 * s + CargoWatch.ASHORE_NS, w.view(3 * s)?.until)
    }
}
