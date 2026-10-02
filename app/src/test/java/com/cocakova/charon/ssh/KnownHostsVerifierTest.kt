package com.cocakova.charon.ssh

import com.cocakova.charon.data.db.KnownHostDao
import com.cocakova.charon.data.db.KnownHostEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.PublicKey

/**
 * The ferryman's ledger, as a hailed (unmoored) crossing meets it: the first
 * meeting always asks, an accepted key is remembered by host and port — not by
 * mooring — so the same hail again sails through, and a changed key never does.
 */
class KnownHostsVerifierTest {

    private class Ledger : KnownHostDao {
        val rows = mutableMapOf<Triple<String, Int, String>, KnownHostEntity>()
        override suspend fun find(host: String, port: Int, keyType: String) = rows[Triple(host, port, keyType)]
        override suspend fun allFor(host: String, port: Int) = rows.values.filter { it.host == host && it.port == port }
        override suspend fun allOnce() = rows.values.toList()
        override suspend fun upsert(entry: KnownHostEntity) {
            rows[Triple(entry.host, entry.port, entry.keyType)] = entry
        }
    }

    private fun rsaKey(): PublicKey =
        KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair().public

    private fun verifier(ledger: Ledger, asked: MutableList<TrustRequest>, answer: Boolean) =
        KnownHostsVerifier(ledger, fingerprintOf = { "SHA256:" + it.size }) { req ->
            asked += req
            answer
        }

    @Test
    fun aHailedHostIsMetOnceThenKnown() {
        val ledger = Ledger()
        val asked = mutableListOf<TrustRequest>()
        val key = rsaKey()
        assertTrue(verifier(ledger, asked, true).verify("10.0.0.5", 2222, key))
        assertEquals(1, asked.size)
        assertTrue(asked.single() is TrustRequest.FirstMeeting)
        // The same hail again — a fresh connection, no mooring anywhere: no prompt.
        assertTrue(verifier(ledger, asked, false).verify("10.0.0.5", 2222, key))
        assertEquals(1, asked.size)
    }

    @Test
    fun anotherPortIsAnotherShore() {
        val ledger = Ledger()
        val asked = mutableListOf<TrustRequest>()
        val key = rsaKey()
        verifier(ledger, asked, true).verify("10.0.0.5", 2222, key)
        verifier(ledger, asked, true).verify("10.0.0.5", 22, key)
        assertEquals(2, asked.size)
    }

    @Test
    fun aRefusedMeetingIsNotRemembered() {
        val ledger = Ledger()
        val asked = mutableListOf<TrustRequest>()
        assertFalse(verifier(ledger, asked, false).verify("h", 22, rsaKey()))
        assertTrue(ledger.rows.isEmpty())
    }

    @Test
    fun aChangedKeyAlwaysAsksAndRefusesByDefault() {
        val ledger = Ledger()
        val asked = mutableListOf<TrustRequest>()
        verifier(ledger, asked, true).verify("h", 22, rsaKey())
        assertFalse(verifier(ledger, asked, false).verify("h", 22, rsaKey()))
        assertTrue(asked.last() is TrustRequest.Changed)
    }
}
