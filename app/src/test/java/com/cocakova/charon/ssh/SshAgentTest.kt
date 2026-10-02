package com.cocakova.charon.ssh

import com.hierynomus.sshj.key.KeyAlgorithms
import net.schmizz.sshj.common.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator

/** The key lent onward: the agent protocol's two questions, and nothing else. */
class SshAgentTest {

    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val blob = Buffer.PlainBuffer().putPublicKey(pair.public).compactData

    private fun agent(onSign: () -> Unit = {}): SshAgent = SshAgent(
        listOf(
            SshAgent.Key(blob, "charon") { data, flags ->
                val name = SshAgent.signatureName("ssh-rsa", flags)
                val algorithm = when (name) {
                    "rsa-sha2-512" -> KeyAlgorithms.RSASHA512().create()
                    "rsa-sha2-256" -> KeyAlgorithms.RSASHA256().create()
                    else -> KeyAlgorithms.SSHRSA().create()
                }
                val signer = algorithm.newSignature()
                signer.initSign(pair.private)
                signer.update(data)
                algorithm.keyAlgorithm to signer.encode(signer.sign())
            },
        ),
    ) { onSign() }

    private fun message(type: Int, body: Buffer.PlainBuffer.() -> Unit = {}): ByteArray =
        Buffer.PlainBuffer().apply { putByte(type.toByte()); body() }.compactData

    @Test
    fun listsItsOneIdentity() {
        val reply = Buffer.PlainBuffer(agent().handle(message(SshAgent.REQUEST_IDENTITIES)))
        assertEquals(SshAgent.IDENTITIES_ANSWER, reply.readByte().toInt())
        assertEquals(1, reply.readUInt32AsInt())
        assertArrayEquals(blob, reply.readBytes())
        assertEquals("charon", reply.readString())
    }

    @Test
    fun signsWithTheHashTheFarSideAsksFor() {
        var signed = 0
        val data = "session-id and userauth request".toByteArray()
        val reply = Buffer.PlainBuffer(
            agent { signed++ }.handle(
                message(SshAgent.SIGN_REQUEST) {
                    putBytes(blob)
                    putBytes(data)
                    putUInt32(SshAgent.RSA_SHA2_256.toLong())
                },
            ),
        )
        assertEquals(SshAgent.SIGN_RESPONSE, reply.readByte().toInt())
        val sig = Buffer.PlainBuffer(reply.readBytes())
        assertEquals("rsa-sha2-256", sig.readString())
        val verifier = KeyAlgorithms.RSASHA256().create().newSignature()
        verifier.initVerify(pair.public)
        verifier.update(data)
        assertTrue("the signature must verify", verifier.verify(Buffer.PlainBuffer().putSignature("rsa-sha2-256", sig.readBytes()).compactData.let { Buffer.PlainBuffer(it).readBytes() }))
        assertEquals("every signature is announced", 1, signed)
    }

    @Test
    fun refusesKeysItDoesNotHold() {
        val other = Buffer.PlainBuffer().putPublicKey(
            KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair().public,
        ).compactData
        val reply = agent().handle(
            message(SshAgent.SIGN_REQUEST) {
                putBytes(other)
                putBytes(byteArrayOf(1, 2, 3))
                putUInt32(0)
            },
        )
        assertArrayEquals(byteArrayOf(SshAgent.AGENT_FAILURE.toByte()), reply)
    }

    @Test
    fun refusesEverythingElseAndSurvivesJunk() {
        val failure = byteArrayOf(SshAgent.AGENT_FAILURE.toByte())
        assertArrayEquals(failure, agent().handle(message(17))) // ADD_IDENTITY: never from the far side
        assertArrayEquals(failure, agent().handle(message(SshAgent.SIGN_REQUEST)))
        assertArrayEquals(failure, agent().handle(ByteArray(0)))
    }

    @Test
    fun rsaFlagsPickTheHash() {
        assertEquals("ssh-rsa", SshAgent.signatureName("ssh-rsa", 0))
        assertEquals("rsa-sha2-256", SshAgent.signatureName("ssh-rsa", SshAgent.RSA_SHA2_256))
        assertEquals("rsa-sha2-512", SshAgent.signatureName("ssh-rsa", SshAgent.RSA_SHA2_512))
        assertEquals("ssh-ed25519", SshAgent.signatureName("ssh-ed25519", SshAgent.RSA_SHA2_512))
    }
}
