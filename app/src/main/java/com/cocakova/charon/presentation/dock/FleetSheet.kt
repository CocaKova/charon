package com.cocakova.charon.presentation.dock

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cocakova.charon.data.db.HostEntity
import com.cocakova.charon.data.db.IdentityEntity
import com.cocakova.charon.data.repository.HostDraft
import com.cocakova.charon.fleet.FleetCandidate
import com.cocakova.charon.fleet.LanSweep
import com.cocakova.charon.fleet.TailscaleImport
import com.cocakova.charon.presentation.components.ChoicePill
import com.cocakova.charon.presentation.components.DropdownChoice
import com.cocakova.charon.presentation.components.ReadonlyDropdownField
import com.cocakova.charon.theme.DeepTeal
import com.cocakova.charon.theme.MistGrey
import com.cocakova.charon.theme.ObolGold
import com.cocakova.charon.theme.StyxTeal
import com.cocakova.charon.theme.WarnEmber
import kotlinx.coroutines.launch

/**
 * Charting the waters: the fleet import sheet. Two ways to find ships — ask a
 * mooring already on the tailnet for `tailscale status --json` (or paste one),
 * or sweep the phone's own /24 for anything answering on :22. Either way the
 * sightings land in one picker: choose, give them a shared username and an
 * optional harbor, and moor the lot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FleetSheet(
    hosts: List<HostEntity>,
    identities: List<IdentityEntity>,
    onDismiss: () -> Unit,
    onFetchTailnet: suspend (HostEntity) -> Result<String>,
    onAddMoorings: (List<HostDraft>) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var tailnetMode by remember { mutableStateOf(true) }

    // The sightings, however they arrived, and which of them are chosen.
    val sightings = remember { mutableStateListOf<FleetCandidate>() }
    var chosen by remember { mutableStateOf(setOf<String>()) }
    var username by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var identityId by remember { mutableStateOf<String?>(null) }
    var harbor by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val existingHosts = remember(hosts) { hosts.mapTo(HashSet()) { it.host } }

    fun land(candidates: List<FleetCandidate>) {
        sightings.clear(); sightings.addAll(candidates)
        chosen = candidates.filterNot { it.host in existingHosts }.mapTo(LinkedHashSet()) { it.host }
        error = null
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(
                "chart the waters",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Clearing busy on a mode switch keeps a cancelled finder's coroutine
                // (its scope dies with the tab) from latching the ask/moor buttons off.
                ChoicePill("the tailnet", tailnetMode) {
                    tailnetMode = true; sightings.clear(); chosen = emptySet(); error = null; busy = false
                }
                ChoicePill("near waters", !tailnetMode) {
                    tailnetMode = false; sightings.clear(); chosen = emptySet(); error = null; busy = false
                }
            }
            Spacer(Modifier.height(14.dp))

            if (tailnetMode) {
                TailnetFinder(
                    hosts = hosts,
                    busy = busy,
                    onBusy = { busy = it },
                    onError = { error = it },
                    onFetch = onFetchTailnet,
                    onSightings = { candidates, sourceUser ->
                        land(candidates)
                        if (username.isBlank()) username = sourceUser
                    },
                )
            } else {
                NearWatersFinder(
                    busy = busy,
                    onBusy = { busy = it },
                    onError = { error = it },
                    onSightings = { land(it) },
                )
            }

            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = WarnEmber)
            }

            if (sightings.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    "${sightings.size} ships sighted",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))
                Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    sightings.forEach { ship ->
                        val moored = ship.host in existingHosts
                        SightingRow(
                            ship = ship,
                            moored = moored,
                            chosen = ship.host in chosen,
                            onToggle = {
                                chosen = if (ship.host in chosen) chosen - ship.host
                                else chosen + ship.host
                            },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                // These moorings share one crossing — a username, a port, and
                // optionally a key of passage. Without a key each is address-only and
                // will ask for a password (or a key) before its first crossing.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        singleLine = true,
                        label = { Text("username") },
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { new -> port = new.filter { it.isDigit() }.take(5) },
                        singleLine = true,
                        label = { Text("port") },
                        modifier = Modifier.width(96.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                val selectedIdentity = identities.firstOrNull { it.id == identityId }
                ReadonlyDropdownField(
                    value = selectedIdentity?.name ?: "no key — set per mooring later",
                    label = "key of passage (optional)",
                    enabled = identities.isNotEmpty(),
                    choices = listOf(
                        DropdownChoice("no key — set per mooring later", dim = true) {
                            identityId = null
                        },
                    ) + identities.map { identity ->
                        DropdownChoice(identity.name) { identityId = identity.id }
                    },
                )
                if (identityId == null) {
                    Text(
                        "no key attached — you'll add a password or key before first crossing",
                        style = MaterialTheme.typography.bodySmall,
                        color = MistGrey,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = harbor,
                    onValueChange = { harbor = it },
                    singleLine = true,
                    label = { Text("harbor (optional)") },
                    placeholder = { Text(if (tailnetMode) "e.g. tailnet" else "e.g. home port", color = MistGrey) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                val count = chosen.size
                Button(
                    onClick = {
                        val bindPort = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: 22
                        val drafts = sightings.filter { it.host in chosen }.map { ship ->
                            HostDraft(
                                id = null,
                                name = ship.name,
                                host = ship.host,
                                port = bindPort,
                                username = username.trim(),
                                password = "",
                                identityId = identityId,
                                harbor = harbor.trim(),
                            )
                        }
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        onAddMoorings(drafts)
                        onDismiss()
                    },
                    enabled = count > 0 && username.isNotBlank() && !busy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StyxTeal,
                        contentColor = Color.Black,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (count == 1) "moor 1 ship" else "moor $count ships")
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** The tailnet leg: pick a mooring to ask, or paste a status by hand. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TailnetFinder(
    hosts: List<HostEntity>,
    busy: Boolean,
    onBusy: (Boolean) -> Unit,
    onError: (String?) -> Unit,
    onFetch: suspend (HostEntity) -> Result<String>,
    onSightings: (List<FleetCandidate>, sourceUser: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf(hosts.firstOrNull()) }
    var pasting by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }

    Text(
        "ask a mooring already on the tailnet — it runs `tailscale status` and reports the fleet",
        style = MaterialTheme.typography.bodySmall,
        color = MistGrey,
    )
    Spacer(Modifier.height(8.dp))
    if (hosts.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ReadonlyDropdownField(
                value = source?.displayName ?: "",
                label = "who to ask",
                choices = hosts.map { h -> DropdownChoice(h.displayName) { source = h } },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            TextButton(
                onClick = {
                    val src = source ?: return@TextButton
                    scope.launch {
                        onBusy(true); onError(null)
                        onFetch(src)
                            .mapCatching { TailscaleImport.parse(it) }
                            .onSuccess { onSightings(it, src.username) }
                            .onFailure { onError(it.message ?: "the errand failed") }
                        onBusy(false)
                    }
                },
                enabled = !busy && source != null,
            ) {
                Text(if (busy) "asking…" else "ask", color = StyxTeal)
            }
        }
    } else {
        Text(
            "no moorings yet to ask — paste a status instead",
            style = MaterialTheme.typography.bodySmall,
            color = MistGrey,
        )
    }
    Spacer(Modifier.height(4.dp))
    TextButton(onClick = { pasting = !pasting }) {
        Text(
            if (pasting) "hide the paste chart" else "or paste `tailscale status --json`",
            color = ObolGold,
            style = MaterialTheme.typography.labelMedium,
        )
    }
    AnimatedVisibility(pasting) {
        Column {
            OutlinedTextField(
                value = pasted,
                onValueChange = { pasted = it },
                label = { Text("the status, verbatim") },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            TextButton(
                onClick = {
                    runCatching { TailscaleImport.parse(pasted) }
                        .onSuccess { onSightings(it, ""); onError(null) }
                        .onFailure { onError(it.message) }
                },
                enabled = pasted.isNotBlank(),
            ) { Text("read it", color = StyxTeal) }
        }
    }
}

/** The near-waters leg: sweep the phone's /24 for ships answering on :22. */
@Composable
private fun NearWatersFinder(
    busy: Boolean,
    onBusy: (Boolean) -> Unit,
    onError: (String?) -> Unit,
    onSightings: (List<FleetCandidate>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val waters = remember { LanSweep.nearWaters() }
    // The sweep's own size — never a hardcoded 253, which lies the moment
    // LanSweep narrows its candidate range.
    val berths = remember(waters) { waters?.let { LanSweep.candidates(it.ownIp).size } ?: 0 }
    var dialed by remember { mutableIntStateOf(0) }
    var found by remember { mutableIntStateOf(0) }
    var swept by remember { mutableStateOf(false) }

    if (waters == null) {
        Text(
            "no near waters — the phone isn't on a local network",
            style = MaterialTheme.typography.bodySmall,
            color = MistGrey,
        )
        return
    }
    Text(
        "sound ${waters.subnetLabel} for ships answering on :22",
        style = MaterialTheme.typography.bodySmall,
        color = MistGrey,
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = {
                scope.launch {
                    onBusy(true); onError(null); swept = false
                    dialed = 0; found = 0
                    val finds = LanSweep.sweep(
                        waters.ownIp,
                        onProgress = { d, f -> dialed = d; found = f },
                    )
                    onSightings(
                        finds.map {
                            FleetCandidate(
                                name = it.hostname?.substringBefore('.') ?: it.ip,
                                host = it.ip,
                                online = true,
                                os = "",
                            )
                        },
                    )
                    swept = true
                    if (finds.isEmpty()) onError("nothing answered in the near waters")
                    onBusy(false)
                }
            },
            enabled = !busy,
        ) {
            Text(if (busy) "sounding…" else "sound the harbor", color = StyxTeal)
        }
        if (busy || swept) {
            Spacer(Modifier.width(8.dp))
            Text(
                "$dialed/$berths · $found answer",
                style = MaterialTheme.typography.labelMedium,
                color = if (found > 0) StyxTeal else MistGrey,
            )
        }
    }
    if (busy) {
        LinearProgressIndicator(
            progress = { if (berths > 0) dialed / berths.toFloat() else 0f },
            color = StyxTeal,
            trackColor = DeepTeal,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

/**
 * One sighted ship: tap to choose it for mooring. An already-moored address sits
 * out by default (an anchor, deselected) but stays tappable — you can still moor
 * the same host under a different username or port.
 */
@Composable
private fun SightingRow(
    ship: FleetCandidate,
    moored: Boolean,
    chosen: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when {
                chosen -> "☑"
                moored -> "⚓"
                else -> "☐"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = if (chosen) StyxTeal else MistGrey,
        )
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (ship.online) StyxTeal else MistGrey.copy(alpha = 0.4f)),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                ship.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (moored) MistGrey else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                buildString {
                    append(ship.host)
                    if (ship.os.isNotBlank()) append("  ·  ${ship.os}")
                    if (moored) append("  ·  already moored")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MistGrey,
            )
        }
    }
}
