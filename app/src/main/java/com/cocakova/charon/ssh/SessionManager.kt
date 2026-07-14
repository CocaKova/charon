package com.cocakova.charon.ssh

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.cocakova.charon.data.db.HostDao
import com.cocakova.charon.data.db.KnownHostDao
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
    }

    private val managed = ConcurrentHashMap<String, Managed>()

    val sessions = MutableStateFlow<List<TerminalSession>>(emptyList())
    val activeSession = MutableStateFlow<TerminalSession?>(null)
    private val activeId = MutableStateFlow<String?>(null)
    val lastError = MutableStateFlow<String?>(null)

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
                if (!ms.closed && ms.config.autoReconnect && ms.everConnected &&
                    ms.session.state.value is TerminalSession.State.Disconnected
                ) {
                    ms.retries = 0
                    reconnectNow(ms)
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

    // ---- connect / reconnect plumbing ---------------------------------------------

    private fun launchConnect(ms: Managed, firstAttempt: Boolean) {
        ms.session.state.value = TerminalSession.State.Connecting
        scope.launch {
            try {
                ms.connection = engine.connectShell(ms.config, ms.session, verifier)
                ms.hostId?.let { hostDao.touchConnected(it, System.currentTimeMillis()) }
                updateService()
            } catch (e: Exception) {
                if (firstAttempt) lastError.value = e.message ?: e.javaClass.simpleName
                // Flip to Disconnected so the watcher decides on a redial.
                ms.session.state.value =
                    TerminalSession.State.Disconnected(e.message ?: "connect failed")
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
                    }
                    is TerminalSession.State.Disconnected ->
                        if (!ms.closed && ms.config.autoReconnect && ms.everConnected) {
                            scheduleReconnect(ms)
                        }
                    else -> {}
                }
                updateService()
            }
        }
    }

    private fun scheduleReconnect(ms: Managed) {
        if (ms.closed) return
        if (ms.reconnectJob?.isActive == true) return
        ms.reconnectJob = scope.launch {
            // 1s, 2s, 4s … capped at 120s. The network callback can pre-empt this.
            val backoff = (1_000L shl ms.retries.coerceAtMost(7)).coerceAtMost(120_000L)
            delay(backoff)
            ms.retries++
            reconnectNow(ms)
        }
    }

    private fun reconnectNow(ms: Managed) {
        if (ms.closed) return
        ms.reconnectJob?.cancel()
        launchConnect(ms, firstAttempt = false)
    }

    /** Carry a public key to a host using its current password authentication. */
    suspend fun grantPassage(config: ConnectConfig, publicLine: String) {
        withContext(Dispatchers.IO) {
            engine.installPublicKey(config, publicLine, verifier)
        }
    }

    // ---- helpers -------------------------------------------------------------------

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
