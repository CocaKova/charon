package com.cocakova.charon

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.cocakova.charon.presentation.connect.QuickConnectScreen
import com.cocakova.charon.presentation.terminal.TerminalScreen
import com.cocakova.charon.ssh.SessionManager
import com.cocakova.charon.ssh.TerminalSession
import com.cocakova.charon.theme.CharonTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sessionManager = (application as CharonApp).sessionManager
        setContent {
            CharonTheme {
                CharonRoot(sessionManager)
            }
        }
    }
}

@Composable
private fun CharonRoot(sessionManager: SessionManager) {
    val session by sessionManager.activeSession.collectAsState()
    val error by sessionManager.lastError.collectAsState()

    val current = session
    if (current == null) {
        QuickConnectScreen(
            connecting = false,
            error = error,
            onConnect = { sessionManager.connect(it) },
        )
    } else {
        val state by current.state.collectAsState()
        if (state is TerminalSession.State.Connecting) {
            QuickConnectScreen(
                connecting = true,
                error = null,
                onConnect = {},
            )
        } else {
            TerminalScreen(
                session = current,
                onDismiss = { sessionManager.dismissSession() },
            )
        }
    }
}
