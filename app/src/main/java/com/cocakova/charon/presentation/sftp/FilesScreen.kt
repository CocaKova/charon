package com.cocakova.charon.presentation.sftp

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cocakova.charon.ssh.RemoteEntry
import com.cocakova.charon.ssh.SftpChannel
import com.cocakova.charon.ssh.SftpTransfers
import com.cocakova.charon.theme.BoneWhite
import com.cocakova.charon.theme.MistGrey
import com.cocakova.charon.theme.ObolGold
import com.cocakova.charon.theme.StyxTeal
import com.cocakova.charon.theme.WarnEmber
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
    var channel by remember { mutableStateOf<SftpChannel?>(null) }
    var dir by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<RemoteEntry>>(emptyList()) }
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
                    entries = list.sortedWith(
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

        Box(Modifier.weight(1f)) {
            when {
                loading && entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = StyxTeal)
                }
                error != null -> Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(error!!, style = MaterialTheme.typography.bodyMedium, color = WarnEmber)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { refreshTick++ }) { Text("try again") }
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    val d = dir
                    if (d != null && d != "/" && d.isNotEmpty()) {
                        item(key = "..") {
                            UpRow { dir = d.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" } }
                        }
                    }
                    items(entries, key = { it.path }) { entry ->
                        EntryRow(
                            entry = entry,
                            onOpen = {
                                if (entry.isDir) dir = entry.path else selected = entry
                            },
                            onLongPress = { selected = entry },
                        )
                    }
                }
            }
        }

        // The cargo ledger: every crossing in flight.
        if (ledger.isNotEmpty()) {
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.surfaceVariant)
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                ledger.forEach { t -> TransferRow(t) }
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
                    color = MistGrey,
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
                }) { Text("release", color = WarnEmber) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("keep", color = MistGrey) }
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
                    color = StyxTeal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    dir,
                    style = MaterialTheme.typography.bodySmall,
                    color = MistGrey,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BarAction("⇡ aboard", onUpload, tint = ObolGold)
            BarAction("+dir", onMkdir)
            BarAction("↻", onRefresh)
        }
    }
}

@Composable
private fun BarAction(label: String, onClick: () -> Unit, tint: androidx.compose.ui.graphics.Color = MistGrey) {
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
        Text("↰ ..", style = MaterialTheme.typography.bodyMedium, color = MistGrey)
    }
}

@Composable
private fun EntryRow(
    entry: RemoteEntry,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    Row(
        Modifier
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
            color = if (entry.isDir) StyxTeal else MistGrey,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            entry.name + if (entry.isDir) "/" else "",
            style = MaterialTheme.typography.bodyMedium,
            color = if (entry.isDir) StyxTeal else BoneWhite,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        if (!entry.isDir) {
            Text(
                humanBytes(entry.size),
                style = MaterialTheme.typography.bodySmall,
                color = MistGrey,
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            DATE_FMT.format(Date(entry.mtime)),
            style = MaterialTheme.typography.bodySmall,
            color = MistGrey.copy(alpha = 0.7f),
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
            color = BoneWhite,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (entry.isDir) "directory" else humanBytes(entry.size),
            style = MaterialTheme.typography.bodySmall,
            color = MistGrey,
            modifier = Modifier.padding(top = 2.dp, bottom = 14.dp),
        )
        if (!entry.isDir) {
            SheetAction("⇣  carry ashore", StyxTeal, onPull)
        }
        SheetAction("✎  rename", BoneWhite, onRename)
        SheetAction("✕  release into the river", WarnEmber, onDelete)
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
private fun TransferRow(t: SftpTransfers.Transfer) {
    val tint = when (t.state) {
        SftpTransfers.State.FAILED -> WarnEmber
        SftpTransfers.State.DONE -> StyxTeal
        SftpTransfers.State.RUNNING ->
            if (t.direction == SftpTransfers.Direction.PULL) StyxTeal else ObolGold
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
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
                color = BoneWhite,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                when (t.state) {
                    SftpTransfers.State.FAILED -> t.error ?: "failed"
                    SftpTransfers.State.DONE -> "ashore"
                    SftpTransfers.State.RUNNING ->
                        if (t.total > 0) "${humanBytes(t.done)} / ${humanBytes(t.total)}"
                        else humanBytes(t.done)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (t.state == SftpTransfers.State.FAILED) WarnEmber else MistGrey,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(3.dp))
        if (t.state == SftpTransfers.State.RUNNING && t.total > 0) {
            LinearProgressIndicator(
                progress = { (t.done.toFloat() / t.total).coerceIn(0f, 1f) },
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
            ) { Text(confirm, color = StyxTeal) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("cancel", color = MistGrey) }
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
