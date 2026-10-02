package com.cocakova.charon.ssh

import net.schmizz.sshj.common.Buffer

/**
 * The key lent onward — the OpenSSH agent protocol (draft-miller-ssh-agent), the
 * half a forwarded agent channel needs: list the identities, sign with one. Nothing
 * else is offered (no adding, removing or locking keys from the far side), and
 * every signature is announced through [onSign] so the traveller sees the key at
 * work. Pure bytes in, bytes out — the channel plumbing lives in [SshjEngine].
 */
class SshAgent(
    private val keys: List<Key>,
    private val onSign: (Key) -> Unit = {},
) {
    /** One identity the agent holds: its public blob, a comment, and how it signs. */
    class Key(
        val blob: ByteArray,
        val comment: String,
        /** Sign [data] under the agent [flags]; returns (signature format, raw signature). */
        val sign: (data: ByteArray, flags: Int) -> Pair<String, ByteArray>,
    )

    /** Answer one agent message (the bytes after its length prefix). */
    fun handle(request: ByteArray): ByteArray {
        if (request.isEmpty()) return FAILURE
        return try {
            val buf = Buffer.PlainBuffer(request)
            when (buf.readByte().toInt()) {
                REQUEST_IDENTITIES -> identities()
                SIGN_REQUEST -> sign(buf)
                else -> FAILURE
            }
        } catch (_: Exception) {
            // A malformed request costs the far side an answer, never the crossing.
            FAILURE
        }
    }

    private fun identities(): ByteArray {
        val out = Buffer.PlainBuffer()
        out.putByte(IDENTITIES_ANSWER.toByte())
        out.putUInt32(keys.size.toLong())
        for (k in keys) {
            out.putBytes(k.blob)
            out.putString(k.comment)
        }
        return out.compactData
    }

    private fun sign(buf: Buffer.PlainBuffer): ByteArray {
        val blob = buf.readBytes()
        val data = buf.readBytes()
        val flags = buf.readUInt32AsInt()
        val key = keys.firstOrNull { it.blob.contentEquals(blob) } ?: return FAILURE
        val (format, signature) = key.sign(data, flags)
        onSign(key)
        val out = Buffer.PlainBuffer()
        out.putByte(SIGN_RESPONSE.toByte())
        out.putSignature(format, signature)
        return out.compactData
    }

    companion object {
        const val REQUEST_IDENTITIES = 11
        const val IDENTITIES_ANSWER = 12
        const val SIGN_REQUEST = 13
        const val SIGN_RESPONSE = 14
        const val AGENT_FAILURE = 5

        /** Sign flags: which RSA hash the far side wants. */
        const val RSA_SHA2_256 = 2
        const val RSA_SHA2_512 = 4

        private val FAILURE = byteArrayOf(AGENT_FAILURE.toByte())

        /** The signature algorithm an agent [flags] asks of a key of [keyType]. */
        fun signatureName(keyType: String, flags: Int): String = when {
            keyType != "ssh-rsa" -> keyType
            flags and RSA_SHA2_512 != 0 -> "rsa-sha2-512"
            flags and RSA_SHA2_256 != 0 -> "rsa-sha2-256"
            else -> "ssh-rsa"
        }
    }
}
