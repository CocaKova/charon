package com.cocakova.charon.data.crypto

import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.security.Security
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The forge's openssh-key-v1 writer is proven against the exact parser that
 * consumes it at connect time: sshj's loadKeys. If these pass, a key born on
 * the phone authenticates like one born from ssh-keygen.
 */
@OptIn(ExperimentalEncodingApi::class)
class Ed25519ForgeTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun bc() {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }

    @Test
    fun `forged key round-trips through sshj`() {
        val forged = Ed25519Forge.forge("test@charon")
        val provider = SSHClient(DefaultConfig()).loadKeys(forged.privatePem, null, null)

        // Private half parses and yields a usable pair.
        val pair = provider.private to provider.public
        assertEquals("EdDSA family", "EdDSA", pair.second.algorithm.replace("Ed25519", "EdDSA"))

        // Public halves agree: wire blob from sshj == blob in our public line.
        val sshjBlob = Buffer.PlainBuffer().putPublicKey(provider.public).compactData
        val lineBlob = Base64.decode(forged.publicLine.split(" ")[1])
        assertArrayEquals(sshjBlob, lineBlob)

        // Fingerprint format sanity.
        assertTrue(forged.fingerprint.startsWith("SHA256:"))
        assertEquals("ssh-ed25519", forged.publicLine.split(" ")[0])
        assertEquals("test@charon", forged.publicLine.split(" ")[2])
    }

    @Test
    fun `comments are sanitized for the courier`() {
        assertEquals("my-phone-key", Ed25519Forge.sanitizeComment("my phone key"))
        assertEquals("a_b.c@d-e", Ed25519Forge.sanitizeComment("a_b.c@d-e"))
        // pure-symbol input has nothing to keep → the fallback name
        assertEquals("charon", Ed25519Forge.sanitizeComment("'\"`$()'"))
        // whatever survives, the result is always shell-quote-inert
        for (nasty in listOf("x'y\"z`w", "'\"$(rm -rf)'", "a b\tc\nd", "  ")) {
            assertTrue(
                "sanitized '$nasty' must be quote-inert",
                Regex("^[A-Za-z0-9._@-]+$").matches(Ed25519Forge.sanitizeComment(nasty)),
            )
        }
    }

    @Test
    fun `key envelope packs and unpacks`() {
        val m1 = KeyEnvelope.unpack(KeyEnvelope.pack("PEMTEXT", "hunter2"))
        assertEquals("PEMTEXT", m1.privateKey)
        assertEquals("hunter2", m1.passphrase)

        val m2 = KeyEnvelope.unpack(KeyEnvelope.pack("PEMTEXT", null))
        assertEquals("PEMTEXT", m2.privateKey)
        assertEquals(null, m2.passphrase)
    }
}
