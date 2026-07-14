package com.cocakova.charon.ssh

import android.content.Context
import com.cocakova.charon.data.db.HostDao
import com.cocakova.charon.data.db.KnownHostDao
import com.cocakova.charon.service.ConnectionService
import com.cocakova.charon.theme.TerminalSchemes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * App-scoped owner of live sessions (Keryx-style manual DI: constructed once in
 * CharonApp). The ConnectionService pins the process while any session lives; this
 * class owns the actual connections and the trust-decision bridge.
 *
 * v0.2: one session at a time. The tabs milestone (v0.5) turns these fields into
 * collections.
 */
class SessionManager(
    private val appContext: Context,
    private val hostDao: HostDao,
    knownHostDao: KnownHostDao,
    private val engine: SshEngine = SshjEngine(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val activeSession = MutableStateFlow<TerminalSession?>(null)
    val lastError = MutableStateFlow<String?>(null)

    /** Set while the ferryman waits on a trust decision; the UI resolves it. */
    val pendingTrust = MutableStateFlow<PendingTrust?>(null)

    private var connection: SshConnection? = null

    private val verifier = KnownHostsVerifier(knownHostDao) { request ->
        // Called on the connect thread: park it until the user decides.
        val pending = PendingTrust(request)
        pendingTrust.value = pending
        try {
            runBlocking { pending.decision.await() }
        } finally {
            pendingTrust.value = null
        }
    }

    fun connect(config: ConnectConfig, hostId: String? = null) {
        val scheme = TerminalSchemes.STYX
        val session = TerminalSession(
            label = "${config.username}@${config.host}",
            basePalette = scheme.ansi16,
            initialFg = scheme.fg,
            initialBg = scheme.bg,
        )
        lastError.value = null
        activeSession.value = session
        scope.launch {
            try {
                connection = engine.connectShell(config, session, verifier)
                ConnectionService.start(appContext, session.label)
                hostId?.let { hostDao.touchConnected(it, System.currentTimeMillis()) }
                // Reflect transport death in service lifecycle.
                session.state.collect { state ->
                    if (state is TerminalSession.State.Disconnected) {
                        ConnectionService.stop(appContext)
                    }
                }
            } catch (e: Exception) {
                lastError.value = e.message ?: e.javaClass.simpleName
                session.state.value =
                    TerminalSession.State.Disconnected(e.message ?: "connect failed")
                activeSession.value = null
                ConnectionService.stop(appContext)
            }
        }
    }

    fun disconnect() {
        val doomed = connection
        connection = null
        activeSession.value = null
        // Teardown is network I/O too — never on the caller's (UI) thread.
        scope.launch { doomed?.disconnect() }
        ConnectionService.stop(appContext)
    }

    /** Back out of a dead session's terminal to the Dock. */
    fun dismissSession() {
        activeSession.value = null
    }
}
