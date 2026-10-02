package com.cocakova.charon.presentation.terminal

import androidx.compose.foundation.layout.sizeIn
import com.cocakova.charon.theme.Hulls
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import com.cocakova.charon.autocomplete.CommandGate
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import com.cocakova.charon.autocomplete.SecretGate
import com.cocakova.charon.autocomplete.Completer
import com.cocakova.charon.cargo.CargoWatch
import com.cocakova.charon.autocomplete.RemoteContext
import com.cocakova.charon.autocomplete.Suggestion
import com.cocakova.charon.data.db.PortForwardEntity
import com.cocakova.charon.data.db.SnippetEntity
import com.cocakova.charon.data.repository.CommandHistory
import com.cocakova.charon.presentation.forwards.ForwardsSheet
import com.cocakova.charon.ssh.TerminalSession
import com.cocakova.charon.terminal.Apparition
import com.cocakova.charon.terminal.SearchEngine
import com.cocakova.charon.terminal.input.KeyEncoder
import com.cocakova.charon.theme.CharonMono
import com.cocakova.charon.theme.Styx
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun TerminalScreen(
    session: TerminalSession,
    sessions: List<TerminalSession>,
    commandHistory: CommandHistory,
    remoteContext: RemoteContext?,
    hostId: String?,
    snippets: List<SnippetEntity>,
    forwards: List<PortForwardEntity>,
    runningForwards: Set<String>,
    forwardError: String?,
    onSwitch: (String) -> Unit,
    onClose: (String) -> Unit,
    onReconnect: (String) -> Unit,
    onNewSession: () -> Unit,
    onDock: () -> Unit,
    onFiles: () -> Unit,
    onSaveSnippet: (SnippetEntity) -> Unit,
    onDeleteSnippet: (String) -> Unit,
    onToggleForward: (PortForwardEntity) -> Unit,
    onSaveForward: (PortForwardEntity) -> Unit,
    onDeleteForward: (String) -> Unit,
    /** Carry the far shore's localhost:port here (a tapped dev-server link); the phone's port. */
    onForwardLink: suspend (String, Int) -> Result<Int> = { _, _ -> Result.failure(IllegalStateException("no crossing")) },
    /** Open the hold at a far-shore path (a tapped file:// link). */
    onFilesAt: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by session.state.collectAsState()
    val selection by session.selection.collectAsState()
    val scrollOffset by session.scrollOffset.collectAsState()
    val sounded by session.sounded.collectAsState()
    val toll by session.toll.collectAsState()
    val tollPulse by session.tollPulse.collectAsState()
    // Subscribe through collectAsState, but read the flow itself: collectAsState
    // hands back the previous tab's value for a frame after a switch, and a barge
    // must never sail over the wrong ferry.
    val cargoTick by session.cargo.collectAsState()
    val cargo = session.cargo.value.also { cargoTick }
    val clipboard = LocalClipboardManager.current
    var ctrl by remember { mutableStateOf(Sticky.OFF) }
    var alt by remember { mutableStateOf(Sticky.OFF) }
    var inputView by remember { mutableStateOf<TerminalInputView?>(null) }
    val inputFocus = remember { FocusRequester() }
    // Decoded shades belong to the session, not to a recomposition: the renderer and
    // the lightbox share one cache so opening an image costs no second decode.
    val apparitionCache = remember(session.id) { ApparitionCache() }
    var lightbox by remember(session.id) { mutableStateOf<Apparition?>(null) }
    // A marked passage (OSC 8 link) under the finger: the confirm sheet's subject.
    var linkSighting by remember(session.id) { mutableStateOf<LinkSighting?>(null) }
    // Dredge the wake: search over scrollback + live grid. One query, one current
    // hit; nav walks the sightings and pins the glass on each. The lock is held only
    // to copy the text; the scan runs on a worker. While the bar is open the dredge
    // refreshes as output lands (at most a few times a second), the eye staying on
    // its words; a fresh query starts at the newest sighting.
    var dredge by remember(session.id) { mutableStateOf("") }
    var dredgePattern by remember(session.id) { mutableStateOf(false) }
    var dredgeFocused by remember(session.id) { mutableStateOf(false) }
    var dredgeError by remember(session.id) { mutableStateOf<String?>(null) }
    var searchState by remember(session.id) { mutableStateOf<SearchEngine.SearchState?>(null) }
    fun showHit(hit: SearchEngine.Hit) {
        val row = synchronized(session.lock) { hit.row(session.term.screen.linesPushed) }
        // A hit already on the glass stays where it is; otherwise it lands a little
        // below the top, with a line or two of what led up to it.
        val top = -session.scrollOffset.value
        if (row < top || row >= top + session.term.rows) session.jumpToRow(row - 2)
    }
    LaunchedEffect(session.id, dredge, dredgePattern) {
        searchState = null
        if (dredge.isEmpty()) {
            dredgeError = null
            return@LaunchedEffect
        }
        val matcher = SearchEngine.compile(dredge, dredgePattern)
        if (matcher == null) {
            dredgeError = "that pattern doesn't parse"
            return@LaunchedEffect
        }
        dredgeError = null
        var first = true
        session.outputTick.collect { // a StateFlow: a slow collector sees only the latest tick
            val hits = withContext(Dispatchers.Default) {
                val snap = synchronized(session.lock) { SearchEngine.snapshot(session.term.screen) }
                SearchEngine.find(snap, matcher)
            }
            val prev = searchState
            val current = if (first || prev == null) hits.lastIndex else SearchEngine.follow(prev, hits)
            searchState = if (hits.isEmpty()) null else SearchEngine.SearchState(hits, current)
            if (first) hits.getOrNull(current)?.let { showHit(it) }
            first = false
            delay(DREDGE_REFRESH_MS)
        }
    }
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("charon", Context.MODE_PRIVATE) }
    // Pinch-zoomable font size, persisted; clamped to a legible band. The write is
    // debounced — a pinch storms dozens of ticks per second and each restart of the
    // effect cancels the last pending write — with a flush on leave so the settled
    // size survives even a mid-gesture exit.
    var fontSizeSp by remember { mutableFloatStateOf(prefs.getFloat("font_size", 14f)) }
    LaunchedEffect(fontSizeSp) {
        delay(250)
        prefs.edit().putFloat("font_size", fontSizeSp).apply()
    }
    DisposableEffect(Unit) {
        onDispose { prefs.edit().putFloat("font_size", fontSizeSp).apply() }
    }
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
    fun send(out: String) {
        session.scrollToBottom()
        session.sendText(out)
        session.trackInput(out) // feed the command-line reconstructor for autofill
        if (ctrl == Sticky.ARMED) ctrl = Sticky.OFF
        if (alt == Sticky.ARMED) alt = Sticky.OFF
    }

    fun stickyMods(): Int =
        (if (ctrl != Sticky.OFF) KeyEncoder.MOD_CTRL else 0) or (if (alt != Sticky.OFF) KeyEncoder.MOD_ALT else 0)

    fun emit(raw: String, singleChar: Boolean) {
        val flags = session.term.kittyKeyboardFlags
        var out = raw
        if (KeyEncoder.kittyActive(flags)) {
            // Kitty keyboard protocol: the sticky modifiers ride inside the key's own
            // escape (sticky Ctrl + i is not Tab). A key the hardware path already
            // encoded carries its own modifiers and goes out untouched.
            val mods = stickyMods()
            if (singleChar && mods != 0) out = KeyEncoder.encodeKittyChar(raw[0], mods, flags)
            else if (alt != Sticky.OFF && !raw.startsWith(KeyEncoder.ESC)) out = KeyEncoder.alt(raw)
        } else {
            if (singleChar && ctrl != Sticky.OFF) out = KeyEncoder.ctrl(raw[0]) ?: raw
            if (alt != Sticky.OFF) out = KeyEncoder.alt(out)
        }
        send(out)
    }

    // An accessory-row special key. Under the Kitty protocol both sticky modifiers
    // fold into it (Ctrl+← is a real chord there); legacy carries Alt as an ESC
    // prefix and drops Ctrl, as it always has.
    fun emitKey(key: KeyEncoder.Key) {
        val flags = session.term.kittyKeyboardFlags
        if (KeyEncoder.kittyActive(flags)) {
            KeyEncoder.encodeKitty(key, stickyMods(), flags)?.let(::send)
        } else {
            emit(KeyEncoder.encode(key, appCursorKeys = session.term.cursorKeysApp), singleChar = false)
        }
    }

    // A modifier armed on one ferry must not discharge into another: switching tabs
    // clears the sticky state rather than carrying it silently across sessions.
    LaunchedEffect(session.id) {
        ctrl = Sticky.OFF
        alt = Sticky.OFF
    }

    // Drop the keyboard the moment the crossing ends (clean exit or a hard failure),
    // and whenever we leave the terminal for the Dock — nothing left to type into.
    LaunchedEffect(state) {
        if (state is TerminalSession.State.Disconnected) inputView?.hideKeyboard()
    }
    DisposableEffect(Unit) {
        onDispose { inputView?.hideKeyboard() }
    }

    // Focus events (DECSET 1004): this tab has the traveller's eye while it's the
    // one on screen and the app is in front. A tab switch hands focus over (the old
    // effect reports out, the new one in); leaving for the Dock or another app
    // reports out. Programs that never asked hear nothing.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(session, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> session.focusChanged(true)
                Lifecycle.Event.ON_PAUSE -> session.focusChanged(false)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            session.focusChanged(false)
        }
    }

    // The bell: a light tick under the ripple TerminalView draws. Rate-limited at
    // the session, still-able at the helm.
    val bellOn = remember { prefs.getBoolean("bell", true) }
    val haptic = LocalHapticFeedback.current
    val bellTick by session.bell.collectAsState()
    val bellNow = session.bell.value.also { bellTick }
    var bellHeard by remember(session.id) { mutableStateOf(bellNow) }
    LaunchedEffect(session.id, bellNow) {
        if (bellNow == bellHeard) return@LaunchedEffect
        bellHeard = bellNow
        if (bellOn) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }

    // The key lent onward (agent forwarding): every signature it makes shows, for a
    // beat, as a gold pill — a lent key is never at work unseen.
    val keyTick by session.keyLent.collectAsState()
    val keyNow = session.keyLent.value.also { keyTick }
    var keySeen by remember(session.id) { mutableStateOf(keyNow) }
    var keyShown by remember(session.id) { mutableStateOf(false) }
    LaunchedEffect(session.id, keyNow) {
        if (keyNow == keySeen) return@LaunchedEffect
        keySeen = keyNow
        keyShown = true
        delay(2200)
        keyShown = false
    }

    // OSC 52: a yank from the far shore, behind consent per user@host.
    val clipTick by session.clipboardOffer.collectAsState()
    val clipOffer = session.clipboardOffer.value.also { clipTick }
    var clipAsking by remember(session.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(session.id, clipOffer) {
        val text = clipOffer ?: return@LaunchedEffect
        session.clipboardOffer.value = null
        when (ClipboardConsent.of(prefs, session.label)) {
            ClipboardConsent.ALWAYS -> clipboard.setText(AnnotatedString(text))
            ClipboardConsent.NEVER -> {}
            else -> clipAsking = text
        }
    }

    // Keep the screen lit at sea (opt-in from the helm): a terminal you're watching
    // shouldn't doze mid-tail. The flag lifts the moment the terminal leaves.
    val activityWindow = (context as? android.app.Activity)?.window
    DisposableEffect(activityWindow) {
        if (prefs.getBoolean("keep_screen_on", false)) {
            activityWindow?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activityWindow?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Smart autofill: history + command grammar + live host context (installed
    // commands, running tmux sessions…). ctxVersion ticks when a probe lands, so
    // suggestions refresh the moment the host answers.
    val draft by session.commandDraft.collectAsState()
    val history by commandHistory.entries.collectAsState()
    val ctxVersion by (remoteContext?.version ?: remember { kotlinx.coroutines.flow.MutableStateFlow(0) })
        .collectAsState()
    // History the strip may draw on: command lines only, judged against the host's
    // own inventory — prose that slipped into the store (recorded into a remote
    // chat/REPL before the gate existed, or before the inventory landed) never
    // suggests back. Screened once per history/inventory change, not per keystroke.
    val cleanHistory = remember(history, ctxVersion, hostId) {
        val installed = remoteContext?.commandSet.orEmpty()
        // This mooring's own lines speak first, then the rest of the fleet's; both
        // walls run again here so anything learned before they existed stays silent.
        val (mine, fleet) = history.partition { hostId != null && it.host == hostId }
        (mine + fleet).asSequence()
            .map { it.line }
            .distinct()
            .filter { CommandGate.isCommandLine(it, installed) && !SecretGate.carriesSecret(it) }
            .toList()
    }
    // Where the shell stands (OSC 7), once vouched for as a directory on this host —
    // what relative paths and branch names complete against. Null = unrigged, or
    // the shell hasn't re-sounded since the last Enter; completion falls back to
    // absolute and ~/ paths only.
    val reportedCwd by session.cwd.collectAsState()
    val cwd = remember(reportedCwd, ctxVersion) { remoteContext?.resolveCwd(reportedCwd) }
    val suggestions = remember(draft, cleanHistory, ctxVersion, cwd) {
        Completer.complete(draft, cleanHistory, remoteContext, cwd = cwd)
    }

    // Charted channels sheet, raised from the switcher's ⇆.
    var showForwards by remember { mutableStateOf(false) }

    // The toll, as displayed: flips the IME into a password editor while it stands,
    // and holds the "paid" face for a beat after sudo's newline tears the pill down.
    var tollShown by remember(session.id) { mutableStateOf<TerminalSession.TollPhase?>(null) }
    LaunchedEffect(toll) {
        inputView?.secure = toll != null
        if (toll != null) {
            tollShown = toll
        } else if (tollShown == TerminalSession.TollPhase.PAID) {
            delay(900)
            tollShown = null
        } else {
            tollShown = null
        }
    }

    // The echo net: a printable keystroke the remote never answers is being read
    // in secret — a prompt the toll grammar didn't recognize (read -s, another
    // language). The session then forgets the line and raises the toll itself.
    LaunchedEffect(draft) {
        if (draft.isEmpty()) return@LaunchedEffect
        val pending = session.echoPending
        if (pending == 0L) return@LaunchedEffect
        delay(700)
        if (session.echoPending == pending) session.markHiddenInput()
    }

    // The lading beat: while this session's watch has anything to show, tick it —
    // it gleans the bottom rows only when new output has landed, and every end
    // (finished, ^C, alt screen, a drop) is the watch's to call, not this loop's.
    // Keyed on the session as well as the awake flag: a loop keyed on the flag
    // alone kept beating for the tab you had just left.
    val cargoAwake by session.cargoAwake.collectAsState()
    LaunchedEffect(session, cargoAwake) {
        if (!cargoAwake) return@LaunchedEffect
        while (session.cargoTick()) delay(200)
    }

    // Stepping off the terminal must drop the keyboard NOW, while the input view is
    // still attached — the onDispose fallback fires after the crossfade detaches it,
    // when its window token is already dead and the hide is a silent no-op.
    fun leaveTerminal(to: () -> Unit) {
        inputView?.hideKeyboard()
        to()
    }

    // The system back gesture steps ashore (the Dock) instead of killing the app;
    // every crossing stays live in the background. The ⌂ in the switcher matches.
    BackHandler { leaveTerminal(onDock) }

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
            onNewSession = { leaveTerminal(onNewSession) },
            onDock = { leaveTerminal(onDock) },
            onFiles = { leaveTerminal(onFiles) },
            onForwards = { showForwards = true },
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
                        onPaste = { session.scrollToBottom(); session.paste(it) }
                        appCursorKeys = { session.term.cursorKeysApp }
                        kittyKeyboardFlags = { session.term.kittyKeyboardFlags }
                        mode = inputMode
                    }.also { inputView = it }
                },
                // Rebind to the active session each recomposition — one input view
                // serves every tab, so a switch must re-point its callbacks or
                // keystrokes keep flowing to the session you just left.
                update = { view ->
                    view.onInput = { emit(it, it.length == 1) }
                    view.onPaste = { session.scrollToBottom(); session.paste(it) }
                    view.appCursorKeys = { session.term.cursorKeysApp }
                    view.kittyKeyboardFlags = { session.term.kittyKeyboardFlags }
                    view.secure = toll != null
                },
                modifier = Modifier.fillMaxSize().focusRequester(inputFocus),
            )
            TerminalView(
                session = session,
                modifier = Modifier.fillMaxSize(),
                fontSizeSp = fontSizeSp,
                bellRipples = bellOn,
                onRequestFocus = {
                    runCatching { inputFocus.requestFocus() }
                    inputView?.showKeyboard()
                },
                onZoom = { zoom ->
                    // Floor 6sp: tiny, but it's what fits 80 columns in portrait —
                    // full-screen TUIs (btop, htop) refuse to draw below 80×24.
                    fontSizeSp = (fontSizeSp * zoom).coerceIn(6f, 32f)
                },
                apparitions = apparitionCache,
                onApparitionTap = { lightbox = it },
                search = searchState,
                onLinkTap = { linkSighting = it },
                highlightLink = linkSighting?.linkId ?: 0,
            )

            // A shade held up to the light: full screen, pinch to look closer, gold
            // to carry it ashore. The reason to want images on a phone at all.
            lightbox?.let { shade ->
                ApparitionLightbox(
                    image = shade,
                    cache = apparitionCache,
                    onDismiss = { lightbox = null },
                )
            }

            // A link from the far shore is untrusted: never opened on a tap alone.
            // The sheet shows where it really leads, then open / copy / select.
            linkSighting?.let { link ->
                val live = state is TerminalSession.State.Connected
                LinkSheet(
                    sighting = link,
                    onSelectWords = { cell ->
                        linkSighting = null
                        session.selectWordAt(cell)
                    },
                    onDismiss = { linkSighting = null },
                    onForwardLocal = if (live) onForwardLink else null,
                    onOpenHold = if (live) onFilesAt else null,
                )
            }

            // Grid-size readout: flashes cols × rows when the width re-snaps (a
            // pinch, a rotation). The keyboard rising or falling only changes the
            // rows, which is ordinary weather, so it stays quiet. A phone's ~48
            // columns in portrait is normal: the pill is teal, gold when a full-screen
            // program is up and the grid is under the 80 × 24 most of them want, and
            // ember only when the glass is genuinely too small to work in.
            val dims by session.dims.collectAsState()
            var dimsShown by remember(session.id) { mutableStateOf(false) }
            var lastCols by remember(session.id) { mutableStateOf(-1) }
            LaunchedEffect(dims.first) {
                // The first snap is just the terminal finding its size — stay quiet.
                val first = lastCols < 0
                lastCols = dims.first
                if (first) return@LaunchedEffect
                dimsShown = true
                delay(1400)
                dimsShown = false
            }
            Column(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (dimsShown) {
                    val (c, r) = dims
                    Text(
                        "$c × $r",
                        fontFamily = CharonMono,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.background,
                        modifier = Modifier
                            .clip(Hulls.pill)
                            .background(
                                when {
                                    c < 20 || r < 6 -> Styx.ember
                                    session.term.usingAlt && (c < 80 || r < 24) -> Styx.coin
                                    else -> Styx.water
                                },
                            )
                            .semantics { contentDescription = "$c columns by $r rows" }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                tollShown?.let { TollPill(phase = it, pulse = tollPulse) }
                AnimatedVisibility(visible = keyShown) {
                    Text(
                        "⚿ your key signed onward",
                        style = MaterialTheme.typography.labelMedium,
                        color = Styx.coin,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .clip(Hulls.pill)
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, Styx.coin.copy(alpha = 0.45f), Hulls.pill)
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }

            // The dredge bar: search the wake. Sits under the switcher, over the
            // water — a field plus the sighting count and step/close pills.
            if (dredge.isNotEmpty() || dredgeFocused) {
                DredgeBar(
                    modifier = Modifier.align(Alignment.TopCenter),
                    query = dredge,
                    pattern = dredgePattern,
                    error = dredgeError,
                    hits = searchState?.hits?.size ?: 0,
                    current = (searchState?.current ?: -1) + 1,
                    onQuery = { dredge = it; dredgeFocused = true },
                    onPattern = { dredgePattern = !dredgePattern },
                    onStep = { dir ->
                        val state = searchState ?: return@DredgeBar
                        val total = state.hits.size
                        val next = ((state.current + dir) % total + total) % total
                        searchState = state.copy(current = next)
                        showHit(state.hits[next])
                    },
                    onClose = { dredge = ""; dredgeFocused = false; session.scrollToBottom() },
                )
            }

            // Anchored to the live edge: the lading strip while cargo is moving,
            // and the scrolled-back pills — ▼ live returns (typing does the same),
            // ⌕ opens the dredge, since scrolled back is where you go looking.
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // The last view stays drawable while the strip fades out.
                val cargoView = cargo?.takeIf { System.nanoTime() < it.until }
                var lastCargo by remember(session.id) { mutableStateOf<CargoWatch.View?>(null) }
                if (cargoView != null) lastCargo = cargoView
                AnimatedVisibility(visible = cargoView != null && toll == null) {
                    lastCargo?.let { CargoStrip(it) }
                }
                if (scrollOffset > 0) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "▼ live",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.background,
                            modifier = Modifier
                                .clip(Hulls.pill)
                                .background(Styx.coin)
                                .clickable(onClickLabel = "back to the live edge") { session.scrollToBottom() }
                                .semantics { contentDescription = "back to the live edge" }
                                .padding(horizontal = 18.dp, vertical = 7.dp),
                        )
                        // Prompt hops (a rigged shell's OSC 133 A marks): the command above,
                        // the command below, each landing at the top of the glass.
                        if (sounded) {
                            Text(
                                "⇡",
                                fontFamily = CharonMono,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.background,
                                modifier = Modifier
                                    .clip(Hulls.pill)
                                    .background(Styx.water)
                                    .clickable(onClickLabel = "previous prompt") { session.jumpToPrompt(older = true) }
                                    .semantics { contentDescription = "jump to the previous prompt" }
                                    .padding(horizontal = 14.dp, vertical = 7.dp),
                            )
                            Text(
                                "⇣",
                                fontFamily = CharonMono,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.background,
                                modifier = Modifier
                                    .clip(Hulls.pill)
                                    .background(Styx.water)
                                    .clickable(onClickLabel = "next prompt") { session.jumpToPrompt(older = false) }
                                    .semantics { contentDescription = "jump to the next prompt" }
                                    .padding(horizontal = 14.dp, vertical = 7.dp),
                            )
                        }
                        // Teal, like the rest of the dredge furniture.
                        Text(
                            "⌕",
                            fontFamily = CharonMono,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.background,
                            modifier = Modifier
                                .clip(Hulls.pill)
                                .background(Styx.water)
                                .clickable(onClickLabel = "search the scrollback") { dredgeFocused = true }
                                .semantics { contentDescription = "search the scrollback" }
                                .padding(horizontal = 16.dp, vertical = 7.dp),
                        )
                    }
                }
            }

            // Copy affordances while a selection holds: "all" swells the selection to
            // the whole scrollback + screen, "copy" takes it to the clipboard.
            if (selection != null) {
                Row(
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "all",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .clip(Hulls.pill)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { session.selectAll() }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "copy",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.background,
                        modifier = Modifier
                            .clip(Hulls.pill)
                            .background(Styx.water)
                            .clickable {
                                session.copySelection()?.let {
                                    clipboard.setText(AnnotatedString(it))
                                }
                                session.clearSelection()
                            }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
            // State overlays fade over the water instead of popping — keyed on the
            // kind of state (plus clean/dirty for disconnects) so an attempt counter
            // ticking up doesn't re-run the whole entrance.
            AnimatedContent(
                targetState = state,
                contentKey = { st ->
                    when (st) {
                        is TerminalSession.State.Disconnected -> "down-${st.clean}"
                        else -> st::class.simpleName ?: "?"
                    }
                },
                transitionSpec = {
                    fadeIn(tween(240)) togetherWith fadeOut(tween(240)) using
                        SizeTransform(clip = false)
                },
                label = "stateVeil",
            ) { s ->
                when (s) {
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
                    else -> Box(Modifier.fillMaxSize())
                }
            }
        }
        // One strip, two tenants: an empty line shows the rehearsed snippets; the
        // moment typing starts, smart autofill takes the stage. They never fight.
        if (suggestions.isNotEmpty() && toll == null) {
            CommandSuggestions(
                suggestions = suggestions,
                onAccept = { s ->
                    session.scrollToBottom()
                    inputView?.textLandedOutsideIme()
                    session.sendText(s.insert)
                    session.trackInput(s.insert)
                },
                onForget = { s -> commandHistory.forget(s.display) },
            )
        } else if (draft.isBlank() && toll == null && state is TerminalSession.State.Connected) {
            SnippetBar(
                snippets = snippets,
                hostId = hostId,
                onType = { cmd ->
                    session.scrollToBottom()
                    inputView?.textLandedOutsideIme()
                    session.sendText(cmd)
                    session.trackInput(cmd)
                },
                onSave = onSaveSnippet,
                onDelete = onDeleteSnippet,
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
                inputView?.textLandedOutsideIme()
                emitKey(key)
            },
            onText = {
                inputView?.textLandedOutsideIme()
                emit(it, it.length == 1)
            },
            onPaste = {
                session.scrollToBottom()
                inputView?.textLandedOutsideIme()
                clipboard.getText()?.text?.let { session.paste(it) }
            },
            onDredge = { dredgeFocused = true },
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

    clipAsking?.let { text ->
        ClipboardSheet(
            shore = session.label,
            text = text,
            onAllowOnce = {
                clipboard.setText(AnnotatedString(text))
                clipAsking = null
            },
            onAlways = {
                ClipboardConsent.set(prefs, session.label, ClipboardConsent.ALWAYS)
                clipboard.setText(AnnotatedString(text))
                clipAsking = null
            },
            onRefuse = { clipAsking = null },
            onNever = {
                ClipboardConsent.set(prefs, session.label, ClipboardConsent.NEVER)
                clipAsking = null
            },
        )
    }

    if (showForwards) {
        ForwardsSheet(
            sessionLabel = session.label,
            hostId = hostId,
            forwards = forwards,
            running = runningForwards,
            error = forwardError,
            onToggle = onToggleForward,
            onSave = onSaveForward,
            onDelete = onDeleteForward,
            onDismiss = { showForwards = false },
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
    onDock: () -> Unit,
    onFiles: () -> Unit,
    onForwards: () -> Unit,
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
            // Back to shore: the Dock, with every crossing left running at sea.
            Box(
                modifier = Modifier
                    .clip(Hulls.chip)
                    .clickable(onClickLabel = "back to the dock", onClick = onDock)
                    .semantics { contentDescription = "back to the dock" }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    "⌂",
                    style = MaterialTheme.typography.titleMedium,
                    color = Styx.mist,
                )
            }
            Spacer(Modifier.width(4.dp))
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
                    .clip(Hulls.chip)
                    .clickable(onClickLabel = "new crossing", onClick = onNewSession)
                    .semantics { contentDescription = "new crossing" }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    "+",
                    style = MaterialTheme.typography.titleMedium,
                    color = Styx.water,
                )
            }
            // The hold: this session's SFTP deck.
            Box(
                modifier = Modifier
                    .clip(Hulls.chip)
                    .clickable(onClickLabel = "open the hold", onClick = onFiles)
                    .semantics { contentDescription = "the hold: this crossing's files" }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    "⇅",
                    style = MaterialTheme.typography.titleMedium,
                    color = Styx.coin,
                )
            }
            // Charted channels: this session's port forwards.
            Box(
                modifier = Modifier
                    .clip(Hulls.chip)
                    .clickable(onClickLabel = "chart channels", onClick = onForwards)
                    .semantics { contentDescription = "charted channels: port forwards" }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    "⇆",
                    style = MaterialTheme.typography.titleMedium,
                    color = Styx.mist,
                )
            }
        }
        Waterline()
    }
}

/**
 * The waterline: where the tab row meets the water, a slow teal ripple stands in for
 * the old hairline divider. Amplitude is under 2dp and the drift takes seven seconds
 * — it reads as a plain rule until you look, which is the point.
 */
@Composable
private fun Waterline() {
    val phase by rememberInfiniteTransition(label = "waterline").animateFloat(
        initialValue = 0f,
        targetValue = TWO_PI,
        animationSpec = infiniteRepeatable(
            animation = tween(7000, easing = LinearEasing),
        ),
        label = "waterlinePhase",
    )
    val still = MaterialTheme.colorScheme.surfaceVariant
    val ripple = Styx.water.copy(alpha = 0.30f)
    // One Path for the ripple's whole life — this draws every frame forever, and a
    // fresh allocation per frame is pure garbage-collector chum.
    val path = remember { Path() }
    Canvas(Modifier.fillMaxWidth().height(4.dp)) {
        val mid = size.height / 2f
        val amp = size.height * 0.32f
        val wavelength = 26.dp.toPx()
        val step = 3.dp.toPx()
        path.reset()
        var x = 0f
        path.moveTo(0f, mid + amp * sin(-phase))
        while (x < size.width + step) {
            path.lineTo(x, mid + amp * sin(x / wavelength * TWO_PI - phase))
            x += step
        }
        // The still base keeps the rule legible; the teal ripple breathes over it.
        drawPath(path, still, style = Stroke(width = 1.dp.toPx()))
        drawPath(path, ripple, style = Stroke(width = 1.dp.toPx()))
    }
}

private const val TWO_PI = (2 * Math.PI).toFloat()

/** One ferry in the switcher: a breathing state dot, its label, and a close ×. */
@Composable
private fun SessionTab(
    session: TerminalSession,
    active: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    val state by session.state.collectAsState()
    // A command done or a call in a tab you aren't on: its dot flashes gold (ashore,
    // a call) or ember (aground), then keeps that hue until you step aboard.
    val signal by session.signal.collectAsState()
    var unseen by remember(session.id) { mutableStateOf<TerminalSession.SignalKind?>(null) }
    val flash = remember(session.id) { Animatable(1f) }
    val firstSignal = remember(session.id) { signal?.seq }
    LaunchedEffect(signal?.seq) {
        val sig = signal ?: return@LaunchedEffect
        if (active || sig.seq == firstSignal) return@LaunchedEffect
        unseen = sig.kind
        repeat(3) {
            flash.animateTo(0.2f, tween(160))
            flash.animateTo(1f, tween(240))
        }
    }
    LaunchedEffect(active) { if (active) unseen = null }
    // The remote's window title when it speaks one (tmux `set-titles on` keeps it at
    // the current window's name), else the mooring's user@host.
    val title by session.title.collectAsState()
    val dotColor by animateColorAsState(
        targetValue = when {
            unseen == TerminalSession.SignalKind.AGROUND -> Styx.ember
            unseen != null -> Styx.coin
            else -> when (state) {
            is TerminalSession.State.Connected -> Styx.water
            is TerminalSession.State.Connecting -> Styx.coin
            is TerminalSession.State.Reconnecting -> Styx.coin
            is TerminalSession.State.Disconnected -> Styx.ember
            }
        },
        animationSpec = tween(400),
        label = "tabDot",
    )
    // Breathe while connected; pulse while redialing — anything settled holds steady.
    // The infinite transition only exists while a dot actually breathes: idle tabs
    // must not each keep an always-running animation invalidating frames.
    val dotAlpha = when (state) {
        is TerminalSession.State.Connected, is TerminalSession.State.Reconnecting -> {
            val breathe by rememberInfiniteTransition(label = "breathe").animateFloat(
                initialValue = 0.45f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1300, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "breatheAlpha",
            )
            breathe
        }
        else -> 1f
    }

    Row(
        modifier = Modifier
            .clip(Hulls.chip)
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
                .graphicsLayer { alpha = dotAlpha * flash.value }
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            title.trim().ifEmpty { session.label },
            style = MaterialTheme.typography.labelMedium,
            color = if (active) MaterialTheme.colorScheme.onSurface else Styx.mist,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 160.dp),
        )
        // Closing a live crossing takes two taps: the first arms the × (ember,
        // "close?"), the second ends it; left alone it disarms after three seconds.
        // A crossing already adrift closes on one. The target is a full 40dp.
        val live = state is TerminalSession.State.Connected || state is TerminalSession.State.Reconnecting
        var armed by remember(session.id) { mutableStateOf(false) }
        LaunchedEffect(armed) {
            if (armed) {
                delay(3000)
                armed = false
            }
        }
        val haptic = LocalHapticFeedback.current
        Box(
            modifier = Modifier
                .sizeIn(minWidth = 40.dp, minHeight = 40.dp)
                .clip(CircleShape)
                .clickable(onClickLabel = if (armed) "end this crossing" else "close") {
                    if (!live || armed) {
                        armed = false
                        onClose()
                    } else {
                        armed = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                }
                .semantics {
                    contentDescription = if (armed) "tap again to end ${session.label}" else "close ${session.label}"
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (armed) "close?" else "×",
                style = if (armed) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium,
                color = if (armed) Styx.ember else Styx.mist,
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
    onForget: (Suggestion) -> Unit,
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
            SuggestionChipPill(
                suggestion = s,
                onClick = { onAccept(s) },
                // Only remembered lines can be forgotten; grammar and live host
                // offers aren't memory, so a long-press means nothing on them.
                onLongClick = if (s.fromHistory) {
                    { onForget(s) }
                } else {
                    null
                },
            )
            Spacer(Modifier.width(8.dp))
        }
    }
}

/** One autofill offer: a » sigil, the already-typed part dimmed, the rest in teal.
 *  History chips answer a long-press by being forgotten. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SuggestionChipPill(
    suggestion: Suggestion,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val haptic = LocalHapticFeedback.current
    val label = buildAnnotatedString {
        withStyle(SpanStyle(color = Styx.mist)) { append(suggestion.display.take(suggestion.matched)) }
        withStyle(SpanStyle(color = Styx.water, fontWeight = FontWeight.Medium)) {
            append(suggestion.display.substring(suggestion.matched))
        }
    }
    Row(
        modifier = Modifier
            .clip(Hulls.chip)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick?.let {
                    {
                        haptic.performHapticFeedback(HapticFeedbackType.Reject)
                        it()
                    }
                },
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("»", style = MaterialTheme.typography.bodyMedium, color = Styx.coin)
        Spacer(Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
    }
}

// ---- The toll & the lading ----------------------------------------------------------

/**
 * Raised while the remote reads a secret. The obol flips edge-on and back once per
 * hidden keystroke — acknowledgement without a length gauge — and the pill turns
 * gold "the toll is paid" on Enter. While it stands, nothing typed touches the
 * autofill draft, the command history, or the IME's dictionary.
 */
@Composable
private fun TollPill(phase: TerminalSession.TollPhase, pulse: Int) {
    val haptic = LocalHapticFeedback.current
    val paid = phase == TerminalSession.TollPhase.PAID
    val flip = remember { Animatable(0f) }
    LaunchedEffect(pulse) {
        if (pulse > 0) {
            flip.snapTo(0f)
            flip.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
        }
    }
    LaunchedEffect(paid) {
        if (paid) haptic.performHapticFeedback(HapticFeedbackType.Confirm)
    }
    val breathe by rememberInfiniteTransition(label = "tollBreathe").animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "tollGlow",
    )
    Row(
        modifier = Modifier
            .clip(Hulls.pill)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, Styx.coin.copy(alpha = 0.45f), Hulls.pill)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .graphicsLayer {
                    scaleX = abs(cos(flip.value * Math.PI)).toFloat()
                    alpha = if (paid) 1f else breathe
                }
                .clip(CircleShape)
                .background(Styx.coin),
        )
        Spacer(Modifier.width(9.dp))
        Text(
            if (paid) "the toll is paid" else "the ferryman asks the toll",
            style = MaterialTheme.typography.labelMedium,
            color = if (paid) Styx.coin else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The dredge bar: search over the wake. A small field over the water with step
 * pills (older/newer sighting) and a release. Sits under the switcher; the count
 * line reads total sightings and which one the eye is on.
 */
@Composable
private fun DredgeBar(
    query: String,
    pattern: Boolean,
    error: String?,
    hits: Int,
    current: Int,
    onQuery: (String) -> Unit,
    onPattern: () -> Unit,
    onStep: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Opened from a pill or a key, the bar is the thing you want to type into —
    // so it asks for the caret itself rather than making you tap it.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(
        modifier = modifier.padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The livery's own field: a dark slip of water with a teal rim and caret,
            // the query in the terminal's mono — not a stock form field.
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = CharonMono,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(Styx.water),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSearch = { onStep(-1) }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus)
                    .semantics { contentDescription = "dredge the wake: search the scrollback" },
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .clip(Hulls.pill)
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, if (error != null) Styx.ember else Styx.water.copy(alpha = 0.6f), Hulls.pill)
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                    ) {
                        if (query.isEmpty()) {
                            Text(
                                "dredge the wake",
                                fontFamily = CharonMono,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Styx.mist,
                            )
                        }
                        inner()
                    }
                },
            )
            DredgePill(".*", "pattern search ${if (pattern) "on" else "off"}", lit = pattern) { onPattern() }
            DredgePill("▲", "older sighting") { onStep(-1) }
            DredgePill("▼", "newer sighting") { onStep(1) }
            DredgePill("✕", "close the dredge") { onClose() }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                error != null -> error
                query.isEmpty() || hits == 0 -> "no sightings"
                hits >= SearchEngine.MAX_HITS -> "$current of ${hits}+"
                else -> "$current of $hits"
            },
            fontFamily = CharonMono,
            style = MaterialTheme.typography.labelMedium,
            color = when {
                error != null -> Styx.ember
                hits > 0 -> Styx.water
                else -> Styx.mist
            },
        )
    }
}

/** One pill of the dredge bar; [lit] = false draws it hollow (a toggle that's off). */
@Composable
private fun DredgePill(label: String, description: String, lit: Boolean = true, onClick: () -> Unit) {
    Text(
        label,
        fontFamily = CharonMono,
        style = MaterialTheme.typography.labelLarge,
        color = if (lit) MaterialTheme.colorScheme.background else Styx.water,
        modifier = Modifier
            .clip(Hulls.pill)
            .background(if (lit) Styx.water else Color.Transparent)
            .border(1.dp, Styx.water, Hulls.pill)
            .clickable(onClickLabel = description) { onClick() }
            .semantics { contentDescription = description }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/** How often an open dredge re-reads the water while output lands. */
private const val DREDGE_REFRESH_MS = 300L

/**
 * The lading strip: while a package manager is hauling, a laden barge crosses a
 * braille waterline — steered by the manager's own percent when one is on screen,
 * patrolling when not — with the package under hand named beneath. The water only
 * moves while cargo does: a quiet stretch stills it, and the ends are moored —
 * docked at the far bank in gold ("cargo ashore"), or run aground in ember with
 * the exit code.
 */
@Composable
private fun CargoStrip(view: CargoWatch.View) {
    val moving = !view.still && view.phase == CargoWatch.Phase.SAILING
    // The infinite transitions exist only while cargo is moving — a still strip
    // must not keep the frame clock running.
    val drift = if (moving) {
        val d by rememberInfiniteTransition(label = "cargoWater").animateFloat(
            initialValue = 0f,
            targetValue = WATER_FRAMES.length.toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(WATER_FRAMES.length * 260, easing = LinearEasing),
            ),
            label = "cargoDrift",
        )
        d
    } else 0f
    val patrol = if (moving && view.percent == null) {
        val p by rememberInfiniteTransition(label = "cargoPatrol").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(4200, easing = LinearEasing),
            ),
            label = "cargoPatrolFrac",
        )
        p
    } else 0.5f
    val aground = view.phase == CargoWatch.Phase.AGROUND
    val ashore = view.phase == CargoWatch.Phase.ASHORE
    val target = when {
        ashore -> 1f
        view.percent != null -> view.percent / 100f
        else -> patrol
    }
    // The barge glides to each new percent rather than jumping cell to cell.
    val frac by animateFloatAsState(target, tween(if (moving) 260 else 600), label = "bargeAt")
    val span = CARGO_WATER_WIDTH - BARGE.length
    val bargeAt = (span * (if (view.percent == null && moving) patrol else frac)).toInt().coerceIn(0, span)
    val hull = if (aground) Styx.ember else Styx.coin
    val water = buildAnnotatedString {
        for (i in 0 until CARGO_WATER_WIDTH) {
            if (i >= bargeAt && i < bargeAt + BARGE.length) {
                withStyle(SpanStyle(color = hull)) { append(BARGE[i - bargeAt]) }
            } else {
                withStyle(SpanStyle(color = Styx.water.copy(alpha = if (moving) 0.55f else 0.3f))) {
                    append(WATER_FRAMES[(i + drift.toInt()) % WATER_FRAMES.length])
                }
            }
        }
    }
    Column(
        modifier = Modifier
            .clip(Hulls.card)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(water, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        Spacer(Modifier.height(3.dp))
        val head = buildString {
            append(
                when {
                    ashore -> "cargo ashore"
                    aground -> "ran aground"
                    else -> "lading the hold"
                },
            )
            view.item?.let { append(" — ").append(it.take(28)) }
        }
        val label = buildAnnotatedString {
            withStyle(SpanStyle(color = if (aground) Styx.ember else Styx.mist)) { append(head) }
            when {
                aground && view.exit != null -> {
                    append("  ")
                    withStyle(SpanStyle(color = Styx.ember, fontWeight = FontWeight.Medium)) {
                        append("exit ${view.exit}")
                    }
                }
                view.percent != null && !aground -> {
                    append("  ")
                    withStyle(SpanStyle(color = Styx.coin, fontWeight = FontWeight.Medium)) {
                        append("${view.percent}%")
                    }
                }
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/** Ripples for the lading's waterline, one frame per character. */
private const val WATER_FRAMES = "⠈⠐⠠⢀⡀⠄⠂⠁"

/** The laden barge, riding low in the water. */
private const val BARGE = "⣼⣿⣧"

private const val CARGO_WATER_WIDTH = 24

// ---- Session-state overlays -------------------------------------------------------

/**
 * A slim bottom pill for the "crossing the Styx…" cold-connect beat, with a braille
 * oar turning beside the words — the same sea-script the ferry sails on.
 */
@Composable
private fun ConnectingPill(text: String) {
    val phase by rememberInfiniteTransition(label = "styxOar").animateFloat(
        initialValue = 0f,
        targetValue = OAR_FRAMES.length.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(OAR_FRAMES.length * 90, easing = LinearEasing),
        ),
        label = "oar",
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Row(
            modifier = Modifier
                .padding(bottom = 16.dp)
                .clip(Hulls.pill)
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                OAR_FRAMES[phase.toInt() % OAR_FRAMES.length].toString(),
                fontFamily = CharonMono,
                style = MaterialTheme.typography.bodyMedium,
                color = Styx.water,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Braille strokes of a turning oar, one frame per character. */
private const val OAR_FRAMES = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"

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
            .background(Styx.night.copy(alpha = 0.82f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .graphicsLayer { alpha = pulse }
                    .clip(CircleShape)
                    .background(Styx.coin),
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
                color = Styx.mist,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onGiveUp) { Text("give up", color = Styx.mist) }
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
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(Unit) { haptic.performHapticFeedback(HapticFeedbackType.Reject) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Styx.night.copy(alpha = 0.82f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .clip(Hulls.card)
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "the crossing failed",
                style = MaterialTheme.typography.titleLarge,
                color = Styx.ember,
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
    val haptic = LocalHapticFeedback.current
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        appeared = true
    }
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
        modifier = Modifier.fillMaxSize().background(Styx.night),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "returned to shore",
                style = MaterialTheme.typography.titleLarge,
                color = Styx.water,
                modifier = Modifier.graphicsLayer { alpha = fade },
            )
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .height(2.dp)
                    .width((168 * sweep).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(Styx.coin),
            )
        }
    }
}
