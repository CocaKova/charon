package com.cocakova.charon.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import com.cocakova.charon.reach.Reach
import java.util.concurrent.ConcurrentHashMap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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

    private val enabled: Boolean
        get() = context.getSharedPreferences("charon", Context.MODE_PRIVATE).getBoolean("horn", true)

    /**
     * A command came home. Away from the app: a push whose tap lands on that very
     * command ([sessionId] + [markId]), buzzing two soft ticks for ashore and one
     * long low note for aground. In the app but on another tab: no push, only the
     * feel of it (the tab's dot flashes on its own).
     */
    fun sound(
        sessionLabel: String,
        command: String,
        exitCode: Int?,
        durationMs: Long,
        sessionId: String,
        markId: Long,
        onAnotherTab: Boolean,
    ) {
        if (durationMs < MIN_VOYAGE_MS) return
        if (!enabled) return
        val aground = exitCode != null && exitCode != 0
        if (AppVisibility.visible) {
            if (onAnotherTab) feel(aground)
            return
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        ensureChannel()
        val id = nextId.incrementAndGet()
        val open = PendingIntent.getActivity(
            context, id,
            Reach.boardIntent(context, sessionId, markId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val channel = if (aground) CHANNEL_AGROUND else CHANNEL_ASHORE
        val text = buildString {
            append(command.take(80))
            append("  ·  ")
            append(voyageLength(durationMs))
            if (aground) append("  ·  ran aground (exit $exitCode)")
            append("  ·  ")
            append(sessionLabel)
        }
        val title = if (aground) "the horn sounds — ran aground" else "the horn sounds — come ashore"
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_charon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(open)
            // A command line can carry a secret in plain sight (`mysql -pSECRET`,
            // a token in an env assignment). The toll keeps those off the glass
            // while they're typed; the horn must not post them to a locked one.
            // Where the traveller has asked for sensitive content to be hidden,
            // the lock screen gets the call without the command.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, channel)
                    .setSmallIcon(R.drawable.ic_charon)
                    .setContentTitle(title)
                    .setContentText(sessionLabel)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build(),
            )
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // Permission revoked between the check and the post — the horn stays quiet.
        }
    }

    private val lastCall = ConcurrentHashMap<String, Long>()

    /**
     * A program on the far side asked to be heard (OSC 9 / OSC 777: a build's "done",
     * a long test run's verdict). Only while the app is away, behind the horn's own
     * switch, and never more than one every [CALL_GAP_MS] from a crossing: the far
     * side writes these, so it must not be able to keep the phone buzzing.
     */
    fun call(sessionLabel: String, title: String?, body: String, sessionId: String) {
        if (!enabled || AppVisibility.visible) return
        val now = SystemClock.elapsedRealtime()
        val last = lastCall[sessionId]
        if (last != null && now - last < CALL_GAP_MS) return
        lastCall[sessionId] = now
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        ensureChannel()
        val id = nextId.incrementAndGet()
        val open = PendingIntent.getActivity(
            context, id, Reach.boardIntent(context, sessionId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val heading = title?.takeIf { it.isNotBlank() } ?: "a call from the far shore"
        val text = body.ifBlank { sessionLabel }
        val notification = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_charon)
            .setContentTitle(heading)
            .setContentText(text)
            .setSubText(sessionLabel)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_CALLS)
                    .setSmallIcon(R.drawable.ic_charon)
                    .setContentTitle("a call from the far shore")
                    .setContentText(sessionLabel)
                    .setContentIntent(open)
                    .build(),
            )
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
        }
    }

    /** The horn felt in the hand: two soft ticks for ashore, one long low buzz for aground. */
    private fun feel(aground: Boolean) {
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        if (!vibrator.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(
                    if (aground) VibrationEffect.createWaveform(BUZZ_TIMINGS, BUZZ_AMPLITUDES, -1)
                    else VibrationEffect.createWaveform(TICK_TIMINGS, TICK_AMPLITUDES, -1),
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(if (aground) BUZZ_TIMINGS else TICK_TIMINGS, -1)
            }
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        // 1.1's single horn channel couldn't buzz two ways; its two heirs can.
        manager.deleteNotificationChannel(LEGACY_CHANNEL)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ASHORE, "the horn — ashore", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "a command you were waiting on came home"
                enableVibration(true)
                vibrationPattern = TICK_TIMINGS
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_AGROUND, "the horn — aground", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "a command you were waiting on failed"
                enableVibration(true)
                vibrationPattern = BUZZ_TIMINGS
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CALLS, "calls from the far shore", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "a program on a crossing asked to be heard (OSC 9 / 777)"
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
        private const val LEGACY_CHANNEL = "charon-horn"
        private const val CHANNEL_ASHORE = "charon-horn-ashore"
        private const val CHANNEL_AGROUND = "charon-horn-aground"
        private const val CHANNEL_CALLS = "charon-calls"

        /** One far-side call per crossing per this long, at most. */
        const val CALL_GAP_MS = 10_000L

        private val TICK_TIMINGS = longArrayOf(0, 28, 110, 28)
        private val TICK_AMPLITUDES = intArrayOf(0, 90, 0, 90)
        private val BUZZ_TIMINGS = longArrayOf(0, 420)
        private val BUZZ_AMPLITUDES = intArrayOf(0, 70)

        /** Shorter voyages don't earn a horn — you never left the rail. */
        const val MIN_VOYAGE_MS = 15_000L

        private val nextId = AtomicInteger(100)
    }
}
