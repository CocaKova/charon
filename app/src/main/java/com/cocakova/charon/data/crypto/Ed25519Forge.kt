package com.cocakova.charon.data.crypto

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Keys born on the phone. Generates an ed25519 pair and writes it out in
 * OpenSSH's own formats — openssh-key-v1 private block (unencrypted; the
 * Keystore seal is the protection at rest) and an authorized_keys public line.
 * The writer is owned code: ~60 lines beats dragging bcpkix in for export.
 * Pure JVM on purpose — the round-trip is proven by unit test against sshj.
 */
@OptIn(ExperimentalEncodingApi::class)
object Ed25519Forge {

    class Forged(
        val privatePem: String,
        val publicLine: String,
        val fingerprint: String,
        val keyType: String = KEY_TYPE,
    )

    private const val KEY_TYPE = "ssh-ed25519"
    private const val MAGIC = "openssh-key-v1"

    fun forge(comment: String): Forged {
        val priv = Ed25519PrivateKeyParameters(SecureRandom())
        val seed = priv.encoded
        val pub = priv.generatePublicKey().encoded
        val safeComment = sanitizeComment(comment)

        val publicBlob = blob {
            sshString(KEY_TYPE.toByteArray(Charsets.US_ASCII))
            sshString(pub)
        }

        val privateBlock = blob {
            val check = SecureRandom().nextInt()
            u32(check)
            u32(check)
            sshString(KEY_TYPE.toByteArray(Charsets.US_ASCII))
            sshString(pub)
            sshString(seed + pub)
            sshString(safeComment.toByteArray(Charsets.UTF_8))
            // Pad to the "none" cipher's block size of 8 with 1, 2, 3, …
            var pad = 1
            while (size() % 8 != 0) write(pad++)
        }

        val keyFile = blob {
            write(MAGIC.toByteArray(Charsets.US_ASCII))
            write(0)
            sshString("none".toByteArray(Charsets.US_ASCII)) // cipher
            sshString("none".toByteArray(Charsets.US_ASCII)) // kdf
            sshString(ByteArray(0))                          // kdf options
            u32(1)                                           // one key
            sshString(publicBlob)
            sshString(privateBlock)
        }

        return Forged(
            privatePem = pemWrap(keyFile),
            publicLine = "$KEY_TYPE ${Base64.encode(publicBlob)} $safeComment",
            fingerprint = fingerprint(publicBlob),
        )
    }

    /** OpenSSH-style display fingerprint of a public-key wire blob. */
    fun fingerprint(publicBlob: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicBlob)
        return "SHA256:" + Base64.encode(digest).trimEnd('=')
    }

    /** authorized_keys comments stay shell-quote-inert: the courier embeds them. */
    fun sanitizeComment(raw: String): String =
        raw.trim().replace(Regex("[^A-Za-z0-9._@-]+"), "-").trim('-').ifBlank { "charon" }

    private fun pemWrap(der: ByteArray): String = buildString {
        append("-----BEGIN OPENSSH PRIVATE KEY-----\n")
        Base64.encode(der).chunked(70).forEach { append(it).append('\n') }
        append("-----END OPENSSH PRIVATE KEY-----\n")
    }

    private class Blob : ByteArrayOutputStream() {
        fun u32(v: Int) {
            write(v ushr 24); write(v ushr 16); write(v ushr 8); write(v)
        }

        fun sshString(bytes: ByteArray) {
            u32(bytes.size)
            write(bytes)
        }
    }

    private inline fun blob(build: Blob.() -> Unit): ByteArray =
        Blob().apply(build).toByteArray()
}
