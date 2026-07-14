package com.cocakova.charon.presentation.dock

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import com.cocakova.charon.data.db.IdentityEntity
import com.cocakova.charon.data.db.HostEntity
import com.cocakova.charon.data.repository.HostDraft
import com.cocakova.charon.presentation.components.StyxCrossing
import com.cocakova.charon.presentation.keys.KeysSheet
import com.cocakova.charon.theme.DeepTeal
import kotlinx.coroutines.delay
import com.cocakova.charon.theme.MistGrey
import com.cocakova.charon.theme.StyxTeal

/**
 * The Dock: the landing surface. Saved crossings as moorings over the sea;
 * one tap casts off. The v0.1 quick-connect form lives on inside the edit
 * sheet ("new crossing"), demoted from landing page to one card among the fleet.
 */
@Composable
fun DockScreen(
    hosts: List<HostEntity>,
    identities: List<IdentityEntity>,
    connecting: Boolean,
    arrivals: Int,
    error: String?,
    onConnect: (HostEntity) -> Unit,
    onQuickConnect: (HostDraft) -> Unit,
    onSave: (HostDraft) -> Unit,
    onDelete: (String) -> Unit,
    onForgeKey: suspend (String, Boolean) -> Unit,
    onImportKey: suspend (String, String, String?, Boolean) -> Unit,
    onReleaseKey: suspend (String) -> Unit,
    onGrantKey: suspend (HostEntity, IdentityEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<EditTarget?>(null) }

    var showKeys by remember { mutableStateOf(false) }
    // Right after a session ends, the ferry is still coming back in — say so.
    var ferryReturning by remember { mutableStateOf(arrivals > 0) }
    LaunchedEffect(arrivals) {
        if (arrivals > 0) {
            ferryReturning = true
            delay(3600)
            ferryReturning = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(28.dp))
        Box(Modifier.fillMaxWidth()) {
            Text(
                "CHARON",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.Center),
            )
            IconButton(
                onClick = { showKeys = true },
                enabled = !connecting,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
            ) {
                Icon(Icons.Outlined.Key, contentDescription = "keys of passage",
                    tint = MaterialTheme.colorScheme.primary)
            }
        }
        Text(
            when {
                connecting -> "crossing the river…"
                ferryReturning -> "the ferry returns to shore"
                else -> "the dock"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(12.dp))
        StyxCrossing(
            connecting = connecting,
            arrivals = arrivals,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        Spacer(Modifier.height(12.dp))

        if (error != null) {
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(8.dp))
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp, end = 20.dp, bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(hosts, key = { it.id }) { host ->
                MooringCard(
                    host = host,
                    enabled = !connecting,
                    onCross = { onConnect(host) },
                    onEdit = { editing = EditTarget.Existing(host) },
                )
            }
            item(key = "new-crossing") {
                NewCrossingCard(
                    enabled = !connecting,
                    firstMooring = hosts.isEmpty(),
                    onClick = { editing = EditTarget.New },
                )
            }
        }
    }

    editing?.let { target ->
        HostEditSheet(
            target = target,
            identities = identities,
            onDismiss = { editing = null },
            onCross = { draft ->
                editing = null
                onQuickConnect(draft)
            },
            onSaveAndCross = { draft ->
                editing = null
                onSave(draft)
                onQuickConnect(draft)
            },
            onDelete = { id ->
                editing = null
                onDelete(id)
            },
        )
    }

    if (showKeys) {
        KeysSheet(
            identities = identities,
            hosts = hosts,
            onDismiss = { showKeys = false },
            onForge = onForgeKey,
            onImport = onImportKey,
            onRelease = onReleaseKey,
            onGrant = onGrantKey,
        )
}
}

sealed class EditTarget {
    data object New : EditTarget()
    data class Existing(val host: HostEntity) : EditTarget()
}

@Composable
private fun MooringCard(
    host: HostEntity,
    enabled: Boolean,
    onCross: () -> Unit,
    onEdit: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onCross)
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Mooring lantern. Reachability dots (live TCP dial) arrive with the fleet
        // milestone — until then it's the dimmed glow of a known shore.
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(DeepTeal),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                host.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                buildString {
                    append("${host.username}@${host.host}")
                    if (host.port != 22) append(":${host.port}")
                    if (host.lastConnectedAt > 0L) {
                        append("  ·  ")
                        append(
                            DateUtils.getRelativeTimeSpanString(
                                host.lastConnectedAt,
                                System.currentTimeMillis(),
                                DateUtils.MINUTE_IN_MILLIS,
                            ),
                        )
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MistGrey,
            )
        }
        IconButton(onClick = onEdit, enabled = enabled) {
            Icon(
                Icons.Outlined.Edit,
                contentDescription = "edit",
                tint = MistGrey,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun NewCrossingCard(
    enabled: Boolean,
    firstMooring: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, DeepTeal, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "+ new crossing",
            style = MaterialTheme.typography.bodyLarge,
            color = StyxTeal,
        )
        if (firstMooring) {
            Text(
                "no moorings yet — the ferryman awaits",
                style = MaterialTheme.typography.bodySmall,
                color = MistGrey,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
