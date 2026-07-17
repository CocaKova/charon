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
    /** The harbor this mooring belongs to — the Dock's collapsible group. Blank = unsorted. */
    val harbor: String = "",
    /** Lantern hue as #RRGGBB, a per-host colour tag; null = the default dimmed glow. */
    val colorHex: String? = null,
    /** Command typed into the shell on every crossing — e.g. `tmux new -As main`. Blank = none. */
    val startupCommand: String = "",
    /** Redial on transport death (backoff + instant network-callback redial). */
    val autoReconnect: Boolean = true,
    val lastConnectedAt: Long,
    val createdAt: Long,
    val lastModified: Long,
) {
    val displayName: String get() = name.ifBlank { "$username@$host" }

    /** The address line, wherever a mooring is spelled out: user@host, :port when odd. */
    val address: String get() = "$username@$host" + if (port != 22) ":$port" else ""
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

/**
 * A rehearsed line — one command kept at hand. Global by default; [hostId] pins it
 * to a single mooring (only that host's snippet bar shows it). UUID + lastModified
 * for the vault merge, like everything else.
 */
@Entity(tableName = "snippets")
data class SnippetEntity(
    @PrimaryKey val id: String,
    val name: String,               // short chip label
    val command: String,
    val hostId: String?,            // null = every host
    val sortOrder: Int,
    val createdAt: Long,
    val lastModified: Long,
)

/**
 * A charted channel: one port carried across the river. [type] is "L" (local →
 * remote), "R" (remote → local) or "D" (dynamic SOCKS5 — [targetHost]/[targetPort]
 * unused). [autoStart] channels open with every crossing of their host.
 */
@Entity(tableName = "port_forwards")
data class PortForwardEntity(
    @PrimaryKey val id: String,
    val hostId: String,
    val type: String,               // "L" | "R" | "D"
    val bindPort: Int,              // listening side: phone for L/D, server for R
    val targetHost: String,         // "" for D
    val targetPort: Int,            // 0 for D
    val autoStart: Boolean,
    val createdAt: Long,
    val lastModified: Long,
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

@Dao
interface SnippetDao {
    @Query("SELECT * FROM snippets ORDER BY sortOrder, createdAt")
    fun all(): Flow<List<SnippetEntity>>

    @Upsert
    suspend fun upsert(snippet: SnippetEntity)

    @Query("DELETE FROM snippets WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface PortForwardDao {
    @Query("SELECT * FROM port_forwards ORDER BY createdAt")
    fun all(): Flow<List<PortForwardEntity>>

    @Query("SELECT * FROM port_forwards WHERE hostId = :hostId ORDER BY createdAt")
    suspend fun forHost(hostId: String): List<PortForwardEntity>

    @Upsert
    suspend fun upsert(forward: PortForwardEntity)

    @Query("DELETE FROM port_forwards WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(
    entities = [
        HostEntity::class, KnownHostEntity::class, IdentityEntity::class,
        SnippetEntity::class, PortForwardEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class CharonDb : RoomDatabase() {
    abstract fun hosts(): HostDao
    abstract fun knownHosts(): KnownHostDao
    abstract fun identities(): IdentityDao
    abstract fun snippets(): SnippetDao
    abstract fun portForwards(): PortForwardDao

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

        // v0.3 → v0.4: harbors + lantern colours (scalable Dock categorisation).
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE hosts ADD COLUMN harbor TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE hosts ADD COLUMN colorHex TEXT")
            }
        }

        // v0.4 → v0.5: startup command + auto-reconnect (multi-session milestone).
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE hosts ADD COLUMN startupCommand TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE hosts ADD COLUMN autoReconnect INTEGER NOT NULL DEFAULT 1")
            }
        }

        // v0.6 → v0.7: snippets + charted channels (port forwards).
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `snippets` (
                        `id` TEXT NOT NULL, `name` TEXT NOT NULL, `command` TEXT NOT NULL,
                        `hostId` TEXT, `sortOrder` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL, `lastModified` INTEGER NOT NULL,
                        PRIMARY KEY(`id`))""",
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `port_forwards` (
                        `id` TEXT NOT NULL, `hostId` TEXT NOT NULL, `type` TEXT NOT NULL,
                        `bindPort` INTEGER NOT NULL, `targetHost` TEXT NOT NULL,
                        `targetPort` INTEGER NOT NULL, `autoStart` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL, `lastModified` INTEGER NOT NULL,
                        PRIMARY KEY(`id`))""",
                )
            }
        }

        fun build(context: Context): CharonDb =
            Room.databaseBuilder(context, CharonDb::class.java, "charon.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
    }
}
