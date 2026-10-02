package com.cocakova.charon.ssh

import com.cocakova.charon.cargo.CargoWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lading, end to end through the session: every way a crossing with cargo can
 * end must leave the barge docked or gone — never sailing over a finished install.
 * Control characters are built from code points, never typed raw into this source.
 */
class TerminalSessionCargoTest {

    private val esc = Char(0x1B).toString()
    private val bel = Char(0x07).toString()
    private val ctrlC = Char(0x03).toString()
    private val ms = 1_000_000L

    /** The test's own clock: every beat of the lading reads this. */
    private var now = 1_000L * 1_000_000_000L

    private fun session() = TerminalSession("t", cols = 60, rows = 12, clock = { now })

    private fun TerminalSession.after(millis: Long): Boolean {
        now += millis * ms
        return cargoTick()
    }

    private fun TerminalSession.feed(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        feedRemote(b, 0, b.size)
    }

    private fun TerminalSession.d(exit: Int) = feed("$esc]133;D;$exit$bel")

    /** Submit an apt install at a prompt and let its first lines land. */
    private fun startInstall(s: TerminalSession) {
        s.feed("$ ")
        s.trackInput("sudo apt install nginx")
        s.trackInput("\r")
        s.feed("sudo apt install nginx\r\n")
        s.feed("Unpacking nginx-core (1.18.0-6ubuntu14) ...\r\n")
        assertTrue(s.after(10))
    }

    @Test
    fun `the finished install's tail above the prompt never keeps the barge sailing`() {
        // The root cause, pinned: the glean read the screen, and an install's last
        // verb lines stay in the bottom rows after the prompt returns. Typing at that
        // prompt echoes — the wire is never silent — so the old minute-of-silence
        // exit never came, and every glean found "Setting up…" again.
        val s = session()
        startInstall(s)
        assertEquals(CargoWatch.Phase.SAILING, s.cargo.value?.phase)
        s.feed("Setting up nginx-core (1.18.0-6ubuntu14) ...\r\n")
        s.feed("Processing triggers for man-db (2.10.2-1) ...\r\n")
        s.after(200)
        assertEquals("man-db", s.cargo.value?.item)
        // An unrigged shell: the prompt returns with no D mark.
        s.feed("me@host:~$ ")
        repeat(40) {
            s.trackInput("l")
            s.feed("l")
            s.after(200)
        }
        assertNull("the barge must not sail over a finished install", s.cargo.value)
    }

    @Test
    fun `a rigged shell's D docks the barge, then it goes`() {
        val s = session()
        startInstall(s)
        s.feed("Setting up nginx-core (1.18.0-6ubuntu14) ...\r\n")
        s.d(0)
        val docked = s.cargo.value
        assertEquals(CargoWatch.Phase.ASHORE, docked?.phase)
        assertEquals(100, docked?.percent)
        assertTrue(s.cargoAwake.value)
        assertFalse(s.after(5_000))
        assertNull(s.cargo.value)
        assertFalse(s.cargoAwake.value)
    }

    @Test
    fun `a failed install runs aground with its exit code`() {
        val s = session()
        startInstall(s)
        s.feed("E: Unable to locate package nginx-cor\r\n")
        s.d(100)
        assertEquals(CargoWatch.Phase.AGROUND, s.cargo.value?.phase)
        assertEquals(100, s.cargo.value?.exit)
    }

    @Test
    fun `ctrl-C sinks the barge at once`() {
        val s = session()
        startInstall(s)
        assertNotNull(s.cargo.value)
        s.trackInput(ctrlC)
        assertNull(s.cargo.value)
        assertFalse(s.cargoAwake.value)
        // The late D for the abandoned command docks nothing.
        s.d(130)
        assertNull(s.cargo.value)
    }

    @Test
    fun `the alternate screen takes the barge with it`() {
        val s = session()
        startInstall(s)
        s.feed("$esc[?1049h")
        assertNull(s.cargo.value)
        assertNull(s.cargoManager)
    }

    @Test
    fun `a dropped transport takes the barge with it`() {
        val s = session()
        startInstall(s)
        s.transportDropped()
        assertNull(s.cargo.value)
        assertFalse(s.cargoAwake.value)
        // The redialed shell's first prompt sounds no D for the old command.
        s.d(0)
        assertNull(s.cargo.value)
    }

    @Test
    fun `an erased progress bar never freezes the percent`() {
        val s = session()
        startInstall(s)
        s.feed("Progress: [ 98%]")
        s.after(200)
        assertEquals(98, s.cargo.value?.percent)
        // apt erases its bar and keeps going.
        s.feed("\r$esc[K")
        repeat(20) { i ->
            s.feed("Setting up pkg$i (1.0) ...\r\n")
            s.after(200)
        }
        assertEquals(CargoWatch.Phase.SAILING, s.cargo.value?.phase)
        assertNull(s.cargo.value?.percent)
    }

    @Test
    fun `a long install keeps sailing while cargo keeps coming`() {
        val s = session()
        startInstall(s)
        repeat(30) { i ->
            s.feed("Unpacking lib$i (2.$i) ...\r\n")
            s.after(1_000)
            assertEquals(CargoWatch.Phase.SAILING, s.cargo.value?.phase)
            assertFalse(s.cargo.value!!.still)
        }
    }

    @Test
    fun `the last install's tail doesn't sail the next barge`() {
        val s = session()
        startInstall(s)
        s.feed("Setting up nginx-core (1.18.0-6ubuntu14) ...\r\n$ ")
        s.after(200)
        s.after(10_000)
        assertNull(s.cargo.value)
        // A second install armed with the first one's lines still on screen.
        s.trackInput("sudo apt install jq")
        s.trackInput("\r")
        s.feed("sudo apt install jq\r\n")
        s.after(200)
        assertNull("nothing new has landed yet", s.cargo.value)
        s.feed("Unpacking jq (1.6) ...\r\n")
        s.after(200)
        assertEquals("jq", s.cargo.value?.item)
    }

    @Test
    fun `answering the install keeps the watch, a new command after it lets go`() {
        val s = session()
        s.trackInput("sudo apt install nginx\r")
        s.feed("Reading package lists... Done\r\nDo you want to continue? [Y/n] ")
        s.after(200)
        s.after(9_000)
        s.trackInput("y\r")
        assertEquals("apt", s.cargoManager)
        s.feed("y\r\nUnpacking nginx (1.18) ...\r\n$ ")
        s.after(200)
        s.after(9_000)
        s.trackInput("git status\r")
        assertNull(s.cargoManager)
    }

    @Test
    fun `the safety net disarms a watch nothing ever answered`() {
        val s = session()
        s.trackInput("sudo apt install nginx\r")
        assertTrue(s.after(0))
        assertFalse(s.after(CargoWatch.SAFETY_NET_NS / ms + 1_000))
        assertNull(s.cargoManager)
    }

    @Test
    fun `a rigged install's quiet stretch holds the barge still until D`() {
        val s = session()
        s.d(0) // the first rigged prompt: this shell reports its commands finishing
        startInstall(s)
        s.feed("Setting up linux-image-6.8 (6.8.0-45) ...\r\n")
        repeat(50) {
            s.after(200)
        }
        val held = s.cargo.value
        assertEquals(CargoWatch.Phase.SAILING, held?.phase)
        assertTrue("a quiet barge is a still one", held!!.still)
        s.d(0)
        assertEquals(CargoWatch.Phase.ASHORE, s.cargo.value?.phase)
    }
}
