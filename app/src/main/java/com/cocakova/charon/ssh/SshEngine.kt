package com.cocakova.charon.ssh

/**
 * The thin seam over the SSH library — just wide enough that swapping sshj for the
 * mwiede JSch fork stays cheap (the plan's fallback), and no wider.
 */
interface SshEngine {
    /**
     * Connect, authenticate, open a shell with a PTY, and wire it to [session]:
     * remote bytes → [TerminalSession.feedRemote], [TerminalSession.onOutput] → stdin,
     * [TerminalSession.onResize] → window-change. Host keys go through [verifier]
     * (may block on a user trust decision). Blocks until the shell is live; throws
     * on failure. The returned handle owns the transport.
     */
    fun connectShell(
        config: ConnectConfig,
        session: TerminalSession,
        verifier: KnownHostsVerifier,
    ): SshConnection

    /** Install one public key through a short-lived, verified SSH connection. */
    fun installPublicKey(
        config: ConnectConfig,
        publicLine: String,
        verifier: KnownHostsVerifier,
    )
}

interface SshConnection {
    val isConnected: Boolean
    fun disconnect()

    /**
     * Run [command] silently on a fresh exec channel over the live transport and
     * return its stdout, or null on any failure/timeout. Nothing touches the user's
     * shell or PTY — this is the probe the smart-autofill context is built from
     * (installed commands, running tmux sessions…). Blocking; call off-main.
     */
    fun exec(command: String, timeoutSeconds: Int = 5): String?
}

data class ConnectConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String? = null,
    /** OpenSSH/PEM private key text (pasted in v0.1; Keystore-backed from v0.3). */
    val privateKeyPem: String? = null,
    val keyPassphrase: String? = null,
    /** Sent to the shell on every (re)connect — the mosh-compensation seat, e.g.
     *  `tmux new -As main` to land back in the same session after a redial. Blank = none. */
    val startupCommand: String = "",
    /** Redial on transport death (backoff + network-callback). The default for a homelab. */
    val autoReconnect: Boolean = true,
)
