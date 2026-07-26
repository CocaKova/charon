package com.cocakova.charon.presentation.dock

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.cocakova.charon.data.db.HostEntity
import com.cocakova.charon.fleet.Reach
import com.cocakova.charon.fleet.Sounding
import com.cocakova.charon.theme.Styx

/**
 * The quick actions a long-pressed mooring offers — one tap from the Dock to the
 * common errands, without opening the full edit sheet. What's on offer bends to
 * the moment: a live crossing adds "step aboard" and "the hold"; releasing the
 * mooring takes a second tap, never a dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickActionsSheet(
    host: HostEntity,
    sounding: Sounding?,
    liveSessionId: String?,
    onDismiss: () -> Unit,
    onCross: () -> Unit,
    onStepAboard: (String) -> Unit,
    onOpenHold: (String) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    var armedRelease by remember { mutableStateOf(false) }

    // Most actions leave the sheet behind before they act.
    fun dismissThen(action: () -> Unit): () -> Unit = { onDismiss(); action() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(lanternColor(host.colorHex)),
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        host.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        host.address,
                        style = MaterialTheme.typography.bodySmall,
                        color = Styx.mist,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            // The last sounding, spoken plainly.
            val (soundingText, soundingColor) = when (sounding?.reach) {
                Reach.REACHABLE ->
                    "answers in ${sounding.latencyMs} ms" to Styx.water
                Reach.UNREACHABLE -> "dark water — no answer" to Styx.ember
                null -> "unsounded" to Styx.mist
            }
            Text(soundingText, style = MaterialTheme.typography.bodySmall, color = soundingColor)
            Spacer(Modifier.height(14.dp))

            QuickAction("cross", "board the ferry to this mooring", Styx.water, dismissThen(onCross))
            if (liveSessionId != null) {
                QuickAction(
                    "step aboard", "a crossing is already underway", Styx.water,
                    dismissThen { onStepAboard(liveSessionId) },
                )
                QuickAction(
                    "the hold", "browse this ship's files", Styx.coin,
                    dismissThen { onOpenHold(liveSessionId) },
                )
            }
            QuickAction("copy address", host.address, MaterialTheme.colorScheme.onSurface) {
                clipboard.setText(AnnotatedString(host.address))
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onDismiss()
            }
            QuickAction(
                "edit the mooring", "name, harbor, lantern, keys…",
                MaterialTheme.colorScheme.onSurface, dismissThen(onEdit),
            )
            QuickAction(
                if (armedRelease) "tap again to release" else "release the mooring",
                if (armedRelease) "this forgets the saved crossing" else "remove from the Dock",
                Styx.ember,
            ) {
                if (!armedRelease) {
                    armedRelease = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                } else {
                    haptic.performHapticFeedback(HapticFeedbackType.Reject)
                    onDismiss(); onDelete()
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun QuickAction(
    label: String,
    detail: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
        Spacer(Modifier.width(12.dp))
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = Styx.mist,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
    }
}
