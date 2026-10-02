package com.cocakova.charon.data.db

import androidx.room.RoomOpenDelegate
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * The ledger survives the upgrade: a v5 database (1.1.x, written down verbatim from
 * Room's own generated schema) is walked through the app's migrations and then
 * checked by Room's generated validator for the current entities — the same check
 * Room runs on the phone before it lets the app open the file. Plain JVM SQLite
 * (sqlite-jdbc), no device: the role MigrationTestHelper plays in instrumented tests.
 */
class CharonDbMigrationTest {

    private val jdbc: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")
    private val connection = JdbcConnection(jdbc)

    @After
    fun close() = jdbc.close()

    @Test
    fun `v5 walks to v6 and Room's own validator accepts it`() {
        V5_SCHEMA.forEach { jdbc.createStatement().use { s -> s.execute(it) } }
        jdbc.createStatement().use {
            it.execute(
                "INSERT INTO hosts (id, name, host, port, username, passwordSealed, identityId, harbor, " +
                    "colorHex, startupCommand, autoReconnect, lastConnectedAt, createdAt, lastModified) " +
                    "VALUES ('h1', 'devbox', 'devbox.example', 2222, 'ferry', NULL, 'k1', 'home', " +
                    "'#3ECFB2', 'tmux new -As main', 1, 5, 1, 9)",
            )
        }

        migrate(from = 5)

        val result = generatedDelegate().onValidateSchema(connection)
        assertTrue(result.expectedFoundMsg ?: "", result.isValid)

        jdbc.createStatement().use { s ->
            s.executeQuery("SELECT name, port, autoReconnect, agentForwarding, jumpHostId FROM hosts WHERE id = 'h1'").use { rs ->
                assertTrue(rs.next())
                assertEquals("devbox", rs.getString(1))
                assertEquals(2222, rs.getInt(2))
                assertEquals(1, rs.getInt(3))
                assertEquals("the key stays home unless asked", 0, rs.getInt(4))
                rs.getString(5)
                assertTrue("straight across unless asked", rs.wasNull())
            }
        }
    }

    @Test
    fun `the migration chain is unbroken up to the current version`() {
        val steps = CharonDb.MIGRATIONS
        for (i in 1 until steps.size) assertEquals(steps[i - 1].endVersion, steps[i].startVersion)
        assertEquals(1, steps.first().startVersion)
        assertEquals(generatedDelegate().version, steps.last().endVersion)
    }

    private fun migrate(from: Int) {
        val db = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            when {
                method.name == "execSQL" && args?.size == 1 -> {
                    jdbc.createStatement().use { it.execute(args[0] as String) }; null
                }
                else -> throw UnsupportedOperationException("migration used ${method.name}")
            }
        } as SupportSQLiteDatabase
        CharonDb.MIGRATIONS.filter { it.startVersion >= from }.forEach { it.migrate(db) }
    }

    private fun generatedDelegate(): RoomOpenDelegate {
        val impl = Class.forName("com.cocakova.charon.data.db.CharonDb_Impl").getDeclaredConstructor().newInstance()
        val create = impl.javaClass.getDeclaredMethod("createOpenDelegate").apply { isAccessible = true }
        return create.invoke(impl) as RoomOpenDelegate
    }

    /** androidx.sqlite's connection over JDBC: enough for Room's schema reader. */
    private class JdbcConnection(private val c: Connection) : SQLiteConnection {
        override fun prepare(sql: String): SQLiteStatement = JdbcStatement(c.prepareStatement(sql))
        override fun close() = Unit
    }

    private class JdbcStatement(private val ps: PreparedStatement) : SQLiteStatement {
        private var rs: ResultSet? = null
        private var executed = false

        private fun results(): ResultSet {
            if (!executed) {
                executed = true
                if (ps.execute()) rs = ps.resultSet
            }
            return rs ?: error("no rows")
        }

        override fun bindBlob(index: Int, value: ByteArray) = ps.setBytes(index, value)
        override fun bindDouble(index: Int, value: Double) = ps.setDouble(index, value)
        override fun bindLong(index: Int, value: Long) = ps.setLong(index, value)
        override fun bindText(index: Int, value: String) = ps.setString(index, value)
        override fun bindNull(index: Int) = ps.setObject(index, null)
        override fun getBlob(index: Int): ByteArray = results().getBytes(index + 1) ?: ByteArray(0)
        override fun getDouble(index: Int): Double = results().getDouble(index + 1)
        override fun getLong(index: Int): Long = results().getLong(index + 1)
        override fun getText(index: Int): String = results().getString(index + 1) ?: ""
        override fun isNull(index: Int): Boolean = results().getObject(index + 1) == null
        override fun getColumnCount(): Int = results().metaData.columnCount
        override fun getColumnName(index: Int): String = results().metaData.getColumnName(index + 1)
        override fun getColumnType(index: Int): Int = when (results().getObject(index + 1)) {
            null -> 5
            is Int, is Long -> 1
            is Double, is Float -> 2
            is ByteArray -> 4
            else -> 3
        }

        override fun step(): Boolean {
            if (!executed) {
                executed = true
                if (!ps.execute()) return false
                rs = ps.resultSet
            }
            return rs?.next() ?: false
        }

        override fun reset() {
            rs?.close(); rs = null; executed = false
        }

        override fun clearBindings() = ps.clearParameters()
        override fun close() {
            rs?.close(); ps.close()
        }
    }

    private companion object {
        /** Room's generated createAllTables for version 5 (Charon 1.1.x), verbatim. */
        val V5_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `hosts` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `host` TEXT NOT NULL, `port` INTEGER NOT NULL, `username` TEXT NOT NULL, `passwordSealed` BLOB, `identityId` TEXT, `harbor` TEXT NOT NULL, `colorHex` TEXT, `startupCommand` TEXT NOT NULL, `autoReconnect` INTEGER NOT NULL, `lastConnectedAt` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `lastModified` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `known_hosts` (`host` TEXT NOT NULL, `port` INTEGER NOT NULL, `keyType` TEXT NOT NULL, `publicKey` BLOB NOT NULL, `fingerprint` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`host`, `port`, `keyType`))",
            "CREATE TABLE IF NOT EXISTS `identities` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `keyType` TEXT NOT NULL, `publicLine` TEXT NOT NULL, `fingerprint` TEXT NOT NULL, `materialSealed` BLOB NOT NULL, `biometricGated` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `lastModified` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `snippets` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `command` TEXT NOT NULL, `hostId` TEXT, `sortOrder` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `lastModified` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `port_forwards` (`id` TEXT NOT NULL, `hostId` TEXT NOT NULL, `type` TEXT NOT NULL, `bindPort` INTEGER NOT NULL, `targetHost` TEXT NOT NULL, `targetPort` INTEGER NOT NULL, `autoStart` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `lastModified` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
        )
    }
}
