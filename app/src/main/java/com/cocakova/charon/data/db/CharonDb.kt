package com.cocakova.charon.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A saved crossing. UUID string PKs + lastModified everywhere — the vault
 * export/import (v0.9) merges by UUID, newer wins (docs/VAULT_FORMAT.md).
 */
@Entity(tableName = "hosts")
data class HostEntity(
    @PrimaryKey val id: String,
    val name: String,               // display label; blank = derive user@host
    val host: String,
    val port: Int,
    val username: String,
    /** Password sealed by [com.cocakova.charon.data.crypto.SecretVault]; null = none saved. */
    val passwordSealed: ByteArray?,
    val lastConnectedAt: Long,
    val createdAt: Long,
    val lastModified: Long,
) {
    val displayName: String get() = name.ifBlank { "$username@$host" }
}

/**
 * The ferryman's ledger: one row per (host, port, key type), OpenSSH-style.
 * [publicKey] is the exact wire blob — trust compares bytes, not fingerprints.
 */
@Entity(tableName = "known_hosts", primaryKeys = ["host", "port", "keyType"])
data class KnownHostEntity(
    val host: String,
    val port: Int,
    val keyType: String,
    val publicKey: ByteArray,
    val fingerprint: String,        // "SHA256:…" display form
    val addedAt: Long,
)

@Dao
interface HostDao {
    @Query("SELECT * FROM hosts ORDER BY lastConnectedAt DESC, createdAt DESC")
    fun all(): Flow<List<HostEntity>>

    @Query("SELECT * FROM hosts WHERE id = :id")
    suspend fun byId(id: String): HostEntity?

    @Upsert
    suspend fun upsert(host: HostEntity)

    @Query("DELETE FROM hosts WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE hosts SET lastConnectedAt = :at WHERE id = :id")
    suspend fun touchConnected(id: String, at: Long)
}

@Dao
interface KnownHostDao {
    @Query("SELECT * FROM known_hosts WHERE host = :host AND port = :port AND keyType = :keyType")
    suspend fun find(host: String, port: Int, keyType: String): KnownHostEntity?

    @Query("SELECT * FROM known_hosts WHERE host = :host AND port = :port")
    suspend fun allFor(host: String, port: Int): List<KnownHostEntity>

    @Upsert
    suspend fun upsert(entry: KnownHostEntity)
}

@Database(
    entities = [HostEntity::class, KnownHostEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class CharonDb : RoomDatabase() {
    abstract fun hosts(): HostDao
    abstract fun knownHosts(): KnownHostDao

    companion object {
        fun build(context: Context): CharonDb =
            Room.databaseBuilder(context, CharonDb::class.java, "charon.db").build()
    }
}
