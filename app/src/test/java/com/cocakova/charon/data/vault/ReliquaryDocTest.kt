package com.cocakova.charon.data.vault

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The payload is an honest format: round-trips, and future fields don't break us. */
class ReliquaryDocTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `document round-trips`() {
        val doc = ReliquaryDoc(
            exportedAt = "2026-07-17T12:00:00Z",
            appVersion = "0.9.0",
            hosts = listOf(
                RHost(
                    id = "h1", name = "silas", host = "192.168.50.253", port = 2222,
                    username = "cocakova", password = "hunter2", identityId = "i1",
                    harbor = "spark", colorHex = "#3ECFB2", startupCommand = "tmux new -As main",
                    autoReconnect = true, lastConnectedAt = 5, createdAt = 1, lastModified = 9,
                ),
            ),
            identities = listOf(
                RIdentity(
                    id = "i1", name = "spark-dev", keyType = "ssh-ed25519",
                    publicLine = "ssh-ed25519 AAAA spark", fingerprint = "SHA256:xyz",
                    privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\n…", passphrase = null,
                    biometricGated = true, createdAt = 1, lastModified = 2,
                ),
            ),
            knownHosts = listOf(RKnownHost("192.168.50.253", 22, "ssh-ed25519", "QUJD", "SHA256:abc", 7)),
            snippets = listOf(RSnippet("s1", "logs", "journalctl -f", null, 0, 1, 2)),
            portForwards = listOf(RPortForward("f1", "h1", "L", 5174, "localhost", 5173, true, 1, 2)),
        )
        val back = json.decodeFromString<ReliquaryDoc>(json.encodeToString(doc))
        assertEquals(doc, back)
    }

    @Test
    fun `future fields are ignored, absent fields take defaults`() {
        val back = json.decodeFromString<ReliquaryDoc>(
            """{"exportedAt":"x","hosts":[{"id":"a","host":"h","futureField":42}],"newTable":[]}""",
        )
        assertEquals(1, back.hosts.size)
        assertEquals(22, back.hosts[0].port)
        assertNull(back.hosts[0].password)
        assertEquals(true, back.hosts[0].autoReconnect)
    }
}
