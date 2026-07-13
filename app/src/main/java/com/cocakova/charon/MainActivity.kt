package com.cocakova.charon

import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import com.cocakova.charon.presentation.connect.QuickConnectScreen
import com.cocakova.charon.presentation.terminal.TerminalScreen
import com.cocakova.charon.ssh.ConnectConfig
import com.cocakova.charon.ssh.SessionManager
import com.cocakova.charon.ssh.TerminalSession
import com.cocakova.charon.theme.CharonTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val sessionManager = (application as CharonApp).sessionManager
        if (BuildConfig.DEBUG) maybeDebugConnect(intent, sessionManager)
        setContent {
            CharonTheme {
                CharonRoot(sessionManager)
            }
        }
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

private const val PREFS_NAME = "charon"
private const val PREF_LAST_HOST = "last_host"
private const val PREF_LAST_PORT = "last_port"
private const val PREF_LAST_USER = "last_user"

@Composable
private fun CharonRoot(sessionManager: SessionManager) {
    val session by sessionManager.activeSession.collectAsState()
    val error by sessionManager.lastError.collectAsState()
    val prefs = LocalContext.current
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val current = session
    val state = current?.let { it.state.collectAsState().value }
    val showTerminal = current != null && state !is TerminalSession.State.Connecting

    // One crossfade for the whole crossing: connect form -> terminal and back.
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
            QuickConnectScreen(
                connecting = current != null,
                error = error,
                // The hub precursor: the last crossing is remembered (never the password).
                initialHost = prefs.getString(PREF_LAST_HOST, "") ?: "",
                initialPort = prefs.getString(PREF_LAST_PORT, "22") ?: "22",
                initialUser = prefs.getString(PREF_LAST_USER, "") ?: "",
                onConnect = { config ->
                    prefs.edit()
                        .putString(PREF_LAST_HOST, config.host)
                        .putString(PREF_LAST_PORT, config.port.toString())
                        .putString(PREF_LAST_USER, config.username)
                        .apply()
                    sessionManager.connect(config)
                },
            )
        }
    }
}
