package com.cocakova.charon.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.cocakova.charon.autocomplete.SecretGate
import com.cocakova.charon.data.crypto.SecretVault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The river's memory — recent commands, the engine behind the smart-autofill strip.
 * Commands are the lines the user *types* (reconstructed from keystrokes by
 * [com.cocakova.charon.ssh.TerminalSession]; pasted text is never learned); this
 * dedupes them most-recent-first and persists a capped list across launches.
 *
 * Hardened walls, each independent:
 *  - **Sealed at rest.** The store is AES-GCM under the Keystore vault key, not
 *    readable XML. A seal that no longer opens (key invalidated) means the river
 *    forgets — the safe failure.
 *  - **Secrets never enter.** [SecretGate] screens every line at [record]; callers
 *    screen again at suggest time so anything learned before the wall went up
 *    stays silent too.
 *  - **Only honest lines.** Control characters and absurd lengths are refused —
 *    a paste-bomb is not a command.
 *  - **The user commands the memory.** [forget] drops one line; [clear] drains it.
 *
 * Entries remember which mooring taught them ([Entry.host]), so the strip can rank
 * the current host's own lines first. Learning stays global on purpose: homelab
 * incantations repeat across hosts.
 */
class CommandHistory(
    private val store: Store,
    private val seal: (ByteArray) -> ByteArray = { it },
    private val open: (ByteArray) -> ByteArray = { it },
) {
    /** One remembered line and the mooring it was learned on (null = quick connect). */
    @Serializable
    data class Entry(val line: String, val host: String? = null, val at: Long = 0)

    /** Where the sealed document lives — a prefs seam so JVM tests can run in memory. */
    interface Store {
        fun get(key: String): String?
        fun put(key: String, value: String?)
    }

    constructor(context: Context) : this(
        PrefsStore(context.getSharedPreferences("charon_history", Context.MODE_PRIVATE)),
        SecretVault::seal,
        SecretVault::open,
    )

    private val _entries = MutableStateFlow(load())
    /** All remembered commands, most-recent first. */
    val entries: StateFlow<List<Entry>> = _entries

    /** Remember a submitted command, floating it to the front. */
    fun record(command: String, host: String? = null) {
        val cmd = command.trim()
        if (cmd.length !in MIN_LEN..MAX_LINE) return
        if (cmd.any { it.isISOControl() }) return
        if (SecretGate.carriesSecret(cmd)) return
        save(listOf(Entry(cmd, host, System.currentTimeMillis())) + _entries.value.filter { it.line != cmd })
    }

    /** The river forgets one line — a long-press on its suggestion chip. */
    fun forget(line: String) {
        save(_entries.value.filter { it.line != line })
    }

    /** The river forgets everything. */
    fun clear() {
        save(emptyList())
    }

    private fun save(entries: List<Entry>) {
        val kept = entries.take(CAP)
        _entries.value = kept
        store.put(KEY_V2, encode(HistoryDoc(kept)))
    }

    private fun load(): List<Entry> {
        store.get(KEY_V2)?.let { sealedDoc ->
            // A seal that won't open (invalidated key, corrupt store) is forgotten,
            // never a crash — history is a convenience, not a vault of record.
            runCatching { return decode(sealedDoc).entries.take(CAP) }
        }
        // First run after the upgrade: carry the old plaintext list into the sealed
        // store (host unknown), then burn the plaintext.
        val legacy = store.get(KEY_LEGACY) ?: return emptyList()
        val entries = legacy.split("\n").filter { it.isNotBlank() }
            .filter { !SecretGate.carriesSecret(it) }
            .map { Entry(it) }
            .take(CAP)
        store.put(KEY_V2, encode(HistoryDoc(entries)))
        store.put(KEY_LEGACY, null)
        return entries
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun encode(doc: HistoryDoc): String =
        Base64.encode(seal(json.encodeToString(doc).encodeToByteArray()))

    @OptIn(ExperimentalEncodingApi::class)
    private fun decode(stored: String): HistoryDoc =
        json.decodeFromString(open(Base64.decode(stored)).decodeToString())

    @Serializable
    private data class HistoryDoc(val entries: List<Entry> = emptyList())

    private class PrefsStore(private val prefs: SharedPreferences) : Store {
        override fun get(key: String): String? = prefs.getString(key, null)
        override fun put(key: String, value: String?) {
            prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
        }
    }

    private companion object {
        const val KEY_LEGACY = "commands"
        const val KEY_V2 = "commands_v2"
        const val CAP = 300
        const val MIN_LEN = 2
        const val MAX_LINE = 500
        val json = Json { ignoreUnknownKeys = true }
    }
}
