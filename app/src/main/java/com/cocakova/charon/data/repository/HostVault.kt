package com.cocakova.charon.data.repository

import com.cocakova.charon.data.crypto.SecretVault
import com.cocakova.charon.data.db.HostDao
import com.cocakova.charon.data.db.HostEntity
import com.cocakova.charon.ssh.ConnectConfig
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** Everything a host edit sheet hands back. Blank password = keep the stored one. */
data class HostDraft(
    val id: String?,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val identityId: String? = null,
)

/**
 * The Dock's storage: host CRUD with passwords sealed through the Keystore before
 * they ever reach Room. Plaintext exists only in memory, briefly, at save and at
 * connect. Key-based crossings resolve their identity through [KeyVault], which
 * may cost a fingerprint — hence connectConfig suspends, and returns null when
 * the user backs out of the prompt.
 */
class HostVault(private val dao: HostDao, private val keyVault: KeyVault) {

    val hosts: Flow<List<HostEntity>> = dao.all()

    suspend fun save(draft: HostDraft) {
        val now = System.currentTimeMillis()
        val existing = draft.id?.let { dao.byId(it) }
        val sealed = when {
            draft.password.isNotEmpty() ->
                SecretVault.seal(draft.password.toByteArray(Charsets.UTF_8))
            else -> existing?.passwordSealed
        }
        dao.upsert(
            HostEntity(
                id = existing?.id ?: draft.id ?: UUID.randomUUID().toString(),
                name = draft.name.trim(),
                host = draft.host.trim(),
                port = draft.port,
                username = draft.username.trim(),
                passwordSealed = sealed,
                identityId = draft.identityId,
                lastConnectedAt = existing?.lastConnectedAt ?: 0L,
                createdAt = existing?.createdAt ?: now,
                lastModified = now,
            ),
        )
    }

    suspend fun delete(id: String) = dao.delete(id)

    /** Unseal and assemble the crossing config; null = biometric prompt dismissed. */
    suspend fun connectConfig(host: HostEntity): ConnectConfig? {
        val material = host.identityId?.let { id ->
            val identity = keyVault.byId(id) ?: return@let null
            keyVault.material(identity) ?: return null // bio prompt dismissed
        }
        return ConnectConfig(
            host = host.host,
            port = host.port,
            username = host.username,
            password = host.passwordSealed
                ?.let { String(SecretVault.open(it), Charsets.UTF_8) },
            privateKeyPem = material?.privateKey,
            keyPassphrase = material?.passphrase,
        )
    }

    /** Same, for an unsaved draft straight off the edit sheet. */
    suspend fun draftConfig(draft: HostDraft): ConnectConfig? {
        val existing = draft.id?.let { dao.byId(it) }
        val material = draft.identityId?.let { id ->
            val identity = keyVault.byId(id) ?: return@let null
            keyVault.material(identity) ?: return null
        }
        return ConnectConfig(
            host = draft.host.trim(),
            port = draft.port,
            username = draft.username.trim(),
            password = draft.password.takeIf { it.isNotBlank() }
                ?: existing?.passwordSealed
                    ?.let { String(SecretVault.open(it), Charsets.UTF_8) },
            privateKeyPem = material?.privateKey,
            keyPassphrase = material?.passphrase,
        )
    }

    /** Password-only config used to carry a new public key to a saved host. */
    suspend fun courierConfig(host: HostEntity): ConnectConfig? {
        val password = host.passwordSealed
            ?.let { String(SecretVault.open(it), Charsets.UTF_8) }
            ?: return null
        return ConnectConfig(
            host = host.host,
            port = host.port,
            username = host.username,
            password = password,
        )
    }
}
