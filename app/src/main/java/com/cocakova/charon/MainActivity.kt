package com.cocakova.charon

import android.content.Intent
import android.os.Bundle
import android.util.Base64
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.cocakova.charon.data.db.HostEntity
import com.cocakova.charon.data.db.IdentityEntity
import com.cocakova.charon.data.repository.HostVault
import com.cocakova.charon.data.repository.KeyVault
import com.cocakova.charon.presentation.dock.DockScreen
import com.cocakova.charon.presentation.dock.TrustGate
import com.cocakova.charon.presentation.terminal.TerminalScreen
import com.cocakova.charon.ssh.ConnectConfig
import com.cocakova.charon.ssh.SessionManager
import com.cocakova.charon.theme.CharonTheme
import kotlinx.coroutines.launch

/**
 * FragmentActivity (not ComponentActivity) because BiometricPrompt binds to one:
 * biometric-gated identities raise a fingerprint prompt hosted here, off the
 * KeyVault's pendingBio flow — the same park-and-resolve shape as the TOFU gate.
 */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as CharonApp
        if (BuildConfig.DEBUG) maybeDebugConnect(intent, app)
        setContent {
            CharonTheme {
                CharonRoot(app.sessionManager, app.hostVault, app.keyVault)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Repeated adb `am start` lands here, not onCreate.
        if (BuildConfig.DEBUG) maybeDebugConnect(intent, application as CharonApp)
    }

    /**
     * Debug-build-only adb hook so milestone demo gates can be driven headlessly:
     *   adb shell am start -n com.cocakova.charon/.MainActivity \
     *     --es debug_host spark --es debug_user me --es debug_key_b64 "$(base64 -w0 key)"
     * With --es debug_import_key_b64 it imports the key as an identity instead of
     * connecting, so the keys-of-passage flow can be driven from a script.
     * Compiled out of release builds by the BuildConfig gate at the call site.
     */
    private fun maybeDebugConnect(intent: Intent?, app: CharonApp) {
        intent?.getStringExtra("debug_import_key_b64")?.let { b64 ->
            val pem = String(Base64.decode(b64, Base64.DEFAULT))
            val name = intent.getStringExtra("debug_import_name") ?: "debug-import"
            lifecycleScope.launch { app.keyVault.import(name, pem, null, biometric = false) }
            return
        }
        val host = intent?.getStringExtra("debug_host") ?: return
        val user = intent.getStringExtra("debug_user") ?: return
        val keyPem = intent.getStringExtra("debug_key_b64")
            ?.let { String(Base64.decode(it, Base64.DEFAULT)) }
        val password = intent.getStringExtra("debug_password")
        if (keyPem == null && password == null) return
        app.sessionManager.connect(
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
private fun CharonRoot(
    sessionManager: SessionManager,
    hostVault: HostVault,
    keyVault: KeyVault,
) {
    val active by sessionManager.activeSession.collectAsState()
    val sessions by sessionManager.sessions.collectAsState()
    val error by sessionManager.lastError.collectAsState()
    val pendingTrust by sessionManager.pendingTrust.collectAsState()
    val hosts by hostVault.hosts.collectAsState(initial = emptyList())
    val identities by keyVault.identities.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    val current = active
    // Terminal on screen whenever a session is active; null active = the Dock, with
    // any live crossings still running in the background.
    val showTerminal = current != null

    // Count returns from sea so the Dock can play the ferry docking on arrival.
    var atSea by remember { mutableStateOf(false) }
    var arrivals by remember { mutableIntStateOf(0) }
    LaunchedEffect(showTerminal) {
        if (showTerminal) {
            atSea = true
        } else if (atSea) {
            atSea = false
            arrivals++
        }
    }

    // One crossfade for the whole crossing: the Dock -> terminal and back.
    Crossfade(
        targetState = showTerminal,
        animationSpec = tween(durationMillis = 500),
        label = "crossing",
    ) { terminal ->
        if (terminal && current != null) {
            TerminalScreen(
                session = current,
                sessions = sessions,
                onSwitch = { sessionManager.switchTo(it) },
                onClose = { sessionManager.close(it) },
                onReconnect = { sessionManager.forceReconnect(it) },
                onNewSession = { sessionManager.showDock() },
            )
        } else {
            DockScreen(
                hosts = hosts,
                identities = identities,
                runningSessions = sessions,
                onResumeSession = { sessionManager.switchTo(it) },
                connecting = false,
                arrivals = arrivals,
                error = error,
                onConnect = { host ->
                    // connectConfig may raise a fingerprint prompt (gated key) and
                    // returns null if the user dismisses it — abort quietly then.
                    scope.launch {
                        hostVault.connectConfig(host)?.let {
                            sessionManager.connect(it, hostId = host.id)
                        }
                    }
                },
                onQuickConnect = { draft ->
                    // draftConfig folds in stored-password fallback and identity
                    // resolution; null = biometric prompt dismissed.
                    scope.launch {
                        hostVault.draftConfig(draft)?.let {
                            sessionManager.connect(it, hostId = draft.id)
                        }
                    }
                },
                onSave = { draft -> scope.launch { hostVault.save(draft) } },
                onDelete = { id -> scope.launch { hostVault.delete(id) } },
                onForgeKey = { name, bio -> keyVault.forge(name, bio) },
                onImportKey = { name, key, pass, bio -> keyVault.import(name, key, pass, bio) },
                onReleaseKey = { id -> keyVault.delete(id) },
                onGrantKey = { host, identity ->
                    val config = hostVault.courierConfig(host)
                        ?: throw IllegalStateException(
                            "this mooring has no saved password to carry the key",
                        )
                    sessionManager.grantPassage(config, identity.publicLine)
                },
            )
        }
    }

    pendingTrust?.let { TrustGate(it) }
    BiometricGate(keyVault)
}

/**
 * Hosts the system fingerprint prompt. When the KeyVault parks on a PendingBio it
 * publishes the cipher to authenticate; a success hands the blessed cipher back so
 * the seal/unseal completes, and any dismissal resolves to a cancel (never an
 * error) so the caller aborts quietly. onAuthenticationFailed (a single bad read)
 * is deliberately not handled — the prompt stays up and lets the user retry.
 */
@Composable
private fun BiometricGate(keyVault: KeyVault) {
    val pending by keyVault.pendingBio.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(pending) {
        val p = pending ?: return@LaunchedEffect
        val activity = context as? FragmentActivity
        if (activity == null) {
            p.cancel()
            return@LaunchedEffect
        }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    result.cryptoObject?.cipher?.let { p.succeed(it) } ?: p.cancel()
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    p.cancel()
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Charon")
            .setSubtitle(p.title)
            .setNegativeButtonText("cancel")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(p.cipher))
    }
}
