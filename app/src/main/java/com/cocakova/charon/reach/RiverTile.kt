package com.cocakova.charon.reach

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.cocakova.charon.CharonApp
import com.cocakova.charon.MainActivity

/**
 * The quick-settings tile: how many crossings are under way, and one tap to the
 * river — aboard the crossing last stood on, or the Dock when the water is still.
 * Read when the shade opens, never polled.
 */
class RiverTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val live = (application as CharonApp).sessionManager.sessions.value.size
        tile.state = if (live > 0) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Charon"
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when (live) {
                0 -> "the river is still"
                1 -> "1 crossing"
                else -> "$live crossings"
            }
        }
        tile.contentDescription = "Charon, $live crossings under way"
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val manager = (application as CharonApp).sessionManager
        val sessionId = manager.activeSession.value?.id ?: manager.sessions.value.lastOrNull()?.id
        val intent = if (sessionId != null) {
            Reach.boardIntent(this, sessionId)
        } else {
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
