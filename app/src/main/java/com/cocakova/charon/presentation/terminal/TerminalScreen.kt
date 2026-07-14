package com.cocakova.charon.presentation.terminal

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.cocakova.charon.autocomplete.Completer
import com.cocakova.charon.autocomplete.RemoteContext
import com.cocakova.charon.autocomplete.Suggestion
import com.cocakova.charon.data.repository.CommandHistory
import com.cocakova.charon.ssh.TerminalSession
import com.cocakova.charon.terminal.input.KeyEncoder
import com.cocakova.charon.theme.CharonMono
import com.cocakova.charon.theme.MistGrey
import com.cocakova.charon.theme.ObolGold
import com.cocakova.charon.theme.StyxBlack
import com.cocakova.charon.theme.StyxTeal
import com.cocakova.charon.theme.WarnEmber
import kotlinx.coroutines.delay

@Composable
fun TerminalScreen(
    session: TerminalSession,
    sessions: List<TerminalSession>,
    commandHistory: CommandHistory,
    remoteContext: RemoteContext?,
    onSwitch: (String) -> Unit,
    onClose: (String) -> Unit,
    onReconnect: (String) -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by session.state.collectAsState()
    val selection by session.selection.collectAsState()
    val scrollOffset by session.scrollOffset.collectAsState()
    val clipboard = LocalClipboardManager.current
    var ctrl by remember { mutableStateOf(Sticky.OFF) }
    var alt by remember { mutableStateOf(Sticky.OFF) }
    var inputView by remember { mutableStateOf<TerminalInputView?>(null) }
    val inputFocus = remember { FocusRequester() }
    val prefs = LocalContext.current.getSharedPreferences("charon", Context.MODE_PRIVATE)
    // Pinch-zoomable font size, persisted; clamped to a legible band.
    var fontSizeSp by remember { mutableFloatStateOf(prefs.getFloat("font_size", 14f)) }
    LaunchedEffect(fontSizeSp) { prefs.edit().putFloat("font_size", fontSizeSp).apply() }
    var inputMode by remember {
        mutableStateOf(
            if (prefs.getString("input_mode", "predictive") == "raw") TerminalInputView.Mode.RAW
            else TerminalInputView.Mode.PREDICTIVE,
        )
    }
    LaunchedEffect(inputMode) {
        inputView?.mode = inputMode
        prefs.edit()
            .putString("input_mode", if (inputMode == TerminalInputView.Mode.RAW) "raw" else "predictive")
            .apply()
    }

    // Apply the sticky modifiers to whatever's about to go out: Ctrl folds a single
    // char to its control code, Alt (Meta) prefixes ESC. Armed modifiers fire once
    // then clear; locked ones persist. Any keystroke snaps the view to the bottom.
    fun emit(raw: String, singleChar: Boolean) {
        session.scrollToBottom()
        var out = raw
        if (singleChar && ctrl != Sticky.OFF) out = KeyEncoder.ctrl(raw[0]) ?: raw
        if (alt != Sticky.OFF) out = KeyEncoder.alt(out)
        session.sendText(out)
        session.trackInput(out) // feed the command-line reconstructor for autofill
        if (ctrl == Sticky.ARMED) ctrl = Sticky.OFF
        if (alt == Sticky.ARMED) alt = Sticky.OFF
    }

    // Drop the keyboard the moment the crossing ends (clean exit or a hard failure),
    // and whenever we leave the terminal for the Dock — nothing left to type into.
    LaunchedEffect(state) {
        if (state is TerminalSession.State.Disconnected) inputView?.hideKeyboard()
    }
    DisposableEffect(Unit) {
        onDispose { inputView?.hideKeyboard() }
    }

    // Smart autofill: history + command grammar + live host context (installed
    // commands, running tmux sessions…). ctxVersion ticks when a probe lands, so
    // suggestions refresh the moment the host answers.
    val draft by session.commandDraft.collectAsState()
    val history by commandHistory.entries.collectAsState()
    val ctxVersion by (remoteContext?.version ?: remember { kotlinx.coroutines.flow.MutableStateFlow(0) })
        .collectAsState()
    val suggestions = remember(draft, history, ctxVersion) {
        Completer.complete(draft, history, remoteContext)
    }

    // Cycle a modifier: tap toggles off<->armed (a lock is cleared by a tap too).
    fun tapMod(s: Sticky) = if (s == Sticky.OFF) Sticky.ARMED else Sticky.OFF
    // Long-press latches or releases the lock.
    fun lockMod(s: Sticky) = if (s == Sticky.LOCKED) Sticky.OFF else Sticky.LOCKED

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        SessionSwitcher(
            sessions = sessions,
            activeId = session.id,
            onSwitch = onSwitch,
            onClose = onClose,
            onNewSession = onNewSession,
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // The IME anchor fills the terminal area (a sane rect for cursor-anchor
            // queries) and sits under the Canvas. Focus is requested through Compose's
            // focus system — raw View.requestFocus() on an interop child loses the
            // focus-owner handoff and every key gets dropped upstream.
            AndroidView(
                factory = { ctx ->
                    TerminalInputView(ctx).apply {
                        onInput = { emit(it, it.length == 1) }
                        appCursorKeys = { session.term.cursorKeysApp }
                        mode = inputMode
                    }.also { inputView = it }
                },
                // Rebind to the active session each recomposition — one input view
                // serves every tab, so a switch must re-point its callbacks or
                // keystrokes keep flowing to the session you just left.
                update = { view ->
                    view.onInput = { emit(it, it.length == 1) }
                    view.appCursorKeys = { session.term.cursorKeysApp }
                },
                modifier = Modifier.fillMaxSize().focusRequester(inputFocus),
            )
            TerminalView(
                session = session,
                modifier = Modifier.fillMaxSize(),
                fontSizeSp = fontSizeSp,
                onRequestFocus = {
                    runCatching { inputFocus.requestFocus() }
                    inputView?.showKeyboard()
                },
                onZoom = { zoom ->
                    fontSizeSp = (fontSizeSp * zoom).coerceIn(8f, 32f)
                },
            )

            // Scrolled-back indicator: a pill anchored to the live edge. Tap to
            // return to the bottom (typing does the same).
            if (scrollOffset > 0) {
                Text(
                    "▼ live",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(ObolGold)
                        .clickable { session.scrollToBottom() }
                        .padding(horizontal = 18.dp, vertical = 7.dp),
                )
            }

            // Copy affordance: a teal pill that surfaces while a selection holds.
            if (selection != null) {
                Text(
                    "copy",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(StyxTeal)
                        .clickable {
                            session.copySelection()?.let {
                                clipboard.setText(AnnotatedString(it))
                            }
                            session.clearSelection()
                        }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            when (val s = state) {
                is TerminalSession.State.Connecting -> ConnectingPill("crossing the Styx…")
                is TerminalSession.State.Reconnecting ->
                    ReconnectingOverlay(attempt = s.attempt, onGiveUp = { onClose(session.id) })
                is TerminalSession.State.Disconnected ->
                    if (s.clean) {
                        // You stepped off the ferry — a quick flourish, then the Dock
                        // (where the ferry docks). Auto-dismiss, no tap needed.
                        ReturnedToShoreFlourish()
                        LaunchedEffect(session.id) {
                            delay(900)
                            onClose(session.id)
                        }
                    } else {
                        CrossingFailedOverlay(
                            reason = s.reason,
                            onRecross = { onReconnect(session.id) },
                            onClose = { onClose(session.id) },
                        )
                    }
                else -> {}
            }
        }
        // Smart autofill: tapping a chip types only the missing tail — our tracker
        // knows exactly what's already on the line, so the remote echoes it in place.
        // A token completion carries a trailing space, which cascades straight into
        // the next word's suggestions (tmux → attach → -t → the live session names).
        if (suggestions.isNotEmpty()) {
            CommandSuggestions(
                suggestions = suggestions,
                onAccept = { s ->
                    session.scrollToBottom()
                    session.sendText(s.insert)
                    session.trackInput(s.insert)
                },
            )
        }
        AccessoryRow(
            ctrl = ctrl,
            onCtrl = { ctrl = tapMod(ctrl) },
            onCtrlLock = { ctrl = lockMod(ctrl) },
            alt = alt,
            onAlt = { alt = tapMod(alt) },
            onAltLock = { alt = lockMod(alt) },
            onKey = { key ->
                // Special keys carry Alt (ESC prefix) but not Ctrl; encode then emit.
                emit(KeyEncoder.encode(key, appCursorKeys = session.term.cursorKeysApp), singleChar = false)
            },
            onText = { emit(it, it.length == 1) },
            onPaste = {
                session.scrollToBottom()
                clipboard.getText()?.text?.let { session.paste(it) }
            },
            rawInput = inputMode == TerminalInputView.Mode.RAW,
            onToggleInputMode = {
                inputMode = if (inputMode == TerminalInputView.Mode.RAW) {
                    TerminalInputView.Mode.PREDICTIVE
                } else {
                    TerminalInputView.Mode.RAW
                }
            },
        )
    }
}

/**
 * The session switcher: the thin band above the grid, now one tab per live crossing.
 * Termux has no frame; Termius buries you in toolbar — Charon wears a row of ferries.
 * Tap a tab to switch, × to close, + to raise another crossing from the Dock.
 */
@Composable
private fun SessionSwitcher(
    sessions: List<TerminalSession>,
    activeId: String,
    onSwitch: (String) -> Unit,
    onClose: (String) -> Unit,
    onNewSession: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            sessions.forEach { s ->
                SessionTab(
                    session = s,
                    active = s.id == activeId,
                    onClick = { onSwitch(s.id) },
                    onClose = { onClose(s.id) },
                )
                Spacer(Modifier.width(6.dp))
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onNewSession)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    "+",
                    style = MaterialTheme.typography.titleMedium,
                    color = StyxTeal,
                )
            }
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

/** One ferry in the switcher: a breathing state dot, its label, and a close ×. */
@Composable
private fun SessionTab(
    session: TerminalSession,
    active: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    val state by session.state.collectAsState()
    val dotColor by animateColorAsState(
        targetValue = when (state) {
            is TerminalSession.State.Connected -> StyxTeal
            is TerminalSession.State.Connecting -> ObolGold
            is TerminalSession.State.Reconnecting -> ObolGold
            is TerminalSession.State.Disconnected -> WarnEmber
        },
        animationSpec = tween(400),
        label = "tabDot",
    )
    val breathe by rememberInfiniteTransition(label = "breathe").animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1300, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breatheAlpha",
    )
    // Breathe while connected; pulse while redialing — anything settled holds steady.
    val dotAlpha = when (state) {
        is TerminalSession.State.Connected, is TerminalSession.State.Reconnecting -> breathe
        else -> 1f
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (active) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surface,
            )
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .graphicsLayer { alpha = dotAlpha }
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            session.label,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) MaterialTheme.colorScheme.onSurface else MistGrey,
            maxLines = 1,
        )
        Spacer(Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onClose)
                .padding(4.dp),
        ) {
            Text(
                "×",
                style = MaterialTheme.typography.bodyMedium,
                color = MistGrey,
            )
        }
    }
}

/**
 * The smart-autofill strip: a scrollable row of inline completions — history lines,
 * subcommands/flags from the command grammar, and live host values (running tmux
 * sessions, containers). Sits just above the accessory row so a completion is a
 * thumb-tap from the keys. The typed part shows dim; the completion glows teal.
 */
@Composable
private fun CommandSuggestions(
    suggestions: List<Suggestion>,
    onAccept: (Suggestion) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        suggestions.forEach { s ->
            SuggestionChipPill(suggestion = s, onClick = { onAccept(s) })
            Spacer(Modifier.width(8.dp))
        }
    }
}

/** One autofill offer: a » sigil, the already-typed part dimmed, the rest in teal. */
@Composable
private fun SuggestionChipPill(suggestion: Suggestion, onClick: () -> Unit) {
    val label = buildAnnotatedString {
        withStyle(SpanStyle(color = MistGrey)) { append(suggestion.display.take(suggestion.matched)) }
        withStyle(SpanStyle(color = StyxTeal, fontWeight = FontWeight.Medium)) {
            append(suggestion.display.substring(suggestion.matched))
        }
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("»", fontFamily = CharonMono, fontSize = 13.sp, color = ObolGold)
        Spacer(Modifier.width(8.dp))
        Text(text = label, fontFamily = CharonMono, fontSize = 13.sp, maxLines = 1)
    }
}

// ---- Session-state overlays -------------------------------------------------------

/** A slim bottom pill for the "crossing the Styx…" cold-connect beat. */
@Composable
private fun ConnectingPill(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(bottom = 16.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 18.dp, vertical = 8.dp),
        )
    }
}

/**
 * A transport drop, redialing: a dimming scrim, a pulsing gold lantern, the attempt
 * count, and a way out. The ferry turns back into the mist rather than beaching.
 */
@Composable
private fun ReconnectingOverlay(attempt: Int, onGiveUp: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "recross").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(760, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "recrossPulse",
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(StyxBlack.copy(alpha = 0.82f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .graphicsLayer { alpha = pulse }
                    .clip(CircleShape)
                    .background(ObolGold),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "re-crossing the Styx…",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "attempt $attempt",
                style = MaterialTheme.typography.bodySmall,
                color = MistGrey,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onGiveUp) { Text("give up", color = MistGrey) }
        }
    }
}

/** Hard end (no redial): the crossing failed. Cross again, or let it go. */
@Composable
private fun CrossingFailedOverlay(
    reason: String,
    onRecross: () -> Unit,
    onClose: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(StyxBlack.copy(alpha = 0.82f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "the crossing failed",
                style = MaterialTheme.typography.titleLarge,
                color = WarnEmber,
            )
            Text(
                reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onRecross) { Text("cross again") }
                OutlinedButton(onClick = onClose) { Text("close") }
            }
        }
    }
}

/**
 * The clean exit — you stepped off the ferry. A quick themed flourish: the wordmark
 * fades up, a gold waterline sweeps across, then the whole screen hands off to the
 * Dock (where the ferry docks). Auto-dismissed by the caller; no tap.
 */
@Composable
private fun ReturnedToShoreFlourish() {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val sweep by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(560, easing = FastOutSlowInEasing),
        label = "shoreSweep",
    )
    val fade by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(280),
        label = "shoreFade",
    )
    Box(
        modifier = Modifier.fillMaxSize().background(StyxBlack),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "returned to shore",
                style = MaterialTheme.typography.titleLarge,
                color = StyxTeal,
                modifier = Modifier.graphicsLayer { alpha = fade },
            )
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .height(2.dp)
                    .width((168 * sweep).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(ObolGold),
            )
        }
    }
}
