package com.cocakova.charon

import android.content.Intent
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import com.cocakova.charon.data.repository.HostDraft
import com.cocakova.charon.data.repository.HostVault
import com.cocakova.charon.presentation.dock.DockScreen
import com.cocakova.charon.presentation.dock.TrustGate
import com.cocakova.charon.presentation.terminal.TerminalScreen
import com.cocakova.charon.ssh.ConnectConfig
import com.cocakova.charon.ssh.SessionManager
import com.cocakova.charon.ssh.TerminalSession
import com.cocakova.charon.theme.CharonTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as CharonApp
        if (BuildConfig.DEBUG) maybeDebugConnect(intent, app.sessionManager)
        setContent {
            CharonTheme {
                CharonRoot(app.sessionManager, app.hostVault)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Repeated adb `am start` lands here, not onCreate.
        if (BuildConfig.DEBUG) maybeDebugConnect(intent, (application as CharonApp).sessionManager)
    }

    /**
     * Debug-build-only adb hook so milestone demo gates can be driven headlessly:
     *   adb shell am start -n com.cocakova.charon/.MainActivity \
     *     --es debug_host spark --es debug_user me --es debug_key_b64 "$(base64 -w0 key)"
     * Compiled out of release builds by the BuildConfig gate at the call site.
     */
    private fun maybeDebugConnect(intent: Intent?, sessionManager: SessionManager) {
        val host = intent?.getStringExtra("debug_host") ?: return
        val user = intent.getStringExtra("debug_user") ?: return
        val keyPem = intent.getStringExtra("debug_key_b64")
            ?.let { String(Base64.decode(it, Base64.DEFAULT)) }
        val password = intent.getStringExtra("debug_password")
        if (keyPem == null && password == null) return
        sessionManager.connect(
            ConnectConfig(
                host = host,
                port = intent.getIntExtra("debug_port", 22),
                username = user,
                password = password,
                privateKeyPem = keyPem,
            ),
        )
    }
}

@Composable
private fun CharonRoot(sessionManager: SessionManager, hostVault: HostVault) {
    val session by sessionManager.activeSession.collectAsState()
    val error by sessionManager.lastError.collectAsState()
    val pendingTrust by sessionManager.pendingTrust.collectAsState()
    val hosts by hostVault.hosts.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    val current = session
    val state = current?.let { it.state.collectAsState().value }
    val showTerminal = current != null && state !is TerminalSession.State.Connecting

    fun draftConfig(draft: HostDraft) = ConnectConfig(
        host = draft.host.trim(),
        port = draft.port,
        username = draft.username.trim(),
        password = draft.password.ifBlank { null },
    )

    // One crossfade for the whole crossing: the Dock -> terminal and back.
    Crossfade(
        targetState = showTerminal,
        animationSpec = tween(durationMillis = 500),
        label = "crossing",
    ) { terminal ->
        if (terminal && current != null) {
            TerminalScreen(
                session = current,
                onDismiss = { sessionManager.dismissSession() },
            )
        } else {
            DockScreen(
                hosts = hosts,
                connecting = current != null,
                error = error,
                onConnect = { host ->
                    scope.launch {
                        sessionManager.connect(hostVault.connectConfig(host), hostId = host.id)
                    }
                },
                onQuickConnect = { draft ->
                    // Blank password on an existing mooring means "use the stored one".
                    val stored = hosts.find { it.id == draft.id }
                    if (draft.password.isBlank() && stored?.passwordSealed != null) {
                        scope.launch {
                            sessionManager.connect(hostVault.connectConfig(stored), hostId = stored.id)
                        }
                    } else {
                        sessionManager.connect(draftConfig(draft), hostId = draft.id)
                    }
                },
                onSave = { draft -> scope.launch { hostVault.save(draft) } },
                onDelete = { id -> scope.launch { hostVault.delete(id) } },
            )
        }
    }

    pendingTrust?.let { TrustGate(it) }
}
