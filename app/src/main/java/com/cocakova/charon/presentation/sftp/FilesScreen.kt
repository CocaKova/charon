package com.cocakova.charon.presentation.sftp

import android.content.Intent
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cocakova.charon.ssh.RemoteEntry
import com.cocakova.charon.ssh.SftpChannel
import com.cocakova.charon.ssh.SftpTransfers
import com.cocakova.charon.theme.Styx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * The hold — the ferry's cargo deck. A single-pane SFTP browser over the active
 * session's transport: tap a directory to enter, tap a file for its cargo sheet
 * (carry ashore / rename / release), **⇡ aboard** brings a local document up via
 * SAF. Transfers ride their own channels and report in the ledger strip at the
 * bottom, resuming themselves through hiccups. Termius charges for this deck;
 * Charon's is part of the fare.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    sessionLabel: String,
    openSftp: () -> SftpChannel?,
    transfers: SftpTransfers,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var channel by remember { mutableStateOf<SftpChannel?>(null) }
    var dir by remember { mutableStateOf<String?>(null) }
    // The landed listing keeps its dir alongside it, so a change of deck can slide in
    // the direction of travel while the old deck stays put until the new one arrives.
    var listing by remember { mutableStateOf<Pair<String, List<RemoteEntry>>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<RemoteEntry?>(null) }
    var renameTarget by remember { mutableStateOf<RemoteEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<RemoteEntry?>(null) }
    var showMkdir by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }
    val ledger by transfers.transfers.collectAsState()

    // System back steps off the deck, back to the terminal — not out of the app.
    BackHandler(onBack = onBack)

    // The browser's own channel: opened once, closed on leave (off-main — close is
    // channel I/O too). Transfers never share it.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val ch = openSftp()
            if (ch == null) {
                error = "the hold is unreachable"
                loading = false
            } else {
                channel = ch
                dir = runCatching { ch.home() }.getOrElse { "/" }
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { thread { runCatching { channel?.close() } } }
    }

    LaunchedEffect(dir, channel, refreshTick) {
        val ch = channel ?: return@LaunchedEffect
        val d = dir ?: return@LaunchedEffect
        loading = true
        error = null
        withContext(Dispatchers.IO) {
            runCatching { ch.list(d) }
                .onSuccess { list ->
                    listing = d to list.sortedWith(
                        compareByDescending<RemoteEntry> { it.isDir }
                            .thenBy { it.name.lowercase() },
                    )
                }
                .onFailure { error = it.message ?: "cannot read this deck" }
        }
        loading = false
    }

    // SAF: carrying ashore (CREATE_DOCUMENT picks the landing spot) …
    var pendingPull by remember { mutableStateOf<RemoteEntry?>(null) }
    val pullLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val entry = pendingPull
        pendingPull = null
        if (uri != null && entry != null) {
            transfers.pull(openSftp, entry.path, entry.size, uri)
        }
    }
    // … and aboard (OPEN_DOCUMENT picks the cargo).
    val pushLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val d = dir
        if (uri != null && d != null) {
            scope.launch(Dispatchers.IO) {
                var name = uri.lastPathSegment?.substringAfterLast('/') ?: "upload"
                var size = -1L
                runCatching {
                    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val nameIdx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (c.moveToFirst()) {
                            if (nameIdx >= 0) c.getString(nameIdx)?.let { name = it }
                            if (sizeIdx >= 0) size = c.getLong(sizeIdx)
                        }
                    }
                }
                transfers.push(openSftp, uri, name, size, d)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        HoldTopBar(
            sessionLabel = sessionLabel,
            dir = dir ?: "…",
            onBack = onBack,
            onRefresh = { refreshTick++ },
            onMkdir = { showMkdir = true },
            onUpload = { pushLauncher.launch(arrayOf("*/*")) },
        )
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.surfaceVariant)
        // A quiet pulse under the bar while a listing is in flight over the old deck.
        if (loading && listing != null) {
            LinearProgressIndicator(
                color = Styx.water,
                trackColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
        }

        Box(Modifier.weight(1f)) {
            when {
                loading && listing == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Styx.water)
                }
                error != null -> Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(error!!, style = MaterialTheme.typography.bodyMedium, color = Styx.ember)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { refreshTick++ }) { Text("try again") }
                }
                else -> AnimatedContent(
                    targetState = listing,
                    transitionSpec = {
                        val from = initialState?.first ?: ""
                        val to = targetState?.first ?: ""
                        if (from == to) {
                            // Same deck refreshed (rename/delete/mkdir): just settle.
                            fadeIn(tween(150)) togetherWith fadeOut(tween(150))
                        } else {
                            // Slide in the direction of travel: deeper comes from the
                            // right, climbing out comes from the left.
                            val way = if (to.count { it == '/' } > from.count { it == '/' }) 1 else -1
                            (slideInHorizontally(tween(220)) { it / 3 * way } + fadeIn(tween(220))) togetherWith
                                (slideOutHorizontally(tween(220)) { -it / 3 * way } + fadeOut(tween(180)))
                        }
                    },
                    label = "deck",
                ) { deck ->
                    if (deck == null) {
                        Box(Modifier.fillMaxSize())
                    } else LazyColumn(Modifier.fillMaxSize()) {
                        val d = deck.first
                        if (d != "/" && d.isNotEmpty()) {
                            item(key = "..") {
                                UpRow { dir = d.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" } }
                            }
                        }
                        items(deck.second, key = { it.path }) { entry ->
                            EntryRow(
                                entry = entry,
                                onOpen = {
                                    if (entry.isDir) dir = entry.path else selected = entry
                                },
                                onLongPress = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    selected = entry
                                },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
        }

        // The cargo ledger: every crossing in flight.
        if (ledger.isNotEmpty()) {
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.surfaceVariant)
            Column(
                Modifier
                    .fillMaxWidth()
                    .animateContentSize()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                ledger.forEach { t ->
                    TransferRow(t) { landed ->
                        // A landed pull opens where it landed — extension mime first,
                        // since CREATE_DOCUMENT stamped it octet-stream.
                        val ext = landed.name.substringAfterLast('.', "").lowercase(Locale.US)
                        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                            ?: "*/*"
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW)
                                    .setDataAndType(landed.landedAt, mime)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            )
                        }.onFailure {
                            Toast.makeText(context, "no app aboard can open this", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    // ---- sheets & dialogs ----------------------------------------------------------

    selected?.let { entry ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            CargoSheet(
                entry = entry,
                onPull = {
                    selected = null
                    if (!entry.isDir) {
                        pendingPull = entry
                        pullLauncher.launch(entry.name)
                    }
                },
                onRename = { selected = null; renameTarget = entry },
                onDelete = { selected = null; deleteTarget = entry },
            )
        }
    }

    renameTarget?.let { entry ->
        NameDialog(
            title = "rename",
            initial = entry.name,
            confirm = "rename",
            onDismiss = { renameTarget = null },
        ) { newName ->
            renameTarget = null
            val ch = channel ?: return@NameDialog
            scope.launch(Dispatchers.IO) {
                runCatching {
                    ch.rename(entry.path, entry.path.substringBeforeLast('/') + "/" + newName)
                }.onFailure { error = it.message }
                refreshTick++
            }
        }
    }

    deleteTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("release ${entry.name}?", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    if (entry.isDir) "the folder goes into the river (must be empty)"
                    else "the file goes into the river — there's no fishing it back",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Styx.mist,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    val ch = channel ?: return@TextButton
                    scope.launch(Dispatchers.IO) {
                        runCatching { ch.delete(entry.path, entry.isDir) }
                            .onFailure { error = it.message }
                        refreshTick++
                    }
                }) { Text("release", color = Styx.ember) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("keep", color = Styx.mist) }
            },
        )
    }

    if (showMkdir) {
        NameDialog(
            title = "new folder",
            initial = "",
            confirm = "create",
            onDismiss = { showMkdir = false },
        ) { name ->
            showMkdir = false
            val ch = channel ?: return@NameDialog
            val d = dir ?: return@NameDialog
            scope.launch(Dispatchers.IO) {
                runCatching { ch.mkdir(d.trimEnd('/') + "/" + name) }
                    .onFailure { error = it.message }
                refreshTick++
            }
        }
    }
}

// ---- pieces -------------------------------------------------------------------------

@Composable
private fun HoldTopBar(
    sessionLabel: String,
    dir: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onMkdir: () -> Unit,
    onUpload: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarAction("←", onBack)
            Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                Text(
                    "the hold — $sessionLabel",
                    style = MaterialTheme.typography.labelMedium,
                    color = Styx.water,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    dir,
                    style = MaterialTheme.typography.bodySmall,
                    color = Styx.mist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BarAction("⇡ aboard", onUpload, tint = Styx.coin)
            BarAction("+dir", onMkdir)
            BarAction("↻", onRefresh)
        }
    }
}

@Composable
private fun BarAction(label: String, onClick: () -> Unit, tint: androidx.compose.ui.graphics.Color = Styx.mist) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = tint,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
    )
}

@Composable
private fun UpRow(onUp: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onUp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("↰ ..", style = MaterialTheme.typography.bodyMedium, color = Styx.mist)
    }
}

@Composable
private fun EntryRow(
    entry: RemoteEntry,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A quiet sigil column: directories are teal doors, files bone cargo.
        Text(
            when {
                entry.isLink -> "⇝"
                entry.isDir -> "▸"
                else -> "·"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (entry.isDir) Styx.water else Styx.mist,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            entry.name + if (entry.isDir) "/" else "",
            style = MaterialTheme.typography.bodyMedium,
            color = if (entry.isDir) Styx.water else Styx.bone,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        if (!entry.isDir) {
            Text(
                humanBytes(entry.size),
                style = MaterialTheme.typography.bodySmall,
                color = Styx.mist,
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            DATE_FMT.format(Date(entry.mtime)),
            style = MaterialTheme.typography.bodySmall,
            color = Styx.mist.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun CargoSheet(
    entry: RemoteEntry,
    onPull: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
        Text(
            entry.name,
            style = MaterialTheme.typography.titleMedium,
            color = Styx.bone,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (entry.isDir) "directory" else humanBytes(entry.size),
            style = MaterialTheme.typography.bodySmall,
            color = Styx.mist,
            modifier = Modifier.padding(top = 2.dp, bottom = 14.dp),
        )
        if (!entry.isDir) {
            SheetAction("⇣  carry ashore", Styx.water, onPull)
        }
        SheetAction("✎  rename", Styx.bone, onRename)
        SheetAction("✕  release into the river", Styx.ember, onDelete)
    }
}

@Composable
private fun SheetAction(label: String, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = tint,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
    )
}

@Composable
private fun TransferRow(t: SftpTransfers.Transfer, onOpen: (SftpTransfers.Transfer) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val tint = when (t.state) {
        SftpTransfers.State.FAILED -> Styx.ember
        SftpTransfers.State.DONE -> Styx.water
        SftpTransfers.State.RUNNING ->
            if (t.direction == SftpTransfers.Direction.PULL) Styx.water else Styx.coin
    }
    // The drawn bar chases the real offset from zero, so even an instant crossing
    // sweeps the width — the eye needs the journey, however short the river.
    val sweep = remember { Animatable(0f) }
    val target = when {
        t.state == SftpTransfers.State.DONE -> 1f
        t.total > 0 -> (t.done.toFloat() / t.total).coerceIn(0f, 1f)
        else -> 0f
    }
    LaunchedEffect(target) { sweep.animateTo(target, tween(450)) }
    LaunchedEffect(t.state) {
        when (t.state) {
            SftpTransfers.State.DONE -> haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            SftpTransfers.State.FAILED -> haptic.performHapticFeedback(HapticFeedbackType.Reject)
            SftpTransfers.State.RUNNING -> Unit
        }
    }
    val openable = t.state == SftpTransfers.State.DONE && t.landedAt != null
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .then(if (openable) Modifier.clickable { onOpen(t) } else Modifier)
            .padding(vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (t.direction == SftpTransfers.Direction.PULL) "⇣" else "⇡",
                style = MaterialTheme.typography.labelMedium,
                color = tint,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                t.name,
                style = MaterialTheme.typography.labelMedium,
                color = Styx.bone,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                when (t.state) {
                    SftpTransfers.State.FAILED -> t.error ?: "the crossing failed"
                    SftpTransfers.State.DONE ->
                        if (openable) "✓ ashore — tap to open" else "✓ aboard"
                    SftpTransfers.State.RUNNING ->
                        if (t.total > 0) "${humanBytes(t.done)} / ${humanBytes(t.total)}"
                        else humanBytes(t.done)
                },
                style = MaterialTheme.typography.bodySmall,
                color = when (t.state) {
                    SftpTransfers.State.FAILED -> Styx.ember
                    SftpTransfers.State.DONE -> tint
                    SftpTransfers.State.RUNNING -> Styx.mist
                },
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(3.dp))
        if (t.state == SftpTransfers.State.RUNNING && t.total <= 0) {
            // Size unknown — the river still has to look like it's moving.
            LinearProgressIndicator(
                color = tint,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
            )
        } else {
            LinearProgressIndicator(
                progress = { sweep.value },
                color = tint,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
            )
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (value.isNotBlank()) onConfirm(value.trim()) },
            ) { Text(confirm, color = Styx.water) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("cancel", color = Styx.mist) }
        },
    )
}

private val DATE_FMT = SimpleDateFormat("MMM d HH:mm", Locale.US)

private fun humanBytes(bytes: Long): String = when {
    bytes < 0 -> "?"
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "%.1fK".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1fM".format(bytes / (1024.0 * 1024))
    else -> "%.2fG".format(bytes / (1024.0 * 1024 * 1024))
}
