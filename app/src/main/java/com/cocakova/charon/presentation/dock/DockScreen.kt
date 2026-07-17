package com.cocakova.charon.presentation.dock

import android.text.format.DateUtils
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.cocakova.charon.data.db.IdentityEntity
import com.cocakova.charon.data.db.HostEntity
import com.cocakova.charon.data.repository.HostDraft
import com.cocakova.charon.fleet.Reach
import com.cocakova.charon.fleet.Sounding
import com.cocakova.charon.theme.WarnEmber
import com.cocakova.charon.presentation.components.StyxCrossing
import com.cocakova.charon.presentation.keys.KeysSheet
import com.cocakova.charon.ssh.TerminalSession
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
    runningSessions: List<TerminalSession>,
    onResumeSession: (String) -> Unit,
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
    soundings: Map<String, Sounding>,
    onSoundFleet: suspend (List<HostEntity>) -> Unit,
    liveSessionFor: (HostEntity) -> String?,
    onOpenHold: (String) -> Unit,
    onFetchTailnet: suspend (HostEntity) -> Result<String>,
    onAddMoorings: (List<HostDraft>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<EditTarget?>(null) }
    val haptic = LocalHapticFeedback.current

    var showKeys by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showFleet by remember { mutableStateOf(false) }
    var quickActions by remember { mutableStateOf<HostEntity?>(null) }

    // The soundings loop: dial the whole fleet while the Dock is on screen AND the
    // app is in the foreground (repeatOnLifecycle stops the dialing when the phone
    // is pocketed — a composition-scoped effect alone would keep firing in the
    // background). Keyed on only the dial-relevant fields so a rename or lantern
    // change doesn't restart the loop and re-storm the fleet; a genuine
    // add/remove/re-address does.
    val lifecycleOwner = LocalLifecycleOwner.current
    val fleetKey = remember(hosts) { hosts.map { Triple(it.id, it.host, it.port) } }
    LaunchedEffect(fleetKey, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                if (hosts.isNotEmpty()) onSoundFleet(hosts)
                delay(25_000)
            }
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    // Harbors the user has folded shut. Ephemeral by design — a fresh launch shows
    // the whole fleet.
    val collapsed = remember { mutableStateListOf<String>() }
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
        if (runningSessions.isNotEmpty()) {
            RunningSessionsBar(
                sessions = runningSessions,
                onResume = onResumeSession,
            )
        }
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
            // The helm: settings, mirroring the keys on the far side.
            IconButton(
                onClick = { showSettings = true },
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
            ) {
                Icon(Icons.Outlined.Settings, contentDescription = "the helm",
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

        // Search only earns its keep once the fleet grows — below that it's clutter.
        if (hosts.size > 4) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("search the fleet", color = MistGrey) },
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null, tint = MistGrey)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(10.dp))
        }

        val q = query.trim()
        val filtered =
            if (q.isBlank()) hosts
            else hosts.filter { it.matches(q) }
        // Sections: named harbors alphabetically, the unsorted mooring last. While a
        // search is running we drop headers entirely and show one flat result list.
        val sections = remember(filtered, q) { groupIntoHarbors(filtered, grouped = q.isBlank()) }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp, end = 20.dp, bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            sections.forEach { section ->
                if (section.name != null) {
                    item(key = "harbor:${section.name}") {
                        HarborHeader(
                            name = section.name,
                            count = section.hosts.size,
                            collapsed = section.name in collapsed,
                            onToggle = {
                                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                if (section.name in collapsed) collapsed.remove(section.name)
                                else collapsed.add(section.name)
                            },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
                if (section.name == null || section.name !in collapsed) {
                    items(section.hosts, key = { it.id }) { host ->
                        MooringCard(
                            host = host,
                            sounding = soundings[host.id],
                            enabled = !connecting,
                            onCross = { onConnect(host) },
                            onEdit = { editing = EditTarget.Existing(host) },
                            onHold = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                quickActions = host
                            },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
            if (filtered.isEmpty() && q.isNotBlank()) {
                item(key = "no-results") {
                    Text(
                        "no moorings match \"$q\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MistGrey,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
            item(key = "new-crossing") {
                NewCrossingCard(
                    enabled = !connecting,
                    firstMooring = hosts.isEmpty(),
                    onClick = { editing = EditTarget.New },
                    modifier = Modifier.animateItem(),
                )
            }
            item(key = "chart-waters") {
                ChartWatersCard(
                    enabled = !connecting,
                    onClick = { showFleet = true },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }

    editing?.let { target ->
        HostEditSheet(
            target = target,
            identities = identities,
            harbors = remember(hosts) {
                hosts.map { it.harbor.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
            },
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

    if (showSettings) {
        SettingsSheet(onDismiss = { showSettings = false })
    }

    if (showFleet) {
        FleetSheet(
            hosts = hosts,
            identities = identities,
            onDismiss = { showFleet = false },
            onFetchTailnet = onFetchTailnet,
            onAddMoorings = onAddMoorings,
        )
    }

    quickActions?.let { host ->
        QuickActionsSheet(
            host = host,
            sounding = soundings[host.id],
            liveSessionId = liveSessionFor(host),
            onDismiss = { quickActions = null },
            onCross = { onConnect(host) },
            onStepAboard = onResumeSession,
            onOpenHold = onOpenHold,
            onEdit = { editing = EditTarget.Existing(host) },
            onDelete = { onDelete(host.id) },
        )
    }
}

sealed class EditTarget {
    data object New : EditTarget()
    data class Existing(val host: HostEntity) : EditTarget()
}

/**
 * Lantern hues a mooring can fly — a small, curated set so the Dock stays a
 * coherent palette rather than a paintbox. null = the default dimmed glow.
 */
val LanternHues: List<String?> = listOf(
    null,
    "#3ECFB2", // styx teal
    "#D9A441", // obol gold
    "#E0563E", // ember
    "#6EA8FE", // sky
    "#B98CFF", // amethyst
    "#63D68B", // moss
    "#E58CC4", // orchid
)

/** Parse a #RRGGBB lantern hue, falling back to the dimmed default. */
fun lanternColor(hex: String?): androidx.compose.ui.graphics.Color =
    hex?.let {
        runCatching { androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(it)) }
            .getOrNull()
    } ?: DeepTeal

/** Does this mooring answer to a search query? Matches label, address, or harbor. */
private fun HostEntity.matches(q: String): Boolean =
    displayName.contains(q, ignoreCase = true) ||
        host.contains(q, ignoreCase = true) ||
        username.contains(q, ignoreCase = true) ||
        harbor.contains(q, ignoreCase = true)

/** One collapsible block of the Dock. [name] null = the headerless flat/unsorted run. */
private data class HarborSection(val name: String?, val hosts: List<HostEntity>)

/**
 * Fold hosts into harbor sections. When [grouped] is false (a search is live) the
 * whole list comes back as one nameless section. Otherwise: named harbors first,
 * sorted; the unsorted moorings gathered under a final "unsorted" header — but only
 * if named harbors exist at all, so a plain fleet stays a plain headerless list.
 */
private fun groupIntoHarbors(hosts: List<HostEntity>, grouped: Boolean): List<HarborSection> {
    if (!grouped) return listOf(HarborSection(null, hosts))
    val byHarbor = hosts.groupBy { it.harbor.trim() }
    val named = byHarbor.keys.filter { it.isNotEmpty() }.sortedBy { it.lowercase() }
    if (named.isEmpty()) return listOf(HarborSection(null, hosts))
    val sections = named.map { HarborSection(it, byHarbor.getValue(it)) }
    val unsorted = byHarbor[""].orEmpty()
    return if (unsorted.isEmpty()) sections
    else sections + HarborSection("unsorted", unsorted)
}

/**
 * The strip of crossings already underway, shown atop the Dock while you're picking
 * the next one — tap a chip to step back aboard. This is the "+"-from-terminal
 * return path made visible.
 */
@Composable
private fun RunningSessionsBar(
    sessions: List<TerminalSession>,
    onResume: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "underway",
            style = MaterialTheme.typography.labelSmall,
            color = MistGrey,
        )
        Spacer(Modifier.width(10.dp))
        // Live crossings breathe — a still dot reads as a dead one.
        val breath by rememberInfiniteTransition(label = "underway")
            .animateFloat(
                0.55f, 1f,
                infiniteRepeatable(tween(1400), RepeatMode.Reverse),
                label = "breath",
            )
        sessions.forEach { s ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onResume(s.id) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(StyxTeal.copy(alpha = breath)),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    s.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(8.dp))
        }
    }
}

/** A harbor's collapsible header: a caret, its name, and the count of moorings within. */
@Composable
private fun HarborHeader(
    name: String,
    count: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // One caret that turns, rather than two glyphs that swap.
    val spin by animateFloatAsState(if (collapsed) -90f else 0f, tween(180), label = "caret")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "▾",
            style = MaterialTheme.typography.bodyMedium,
            color = StyxTeal,
            modifier = Modifier.rotate(spin),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            name,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MistGrey,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MooringCard(
    host: HostEntity,
    sounding: Sounding?,
    enabled: Boolean,
    onCross: () -> Unit,
    onEdit: () -> Unit,
    onHold: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The card gives slightly under the thumb — a plank taking weight.
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val give by animateFloatAsState(if (pressed) 0.98f else 1f, tween(120), label = "give")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .scale(give)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .combinedClickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                onClick = onCross,
                onLongClick = onHold,
            )
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Mooring lantern: the host's colour tag stays full-strength so colour-coding
        // reads at a glance even for offline hosts — reachability rides on top of it,
        // never over it. Answering water = a teal-tinted halo around the flame; dark
        // water = a WarnEmber tick at its foot; unsounded = just the flame.
        val lantern = lanternColor(host.colorHex)
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            if (sounding?.reach == Reach.REACHABLE) {
                Box(
                    Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(lantern.copy(alpha = 0.28f)),
                )
            }
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(lantern),
            )
            if (sounding?.reach == Reach.UNREACHABLE) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(WarnEmber),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                host.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                buildString {
                    append(host.address)
                    // Last-crossed stays put; the live round-trip rides alongside it
                    // so a reachability flap never erases the timestamp.
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
                    val latency = sounding?.latencyMs
                    if (sounding?.reach == Reach.REACHABLE && latency != null) {
                        append("  ·  $latency ms")
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
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
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

/** The fleet import's front door: tailnet charting and the near-waters sweep. */
@Composable
private fun ChartWatersCard(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "⚓ chart the waters",
            style = MaterialTheme.typography.bodyMedium,
            color = MistGrey,
        )
        Text(
            "import the tailnet  ·  sound the near waters",
            style = MaterialTheme.typography.bodySmall,
            color = MistGrey.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
