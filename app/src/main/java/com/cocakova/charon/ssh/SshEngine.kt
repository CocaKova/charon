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

    /**
     * Run one command over a short-lived, verified connection — connect, exec,
     * hang up — and return its stdout. No PTY, no session left behind; this is
     * how the fleet import asks a mooring for `tailscale status --json`.
     * Throws with a readable message on refusal. Blocking; call off-main.
     */
    fun execOnce(
        config: ConnectConfig,
        command: String,
        verifier: KnownHostsVerifier,
    ): String
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

    /**
     * Open a fresh SFTP channel on the live transport, or null if it can't be had
     * (server without the subsystem, transport mid-death). Each caller gets its own:
     * the browser holds one, every transfer opens another, so a long pull never
     * blocks directory listings. Blocking; call off-main.
     */
    fun openSftp(): SftpChannel?

    /**
     * Chart a channel across the crossing. [type] is "L" (phone :bindPort →
     * [targetHost]:[targetPort] as seen from the server), "R" (server :bindPort →
     * target as seen from the phone) or "D" (SOCKS5 proxy on phone :bindPort;
     * target args unused). Throws if the port can't be bound. Blocking; call
     * off-main. The handle outlives this call until stopped or the transport dies.
     */
    fun startForward(type: String, bindPort: Int, targetHost: String, targetPort: Int): ForwardHandle
}

/** One charted channel, running until stopped (or the transport under it dies). */
interface ForwardHandle {
    fun stop()
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
