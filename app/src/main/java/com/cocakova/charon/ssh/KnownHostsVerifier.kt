package com.cocakova.charon.ssh

import android.util.Base64
import com.cocakova.charon.data.db.KnownHostDao
import com.cocakova.charon.data.db.KnownHostEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.MessageDigest
import java.security.PublicKey

/** What the UI must decide when the ferryman presents a key. */
sealed class TrustRequest {
    abstract val host: String
    abstract val port: Int
    abstract val keyType: String
    abstract val fingerprint: String

    /** First meeting: no ledger entry for this host+key type (TOFU). */
    data class FirstMeeting(
        override val host: String,
        override val port: Int,
        override val keyType: String,
        override val fingerprint: String,
    ) : TrustRequest()

    /** The key CHANGED. Possibly a reinstall — possibly an interception. */
    data class Changed(
        override val host: String,
        override val port: Int,
        override val keyType: String,
        override val fingerprint: String,
        val knownFingerprint: String,
    ) : TrustRequest()
}

/** A trust decision in flight: the connect thread blocks until the UI resolves it. */
class PendingTrust(val request: TrustRequest) {
    internal val decision = CompletableDeferred<Boolean>()
    fun resolve(trust: Boolean) {
        decision.complete(trust)
    }
}

/**
 * The ferryman's ledger, enforced. Room-backed TOFU: unknown keys ask the user
 * (fingerprint as the toll), byte-exact matches pass silently, changed keys raise
 * the red flag and refuse unless the user explicitly replaces the entry.
 *
 * Runs on the connect thread (already blocking I/O land) — DB reads and the UI
 * decision wait are runBlocking on purpose.
 */
class KnownHostsVerifier(
    private val dao: KnownHostDao,
    private val requestDecision: (TrustRequest) -> Boolean,
) : HostKeyVerifier {

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val keyType = KeyType.fromKey(key).toString()
        val wire = Buffer.PlainBuffer().putPublicKey(key).compactData
        val fingerprint = sha256Fingerprint(wire)
        val known = runBlocking { dao.find(hostname, port, keyType) }
        return when {
            known == null ->
                requestDecision(TrustRequest.FirstMeeting(hostname, port, keyType, fingerprint))
                    .also { trusted -> if (trusted) remember(hostname, port, keyType, wire, fingerprint) }

            known.publicKey.contentEquals(wire) -> true

            else ->
                requestDecision(
                    TrustRequest.Changed(hostname, port, keyType, fingerprint, known.fingerprint),
                ).also { trusted -> if (trusted) remember(hostname, port, keyType, wire, fingerprint) }
        }
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
        runBlocking { dao.allFor(hostname, port).map { it.keyType } }

    private fun remember(host: String, port: Int, keyType: String, wire: ByteArray, fp: String) {
        runBlocking {
            dao.upsert(
                KnownHostEntity(
                    host = host, port = port, keyType = keyType,
                    publicKey = wire, fingerprint = fp,
                    addedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    companion object {
        /** OpenSSH-style: "SHA256:" + unpadded base64 of the wire-blob digest. */
        fun sha256Fingerprint(wireBlob: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(wireBlob)
            return "SHA256:" + Base64.encodeToString(
                digest, Base64.NO_PADDING or Base64.NO_WRAP,
            )
        }
    }
}
