package com.cocakova.charon.ssh

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.cocakova.charon.autocomplete.CommandGate
import com.cocakova.charon.autocomplete.RemoteContext
import com.cocakova.charon.data.db.HostDao
import com.cocakova.charon.data.db.KnownHostDao
import com.cocakova.charon.data.db.PortForwardDao
import com.cocakova.charon.data.db.PortForwardEntity
import com.cocakova.charon.data.repository.CommandHistory
import com.cocakova.charon.service.ConnectionService
import com.cocakova.charon.theme.TerminalSchemes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * App-scoped owner of live sessions (Keryx-style manual DI: constructed once in
 * CharonApp). The ConnectionService pins the process while any session lives; this
 * class owns the actual connections and the trust-decision bridge.
 *
 * v0.5: N sessions at once. [sessions] is the fleet at sea; [activeSession] is the
 * one on screen (null = the Dock is showing, sessions still alive in the background).
 * Each crossing that dies un-asked redials on a capped backoff, and every session
 * gets an instant redial the moment the network returns (airplane-mode toggle).
 */
class SessionManager(
    private val appContext: Context,
    private val hostDao: HostDao,
    knownHostDao: KnownHostDao,
    private val commandHistory: CommandHistory,
    private val portForwardDao: PortForwardDao,
    private val engine: SshEngine = SshjEngine(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Everything a live session drags behind it: transport, its config for redial, watchers. */
    private class Managed(
        val session: TerminalSession,
        val config: ConnectConfig,
        val hostId: String?,
    ) {
        @Volatile var connection: SshConnection? = null
        @Volatile var closed: Boolean = false
        @Volatile var retries: Int = 0
        /** True once the crossing has stood up at least once — the gate for redialing.
         *  A first attempt that fails (bad password, wrong host) must NOT loop forever. */
        @Volatile var everConnected: Boolean = false
        var watchJob: Job? = null
        var reconnectJob: Job? = null
        /** Live host knowledge for smart autofill; probes ride this session's transport. */
        var remote: RemoteContext? = null
        /** Charted channels currently open on this crossing: forwardId → handle. */
        val forwards = ConcurrentHashMap<String, ForwardHandle>()
    }

    private val managed = ConcurrentHashMap<String, Managed>()

    val sessions = MutableStateFlow<List<TerminalSession>>(emptyList())
    val activeSession = MutableStateFlow<TerminalSession?>(null)
    private val activeId = MutableStateFlow<String?>(null)
    val lastError = MutableStateFlow<String?>(null)

    /** Forward-ids of every charted channel currently open, across all crossings. */
    val runningForwards = MutableStateFlow<Set<String>>(emptySet())

    /** Last channel failure (port taken, refused) — shown inside the channels sheet. */
    val forwardError = MutableStateFlow<String?>(null)

    /** Set while the ferryman waits on a trust decision; the UI resolves it. */
    val pendingTrust = MutableStateFlow<PendingTrust?>(null)

    private val verifier = KnownHostsVerifier(knownHostDao) { request ->
        // Called on a connect thread: park it until the user decides.
        val pending = PendingTrust(request)
        pendingTrust.value = pending
        try {
            runBlocking { pending.decision.await() }
        } finally {
            pendingTrust.value = null
        }
    }

    init {
        // Instant redial when connectivity returns — the airplane-mode demo gate. Any
        // session that's down and still wanted gets its backoff cut short.
        val cm = appContext.getSystemService(ConnectivityManager::class.java)
        runCatching {
            cm?.registerDefaultNetworkCallback(networkCallback)
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            managed.values.forEach { ms ->
                // A session mid-redial (or one that dropped hard) gets its backoff cut
                // short the instant the network returns — the airplane-mode gate.
                val st = ms.session.state.value
                val down = st is TerminalSession.State.Reconnecting ||
                    (st is TerminalSession.State.Disconnected && !st.clean)
                if (!ms.closed && ms.config.autoReconnect && ms.everConnected && down) {
                    ms.reconnectJob?.cancel()
                    ms.retries = 0
                    ms.session.state.value = TerminalSession.State.Reconnecting(1)
                    launchConnect(ms, firstAttempt = false)
                }
            }
        }
    }

    fun connect(config: ConnectConfig, hostId: String? = null) {
        val scheme = TerminalSchemes.STYX
        val session = TerminalSession(
            label = displayLabel(config),
            basePalette = scheme.ansi16,
            initialFg = scheme.fg,
            initialBg = scheme.bg,
        )
        val ms = Managed(session, config, hostId)
        ms.remote = RemoteContext(scope) { cmd -> ms.connection?.exec(cmd) }
        // Only genuine command lines reach the shared history: a sentence typed into
        // a chat or REPL running on the host must never resurface as autofill.
        session.onCommandSubmitted = { line ->
            if (CommandGate.isCommandLine(line, ms.remote?.commandSet.orEmpty())) {
                commandHistory.record(line)
            }
        }
        managed[session.id] = ms
        lastError.value = null
        sessions.update { it + session }
        activeId.value = session.id
        refreshActive()
        watch(ms)
        launchConnect(ms, firstAttempt = true)
    }

    /** Show a specific live session. */
    fun switchTo(id: String) {
        if (managed.containsKey(id)) {
            activeId.value = id
            refreshActive()
        }
    }

    /** Drop to the Dock without closing anything — the "+ new crossing" path. */
    fun showDock() {
        activeId.value = null
        refreshActive()
    }

    /** Close one session for good: cancel redials, drop the transport, drop the tab. */
    fun close(id: String) {
        val ms = managed.remove(id) ?: return
        ms.closed = true
        ms.reconnectJob?.cancel()
        ms.watchJob?.cancel()
        dropForwards(ms)
        val doomed = ms.connection
        ms.connection = null
        sessions.update { list -> list.filterNot { it.id == id } }
        if (activeId.value == id) activeId.value = sessions.value.lastOrNull()?.id
        refreshActive()
        scope.launch { runCatching { doomed?.disconnect() } }
        updateService()
    }

    /** Close every session — the notification's disconnect-all. */
    fun closeAll() {
        managed.keys.toList().forEach { close(it) }
    }

    /** Manual re-cross from the "the crossing failed" overlay — ignores backoff. */
    fun forceReconnect(id: String) {
        val ms = managed[id] ?: return
        if (ms.closed) return
        ms.reconnectJob?.cancel()
        ms.retries = 0
        ms.session.state.value = TerminalSession.State.Reconnecting(1)
        launchConnect(ms, firstAttempt = false)
    }

    // ---- connect / reconnect plumbing ---------------------------------------------

    private fun launchConnect(ms: Managed, firstAttempt: Boolean) {
        // A redial is already wearing the Reconnecting state; only a cold connect
        // announces Connecting (the "crossing the Styx…" beat).
        if (firstAttempt) ms.session.state.value = TerminalSession.State.Connecting
        scope.launch {
            try {
                ms.connection = engine.connectShell(ms.config, ms.session, verifier)
                ms.hostId?.let { hostDao.touchConnected(it, System.currentTimeMillis()) }
                // Auto-start charted channels here, not in the watcher — the state
                // flips Connected inside connectShell, before ms.connection is set.
                autoStartForwards(ms)
                updateService()
            } catch (e: Exception) {
                if (firstAttempt) lastError.value = e.message ?: e.javaClass.simpleName
                // A failed connect is never a clean end — the watcher decides on a redial.
                ms.session.state.value =
                    TerminalSession.State.Disconnected(e.message ?: "connect failed", clean = false)
            }
        }
    }

    /** One collector per session for its whole life: drives redials and the service. */
    private fun watch(ms: Managed) {
        ms.watchJob = scope.launch {
            ms.session.state.collect { state ->
                when (state) {
                    is TerminalSession.State.Connected -> {
                        ms.retries = 0
                        ms.everConnected = true
                        // Inventory the host for autofill on every crossing (PATH can
                        // change between redials; the probe is one cheap exec).
                        ms.remote?.refreshCommands()
                    }
                    is TerminalSession.State.Disconnected -> {
                        // The channels died with the transport; drop the handles so a
                        // redial can chart them fresh.
                        dropForwards(ms)
                        // Redial only a true drop of an established crossing — never a
                        // clean `exit`, never a never-connected first attempt.
                        if (!ms.closed && !state.clean &&
                            ms.config.autoReconnect && ms.everConnected
                        ) {
                            beginReconnect(ms)
                        }
                    }
                    else -> {}
                }
                updateService()
            }
        }
    }

    private fun beginReconnect(ms: Managed) {
        if (ms.closed) return
        if (ms.reconnectJob?.isActive == true) return
        ms.session.state.value = TerminalSession.State.Reconnecting(ms.retries + 1)
        ms.reconnectJob = scope.launch {
            // 1s, 2s, 4s … capped at 120s. The network callback can pre-empt this.
            val backoff = (1_000L shl ms.retries.coerceAtMost(7)).coerceAtMost(120_000L)
            delay(backoff)
            ms.retries++
            ms.session.state.value = TerminalSession.State.Reconnecting(ms.retries)
            launchConnect(ms, firstAttempt = false)
        }
    }

    // ---- charted channels (port forwards) --------------------------------------------

    /** Open or close one charted channel on a live crossing. */
    fun toggleForward(sessionId: String, fwd: PortForwardEntity) {
        val ms = managed[sessionId] ?: return
        val open = ms.forwards.remove(fwd.id)
        if (open != null) {
            scope.launch { runCatching { open.stop() } }
            publishForwards()
            return
        }
        scope.launch {
            try {
                val conn = ms.connection ?: error("the crossing isn't up")
                ms.forwards[fwd.id] = conn.startForward(
                    fwd.type, fwd.bindPort, fwd.targetHost, fwd.targetPort,
                )
                forwardError.value = null
            } catch (e: Exception) {
                forwardError.value = e.message ?: "the channel could not be charted"
            }
            publishForwards()
        }
    }

    /** Chart every autoStart channel of this crossing's host. Runs post-connect. */
    private suspend fun autoStartForwards(ms: Managed) {
        val hostId = ms.hostId ?: return
        val conn = ms.connection ?: return
        portForwardDao.forHost(hostId).filter { it.autoStart }.forEach { fwd ->
            if (ms.closed || ms.forwards.containsKey(fwd.id)) return@forEach
            runCatching {
                ms.forwards[fwd.id] = conn.startForward(
                    fwd.type, fwd.bindPort, fwd.targetHost, fwd.targetPort,
                )
            }.onFailure { forwardError.value = it.message }
        }
        publishForwards()
    }

    /** Stop-and-forget every channel on a crossing (transport died or session closed). */
    private fun dropForwards(ms: Managed) {
        val doomed = ms.forwards.values.toList()
        ms.forwards.clear()
        if (doomed.isNotEmpty()) {
            scope.launch { doomed.forEach { runCatching { it.stop() } } }
        }
        publishForwards()
    }

    private fun publishForwards() {
        runningForwards.value = managed.values.flatMap { it.forwards.keys }.toSet()
    }

    /** Carry a public key to a host using its current password authentication. */
    suspend fun grantPassage(config: ConnectConfig, publicLine: String) {
        withContext(Dispatchers.IO) {
            engine.installPublicKey(config, publicLine, verifier)
        }
    }

    /**
     * One command over a short-lived verified connection — the fleet import's
     * errand-runner. Failures come back as a Result so the sheet can say why.
     */
    suspend fun execOnce(config: ConnectConfig, command: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching { engine.execOnce(config, command, verifier) }
        }

    /** The live session moored to a saved host, if one is underway. */
    fun sessionForHost(hostId: String): TerminalSession? =
        managed.values.firstOrNull { it.hostId == hostId }?.session

    /**
     * Run one command over a saved host's ALREADY-LIVE transport, if a crossing to
     * it is underway — no second handshake, no fresh auth (and so no re-trust or
     * biometric re-prompt). Null when no live session holds that host; the caller
     * falls back to [execOnce]. Blocking; call off-main.
     */
    suspend fun execOnHost(hostId: String, command: String): Result<String>? {
        val connection = managed.values.firstOrNull { it.hostId == hostId }?.connection
            ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                connection.exec(command, 25)
                    ?: throw IllegalStateException("the mooring didn't answer the errand")
            }
        }
    }

    // ---- helpers -------------------------------------------------------------------

    /** The autofill host-context for a live session, or null once it's closed. */
    fun contextFor(id: String): RemoteContext? = managed[id]?.remote

    /** A fresh SFTP channel on a live session's transport. Blocking; call off-main. */
    fun openSftp(id: String): SftpChannel? = managed[id]?.connection?.openSftp()

    /** The session's display label, for chrome that outlives the object (files view). */
    fun labelFor(id: String): String? = managed[id]?.session?.label

    /** The saved-host id behind a session, or null for a quick connect. */
    fun hostIdFor(id: String): String? = managed[id]?.hostId

    private fun refreshActive() {
        activeSession.value = sessions.value.firstOrNull { it.id == activeId.value }
    }

    private fun updateService() {
        val live = sessions.value
        if (live.isEmpty()) {
            ConnectionService.stop(appContext)
        } else {
            val text = if (live.size == 1) live.first().label
            else live.joinToString("  ·  ") { it.label }
            ConnectionService.start(appContext, count = live.size, text = text)
        }
    }

    private fun displayLabel(config: ConnectConfig) = "${config.username}@${config.host}"
}
