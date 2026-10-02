package com.cocakova.charon.reach

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.cocakova.charon.MainActivity
import com.cocakova.charon.R
import com.cocakova.charon.data.db.HostEntity
import com.cocakova.charon.fleet.Hail
import com.cocakova.charon.fleet.HailTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The ways in from outside the Dock: an `ssh://` link from anywhere on the phone, a
 * launcher shortcut to a mooring, the quick-settings tile, a horn tapped in the shade.
 * Each lands as one ask the root reads once and clears. A link never casts off on
 * its own: it opens the hail sheet filled in, and the traveller says go.
 */
object Reach {

    sealed interface Ask {
        /** An ssh:// link: the hail sheet, filled in, waiting for the traveller. */
        data class HailFor(val target: HailTarget) : Ask

        /** A launcher shortcut: cross to this mooring (or step aboard its live crossing). */
        data class Moor(val hostId: String) : Ask

        /** A horn or the tile: step aboard this crossing, at this mark if it has one. */
        data class Board(val sessionId: String, val markId: Long = -1L) : Ask
    }

    const val ACTION_MOOR = "com.cocakova.charon.action.MOOR"
    const val ACTION_BOARD = "com.cocakova.charon.action.BOARD"
    const val EXTRA_HOST_ID = "host_id"
    const val EXTRA_SESSION_ID = "session_id"
    const val EXTRA_MARK_ID = "mark_id"

    private val _pending = MutableStateFlow<Ask?>(null)
    val pending: StateFlow<Ask?> = _pending

    /** Read an intent the activity was started (or re-started) with. */
    fun receive(intent: Intent?) {
        intent ?: return
        val ask = askOf(
            action = intent.action,
            data = intent.dataString,
            hostId = intent.getStringExtra(EXTRA_HOST_ID),
            sessionId = intent.getStringExtra(EXTRA_SESSION_ID),
            markId = intent.getLongExtra(EXTRA_MARK_ID, -1L),
        ) ?: return
        _pending.value = ask
    }

    /** The root took it: clear, but only if nothing newer arrived meanwhile. */
    fun taken(ask: Ask) {
        _pending.compareAndSet(ask, null)
    }

    /** Pure: an intent's parts → what it asks for. Tested without a device. */
    fun askOf(action: String?, data: String?, hostId: String?, sessionId: String?, markId: Long): Ask? = when {
        action == ACTION_MOOR && !hostId.isNullOrBlank() -> Ask.Moor(hostId)
        action == ACTION_BOARD && !sessionId.isNullOrBlank() -> Ask.Board(sessionId, markId)
        action == Intent.ACTION_VIEW && data != null && data.startsWith("ssh://", ignoreCase = true) ->
            Hail.parse(data)?.let { Ask.HailFor(it) }
        else -> null
    }

    fun boardIntent(context: Context, sessionId: String, markId: Long = -1L): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(ACTION_BOARD)
            .putExtra(EXTRA_SESSION_ID, sessionId)
            .putExtra(EXTRA_MARK_ID, markId)
            // A distinct data URI keeps two horns' PendingIntents from collapsing into one.
            .setData(Uri.parse("charon://board/$sessionId/$markId"))
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    /** The moorings worth a long-press on the launcher icon: the latest crossed first. */
    fun shortcutHosts(hosts: List<HostEntity>, max: Int): List<HostEntity> =
        hosts.sortedWith(compareByDescending<HostEntity> { it.lastConnectedAt }.thenBy { it.displayName.lowercase() })
            .take(max)

    /** Keep the launcher's long-press list in step with the moorings. */
    fun publishShortcuts(context: Context, hosts: List<HostEntity>) {
        runCatching {
            val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceIn(1, 4)
            val icon = IconCompat.createWithResource(context, R.drawable.ic_shortcut_moor)
            val shortcuts = shortcutHosts(hosts, max).map { h ->
                ShortcutInfoCompat.Builder(context, "moor-${h.id}")
                    .setShortLabel(h.displayName.take(24))
                    .setLongLabel("cross to ${h.displayName}".take(48))
                    .setIcon(icon)
                    .setIntent(
                        Intent(context, MainActivity::class.java)
                            .setAction(ACTION_MOOR)
                            .putExtra(EXTRA_HOST_ID, h.id),
                    )
                    .build()
            }
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        }
    }
}
