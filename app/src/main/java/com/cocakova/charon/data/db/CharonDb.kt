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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    /** Crossing key: id of an [IdentityEntity]; null = password auth. */
    val identityId: String?,
    val lastConnectedAt: Long,
    val createdAt: Long,
    val lastModified: Long,
) {
    val displayName: String get() = name.ifBlank { "$username@$host" }
}

/**
 * A key of passage. [materialSealed] is a KeyEnvelope (private key text + optional
 * passphrase) sealed by SecretVault — under the plain vault key, or under the
 * biometric-gated key when [biometricGated], in which case every unseal takes a
 * fingerprint. The private key never touches Room in the clear.
 */
@Entity(tableName = "identities")
data class IdentityEntity(
    @PrimaryKey val id: String,
    val name: String,
    val keyType: String,            // "ssh-ed25519", "ssh-rsa", …
    val publicLine: String,         // full authorized_keys line: type + base64 + comment
    val fingerprint: String,        // "SHA256:…" display form
    val materialSealed: ByteArray,
    val biometricGated: Boolean,
    val createdAt: Long,
    val lastModified: Long,
)

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
interface IdentityDao {
    @Query("SELECT * FROM identities ORDER BY createdAt DESC")
    fun all(): Flow<List<IdentityEntity>>

    @Query("SELECT * FROM identities WHERE id = :id")
    suspend fun byId(id: String): IdentityEntity?

    @Upsert
    suspend fun upsert(identity: IdentityEntity)

    @Query("DELETE FROM identities WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE hosts SET identityId = NULL WHERE identityId = :id")
    suspend fun unbindHosts(id: String)
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
    entities = [HostEntity::class, KnownHostEntity::class, IdentityEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class CharonDb : RoomDatabase() {
    abstract fun hosts(): HostDao
    abstract fun knownHosts(): KnownHostDao
    abstract fun identities(): IdentityDao

    companion object {
        // v0.2 → v0.3: keys of passage.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE hosts ADD COLUMN identityId TEXT")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `identities` (
                        `id` TEXT NOT NULL, `name` TEXT NOT NULL,
                        `keyType` TEXT NOT NULL, `publicLine` TEXT NOT NULL,
                        `fingerprint` TEXT NOT NULL, `materialSealed` BLOB NOT NULL,
                        `biometricGated` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL, `lastModified` INTEGER NOT NULL,
                        PRIMARY KEY(`id`))""",
                )
            }
        }

        fun build(context: Context): CharonDb =
            Room.databaseBuilder(context, CharonDb::class.java, "charon.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
