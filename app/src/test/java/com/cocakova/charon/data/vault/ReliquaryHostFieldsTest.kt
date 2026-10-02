package com.cocakova.charon.data.vault

import com.cocakova.charon.data.db.HostEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 1.2 fields (the key lent onward, the crossing via) survive a reliquary both ways. */
class ReliquaryHostFieldsTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun host(id: String, jump: String?, agent: Boolean) = HostEntity(
        id = id, name = id, host = "$id.example", port = 22, username = "ferry",
        passwordSealed = null, identityId = "k1", harbor = "", colorHex = null,
        startupCommand = "", autoReconnect = true,
        agentForwarding = agent, jumpHostId = jump,
        lastConnectedAt = 3, createdAt = 1, lastModified = 2,
    )

    private fun travel(h: HostEntity): RHost =
        json.decodeFromString<ReliquaryDoc>(
            json.encodeToString(ReliquaryDoc(exportedAt = "x", hosts = listOf(h.toRHost(password = null)))),
        ).hosts.single()

    @Test
    fun `agent forwarding and the jump cross the water and land intact`() {
        val inner = host("inner", jump = "bastion", agent = true)
        val landed = travel(inner).toHostEntity(existing = null, identityIds = setOf("k1"), sealedPassword = null)
        assertTrue(landed.agentForwarding)
        assertEquals("bastion", landed.jumpHostId)
        assertEquals("k1", landed.identityId)
        assertEquals(inner.copy(passwordSealed = null), landed.copy(passwordSealed = null))
    }

    @Test
    fun `a reliquary from before 1_2 lands straight across with the key kept home`() {
        val old = json.decodeFromString<ReliquaryDoc>(
            """{"exportedAt":"x","hosts":[{"id":"a","host":"h","username":"u"}]}""",
        ).hosts.single()
        assertFalse(old.agentForwarding)
        assertNull(old.jumpHostId)
        val landed = old.toHostEntity(existing = null, identityIds = emptySet(), sealedPassword = null)
        assertFalse(landed.agentForwarding)
        assertNull(landed.jumpHostId)
    }

    @Test
    fun `a mooring can't jump through itself, and a key left ashore isn't referenced`() {
        val loop = host("self", jump = "self", agent = false).toRHost(password = null)
        val landed = loop.toHostEntity(existing = null, identityIds = emptySet(), sealedPassword = null)
        assertNull(landed.jumpHostId)
        assertNull(landed.identityId)
    }

    @Test
    fun `without a travelled password the existing seal stays`() {
        val sealed = byteArrayOf(1, 2, 3)
        val existing = host("a", jump = null, agent = false).copy(passwordSealed = sealed, lastConnectedAt = 99)
        val landed = host("a", jump = null, agent = true).toRHost(password = null)
            .toHostEntity(existing = existing, identityIds = setOf("k1"), sealedPassword = null)
        assertArrayEquals(sealed, landed.passwordSealed)
        assertEquals(99, landed.lastConnectedAt)
        assertTrue(landed.agentForwarding)
    }
}
