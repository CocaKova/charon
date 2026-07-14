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
)

/**
 * The Dock's storage: host CRUD with passwords sealed through the Keystore before
 * they ever reach Room. Plaintext exists only in memory, briefly, at save and at
 * connect.
 */
class HostVault(private val dao: HostDao) {

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
                lastConnectedAt = existing?.lastConnectedAt ?: 0L,
                createdAt = existing?.createdAt ?: now,
                lastModified = now,
            ),
        )
    }

    suspend fun delete(id: String) = dao.delete(id)

    /** Unseal and assemble the crossing config. */
    fun connectConfig(host: HostEntity): ConnectConfig = ConnectConfig(
        host = host.host,
        port = host.port,
        username = host.username,
        password = host.passwordSealed
            ?.let { String(SecretVault.open(it), Charsets.UTF_8) },
    )
}
