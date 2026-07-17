package com.cocakova.charon.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.cocakova.charon.MainActivity
import com.cocakova.charon.R
import java.util.concurrent.atomic.AtomicInteger

/**
 * The horn: a push (with its buzz) when a long-running command finishes while
 * you're away from the app — the thing no desktop terminal can do. Fed by OSC 133
 * marks from a rigged shell (docs/HORN.md); gated three ways so it never becomes
 * a buzzer: the voyage must have been long enough to have wandered off from, the
 * app must actually be away, and the helm can silence it outright.
 */
class Horn(private val context: Context) {

    fun sound(sessionLabel: String, command: String, exitCode: Int?, durationMs: Long) {
        if (durationMs < MIN_VOYAGE_MS) return
        if (AppVisibility.visible) return
        val prefs = context.getSharedPreferences("charon", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("horn", true)) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        ensureChannel()
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val aground = exitCode != null && exitCode != 0
        val text = buildString {
            append(command.take(80))
            append("  ·  ")
            append(voyageLength(durationMs))
            if (aground) append("  ·  ran aground (exit $exitCode)")
            append("  ·  ")
            append(sessionLabel)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_charon)
            .setContentTitle(if (aground) "the horn sounds — ran aground" else "the horn sounds — come ashore")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(nextId.incrementAndGet(), notification)
        } catch (e: SecurityException) {
            // Permission revoked between the check and the post — the horn stays quiet.
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "the horn",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "a command you were waiting on has finished"
            },
        )
    }

    private fun voyageLength(ms: Long): String {
        val s = ms / 1000
        return when {
            s >= 3600 -> "${s / 3600}h ${(s % 3600) / 60}m"
            s >= 60 -> "${s / 60}m ${s % 60}s"
            else -> "${s}s"
        }
    }

    companion object {
        private const val CHANNEL_ID = "charon-horn"

        /** Shorter voyages don't earn a horn — you never left the rail. */
        const val MIN_VOYAGE_MS = 15_000L

        private val nextId = AtomicInteger(100)
    }
}
