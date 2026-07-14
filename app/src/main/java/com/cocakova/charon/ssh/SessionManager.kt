package com.cocakova.charon.ssh

import android.content.Context
import com.cocakova.charon.service.ConnectionService
import com.cocakova.charon.theme.TerminalSchemes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * App-scoped owner of live sessions (Keryx-style manual DI: constructed once in
 * CharonApp). The ConnectionService pins the process while any session lives; this
 * class owns the actual connections.
 *
 * v0.1: one session at a time. The tabs milestone (v0.5) turns these fields into
 * collections.
 */
class SessionManager(
    private val appContext: Context,
    private val engine: SshEngine = SshjEngine(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val activeSession = MutableStateFlow<TerminalSession?>(null)
    val lastError = MutableStateFlow<String?>(null)

    private var connection: SshConnection? = null

    fun connect(config: ConnectConfig) {
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
                connection = engine.connectShell(config, session)
                ConnectionService.start(appContext, session.label)
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
        connection?.disconnect()
        connection = null
        activeSession.value = null
        ConnectionService.stop(appContext)
    }

    /** Back out of a dead session's terminal to the connect screen. */
    fun dismissSession() {
        activeSession.value = null
    }
}
