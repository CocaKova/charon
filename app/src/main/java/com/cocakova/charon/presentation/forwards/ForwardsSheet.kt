package com.cocakova.charon.presentation.forwards

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.cocakova.charon.data.db.PortForwardEntity
import com.cocakova.charon.theme.BoneWhite
import com.cocakova.charon.theme.MistGrey
import com.cocakova.charon.theme.ObolGold
import com.cocakova.charon.theme.StyxTeal
import com.cocakova.charon.theme.WarnEmber
import java.util.UUID

/**
 * Charted channels — the port-forwards sheet for one live crossing. Each row is a
 * channel: **L** phone→server, **R** server→phone, **D** Charon's own SOCKS5. Tap
 * the row to open/close it now (teal dot = open); the auto switch charts it with
 * every crossing of this host; long-press deletes. The Termius Premium feature,
 * free — server web UIs land in the phone browser via `127.0.0.1:port`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardsSheet(
    sessionLabel: String,
    hostId: String?,
    forwards: List<PortForwardEntity>,
    running: Set<String>,
    error: String?,
    onToggle: (PortForwardEntity) -> Unit,
    onSave: (PortForwardEntity) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<PortForwardEntity?>(null) }
    // The channel mid-toggle: its dot pulses gold until the running set answers
    // (haptic confirm), an error lands (haptic reject), or the attempt goes stale.
    var charting by remember { mutableStateOf<String?>(null) }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(running) {
        if (charting != null) {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            charting = null
        }
    }
    LaunchedEffect(error) {
        if (error != null && charting != null) {
            haptic.performHapticFeedback(HapticFeedbackType.Reject)
            charting = null
        }
    }
    LaunchedEffect(charting) {
        if (charting != null) {
            delay(8_000)
            charting = null
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .animateContentSize()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                "charted channels — $sessionLabel",
                style = MaterialTheme.typography.titleMedium,
                color = StyxTeal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (hostId == null) {
                Text(
                    "save this host to a mooring first — quick connects can't keep charts",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MistGrey,
                    modifier = Modifier.padding(top = 12.dp),
                )
                return@Column
            }
            if (error != null) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = WarnEmber,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(10.dp))

            if (forwards.isEmpty() && !adding) {
                Text(
                    "no channels charted for this host yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MistGrey,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            forwards.forEach { fwd ->
                ChannelRow(
                    fwd = fwd,
                    open = fwd.id in running,
                    charting = charting == fwd.id,
                    onToggle = { charting = fwd.id; onToggle(fwd) },
                    onAutoChange = { onSave(fwd.copy(autoStart = it, lastModified = System.currentTimeMillis())) },
                    onLongPress = { deleting = fwd },
                )
            }

            Spacer(Modifier.height(10.dp))
            AnimatedVisibility(
                visible = adding,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                AddChannelForm(
                    hostId = hostId,
                    onSave = { onSave(it); adding = false },
                    onCancel = { adding = false },
                )
            }
            AnimatedVisibility(visible = !adding, enter = fadeIn(), exit = fadeOut()) {
                TextButton(onClick = { adding = true }) {
                    Text("+ chart a channel", color = StyxTeal)
                }
            }
        }
    }

    deleting?.let { fwd ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("uncharted?", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    describe(fwd),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MistGrey,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (fwd.id in running) onToggle(fwd)
                    onDelete(fwd.id)
                    deleting = null
                }) { Text("remove", color = WarnEmber) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("keep", color = MistGrey) }
            },
        )
    }
}

@Composable
private fun ChannelRow(
    fwd: PortForwardEntity,
    open: Boolean,
    charting: Boolean,
    onToggle: () -> Unit,
    onAutoChange: (Boolean) -> Unit,
    onLongPress: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Type badge: L teal, R gold, D bone — the letter is the grammar.
        Text(
            fwd.type,
            style = MaterialTheme.typography.labelLarge,
            color = when (fwd.type) {
                "L" -> StyxTeal
                "R" -> ObolGold
                else -> BoneWhite
            },
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                describe(fwd),
                style = MaterialTheme.typography.bodyMedium,
                color = BoneWhite,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The dot eases between states; while a toggle is in flight it
                // pulses gold — the channel is being charted, not stuck.
                val dotColor by animateColorAsState(
                    when {
                        charting -> ObolGold
                        open -> StyxTeal
                        else -> MistGrey.copy(alpha = 0.4f)
                    },
                    tween(250),
                    label = "channel-dot",
                )
                val pulse by rememberInfiniteTransition(label = "charting")
                    .animateFloat(
                        0.35f, 1f,
                        infiniteRepeatable(tween(500), RepeatMode.Reverse),
                        label = "pulse",
                    )
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(dotColor.copy(alpha = if (charting) pulse else 1f)),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    when {
                        charting && open -> "closing the channel…"
                        charting -> "charting…"
                        open -> "open — tap to close"
                        else -> "tap to open"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (charting) ObolGold else MistGrey,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("auto", style = MaterialTheme.typography.bodySmall, color = MistGrey)
            Switch(
                checked = fwd.autoStart,
                onCheckedChange = onAutoChange,
                colors = SwitchDefaults.colors(checkedTrackColor = StyxTeal),
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            "✕",
            style = MaterialTheme.typography.bodyMedium,
            color = MistGrey,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onLongPress)
                .padding(8.dp),
        )
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun AddChannelForm(
    hostId: String,
    onSave: (PortForwardEntity) -> Unit,
    onCancel: () -> Unit,
) {
    var type by rememberSaveable { mutableStateOf("L") }
    var bindPort by rememberSaveable { mutableStateOf("") }
    var targetHost by rememberSaveable { mutableStateOf("localhost") }
    var targetPort by rememberSaveable { mutableStateOf("") }
    var autoStart by rememberSaveable { mutableStateOf(false) }

    Column {
        // Type selector: three pills.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "L" to "local", "R" to "remote", "D" to "socks",
            ).forEach { (t, label) ->
                val selected = type == t
                Text(
                    "$t · $label",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MistGrey,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (selected) StyxTeal
                            else MaterialTheme.colorScheme.surfaceVariant,
                        )
                        .clickable { type = t }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = bindPort,
                onValueChange = { bindPort = it.filter { c -> c.isDigit() }.take(5) },
                label = {
                    Text(
                        if (type == "R") "server port" else "phone port",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            if (type != "D") {
                OutlinedTextField(
                    value = targetHost,
                    onValueChange = { targetHost = it },
                    label = { Text("target host", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    modifier = Modifier.weight(1.4f),
                )
                OutlinedTextField(
                    value = targetPort,
                    onValueChange = { targetPort = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text("port", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    modifier = Modifier.weight(0.8f),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = autoStart,
                onCheckedChange = { autoStart = it },
                colors = SwitchDefaults.colors(checkedTrackColor = StyxTeal),
            )
            Spacer(Modifier.width(8.dp))
            Text("chart on every crossing", style = MaterialTheme.typography.bodyMedium, color = MistGrey)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("cancel", color = MistGrey) }
            Button(
                onClick = {
                    val bind = bindPort.toIntOrNull() ?: return@Button
                    val now = System.currentTimeMillis()
                    onSave(
                        PortForwardEntity(
                            id = UUID.randomUUID().toString(),
                            hostId = hostId,
                            type = type,
                            bindPort = bind,
                            targetHost = if (type == "D") "" else targetHost.trim(),
                            targetPort = if (type == "D") 0 else (targetPort.toIntOrNull() ?: return@Button),
                            autoStart = autoStart,
                            createdAt = now,
                            lastModified = now,
                        ),
                    )
                },
                enabled = bindPort.isNotBlank() &&
                    (type == "D" || (targetHost.isNotBlank() && targetPort.isNotBlank())),
            ) { Text("chart") }
        }
    }
}

private fun describe(fwd: PortForwardEntity): String = when (fwd.type) {
    "L" -> "127.0.0.1:${fwd.bindPort} → ${fwd.targetHost}:${fwd.targetPort}"
    "R" -> "server:${fwd.bindPort} → ${fwd.targetHost}:${fwd.targetPort}"
    else -> "socks5 on 127.0.0.1:${fwd.bindPort}"
}
