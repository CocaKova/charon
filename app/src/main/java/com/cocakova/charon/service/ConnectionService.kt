package com.cocakova.charon.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.cocakova.charon.CharonApp
import com.cocakova.charon.MainActivity
import com.cocakova.charon.R

/**
 * The process pin: a specialUse foreground service that exists so live SSH sessions
 * survive backgrounding (template: Keryx BuiltinPushService). SessionManager owns the
 * sessions; this service owns only the notification and the foreground state.
 *
 * v0.1 uses START_NOT_STICKY: a process death kills the session anyway, and a zombie
 * service with nothing to show for it helps no one. Auto-reconnect lives in
 * SessionManager (network-callback redial), not a sticky service.
 */
class ConnectionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                (applicationContext as CharonApp).sessionManager.closeAll()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        val count = intent?.getIntExtra(EXTRA_COUNT, 1) ?: 1
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "session"
        ensureChannel()
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(count, text), type)
        return START_NOT_STICKY
    }

    private fun buildNotification(count: Int, text: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnectIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ConnectionService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (count == 1) "Charon — crossing active"
        else "Charon — $count crossings active"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_charon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(tapIntent)
            .addAction(0, if (count == 1) "Disconnect" else "Disconnect all", disconnectIntent)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Active session", NotificationManager.IMPORTANCE_MIN)
                .apply { description = "Keeps SSH sessions alive in the background" },
        )
    }

    companion object {
        private const val CHANNEL_ID = "charon_session"
        private const val NOTIF_ID = 1
        private const val ACTION_DISCONNECT = "com.cocakova.charon.DISCONNECT"
        private const val EXTRA_COUNT = "count"
        private const val EXTRA_TEXT = "text"

        fun start(context: Context, count: Int, text: String) {
            val intent = Intent(context, ConnectionService::class.java)
                .putExtra(EXTRA_COUNT, count)
                .putExtra(EXTRA_TEXT, text)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }
    }
}
