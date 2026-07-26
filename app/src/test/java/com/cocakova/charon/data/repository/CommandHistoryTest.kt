package com.cocakova.charon.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The river's memory, hardened: sealed at rest, secrets refused at the door,
 * paste-bombs turned away, and the user able to make it forget.
 */
class CommandHistoryTest {

    private class MemStore : CommandHistory.Store {
        val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }

    /** A reversible toy seal — enough to prove the store never holds plaintext. */
    private fun crypt(b: ByteArray) = ByteArray(b.size) { (b[it].toInt() xor 0x5A).toByte() }

    private fun history(store: MemStore) = CommandHistory(store, ::crypt, ::crypt)

    @Test
    fun recordsMostRecentFirstAndDedupes() {
        val h = history(MemStore())
        h.record("ls -la")
        h.record("tmux attach -t main")
        h.record("ls -la")
        assertEquals(listOf("ls -la", "tmux attach -t main"), h.entries.value.map { it.line })
    }

    @Test
    fun secretLinesNeverEnter() {
        val h = history(MemStore())
        h.record("PGPASSWORD=hunter2 psql -h db")
        h.record("vault login --token=s.abc")
        assertTrue(h.entries.value.isEmpty())
    }

    @Test
    fun pasteBombsAndControlCharactersAreRefused() {
        val h = history(MemStore())
        h.record("x".repeat(600))
        h.record("ls -la")
        h.record("a")
        assertTrue(h.entries.value.isEmpty())
    }

    @Test
    fun forgetDropsOneLineAndClearDrainsAll() {
        val h = history(MemStore())
        h.record("ls -la")
        h.record("git status")
        h.forget("ls -la")
        assertEquals(listOf("git status"), h.entries.value.map { it.line })
        h.clear()
        assertTrue(h.entries.value.isEmpty())
    }

    @Test
    fun rememberedLinesCarryTheirMooring() {
        val h = history(MemStore())
        h.record("systemctl status palworld", host = "host-1")
        h.record("uptime")
        assertEquals("host-1", h.entries.value.first { it.line.startsWith("systemctl") }.host)
        assertNull(h.entries.value.first { it.line == "uptime" }.host)
    }

    @Test
    fun persistsSealedAcrossInstances() {
        val store = MemStore()
        history(store).record("tmux attach -t main")
        // Nothing legible at rest: neither the command nor bare JSON structure.
        assertTrue(store.map.values.none { it.contains("tmux") })
        assertTrue(store.map.values.none { it.contains("entries") })
        val reborn = history(store)
        assertEquals(listOf("tmux attach -t main"), reborn.entries.value.map { it.line })
    }

    @Test
    fun legacyPlaintextMigratesScreenedAndBurns() {
        val store = MemStore()
        store.map["commands"] = "tmux attach -t main\nPGPASSWORD=x psql\nls -la"
        val h = history(store)
        assertEquals(listOf("tmux attach -t main", "ls -la"), h.entries.value.map { it.line })
        assertNull(store.map["commands"])
        assertNotNull(store.map["commands_v2"])
        assertFalse(store.map["commands_v2"]!!.contains("tmux"))
    }

    @Test
    fun corruptSealedStoreForgetsInsteadOfCrashing() {
        val store = MemStore()
        store.map["commands_v2"] = "not!!valid@@base64%%at--all"
        val h = history(store)
        assertTrue(h.entries.value.isEmpty())
        h.record("ls -la")
        assertEquals(1, history(store).entries.value.size)
    }
}
