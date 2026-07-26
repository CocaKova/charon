package com.cocakova.charon.ssh

import android.util.Log
import com.cocakova.charon.service.AppVisibility
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Message
import net.schmizz.sshj.common.SSHPacket
import net.schmizz.sshj.connection.channel.direct.PTYMode
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import net.schmizz.sshj.connection.channel.forwarded.SocketForwardingConnectListener
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val TAG = "CharonSsh"

/** Work items for the single channel-writer thread. */
private sealed class ChannelOp {
    class Write(val bytes: ByteArray) : ChannelOp()
    class Resize(val cols: Int, val rows: Int) : ChannelOp()
    data object Stop : ChannelOp()
}

class SshjEngine : SshEngine {

    override fun installPublicKey(
        config: ConnectConfig,
        publicLine: String,
        verifier: KnownHostsVerifier,
    ) {
        val line = shellQuote(publicLine.trim())
        val file = "\"\$HOME/.ssh/authorized_keys\""
        oneShotExec(
            config,
            "umask 077 && mkdir -p \"\$HOME/.ssh\" && touch $file && " +
                "chmod 700 \"\$HOME/.ssh\" && chmod 600 $file && " +
                "{ grep -qxF $line $file || printf '%s\\n' $line >> $file; }",
            verifier,
            "the remote host refused the key",
        )
    }

    override fun execOnce(
        config: ConnectConfig,
        command: String,
        verifier: KnownHostsVerifier,
    ): String = oneShotExec(config, command, verifier, "the mooring refused the errand")

    /**
     * Connect, authenticate, run one command over a short-lived verified
     * connection, and hang up — the single owner of the "short-lived verified
     * connection" ritual, shared by the key courier and the fleet import.
     *
     * The read is bounded by a socket read timeout ([ONE_SHOT_TIMEOUT_S]) set the
     * moment the transport is up (after the TOFU handshake, which keeps the long
     * timeoutMs). A command that never closes stdout — a wedged daemon, a shell rc
     * that prompts — surfaces as a SocketTimeoutException instead of parking the
     * IO thread forever. Blocking; call off-main.
     */
    private fun oneShotExec(
        config: ConnectConfig,
        command: String,
        verifier: KnownHostsVerifier,
        refusalMessage: String,
    ): String {
        val client = client(verifier)
        try {
            client.connect(config.host, config.port)
            // TOFU is resolved inside connect(); now bound every subsequent read so
            // the errand can't hang the caller.
            client.timeout = ONE_SHOT_TIMEOUT_S * 1000
            authenticate(client, config)
            val sshSession = client.startSession()
            try {
                val cmd = sshSession.exec(command)
                val out = cmd.inputStream.bufferedReader().readText()
                cmd.join(ONE_SHOT_TIMEOUT_S.toLong(), TimeUnit.SECONDS)
                val status = cmd.exitStatus
                    ?: throw IllegalStateException("the mooring took too long to answer")
                if (status != 0) {
                    val detail = cmd.errorStream.bufferedReader().readText().trim()
                    throw IllegalStateException(detail.ifBlank { "$refusalMessage (exit $status)" })
                }
                return out
            } finally {
                runCatching { sshSession.close() }
            }
        } finally {
            runCatching { client.close() }
        }
    }

    override fun connectShell(
        config: ConnectConfig,
        session: TerminalSession,
        verifier: KnownHostsVerifier,
    ): SshConnection {
        val client = client(verifier)

        try {
            client.connect(config.host, config.port)
            // The heartbeat starts at whichever rate matches where the app is right
            // now — a redial fired by the network callback can land with the phone
            // still pocketed, and must not wake the radio every 30s all night.
            client.connection.keepAlive.keepAliveInterval =
                if (AppVisibility.visible) SshConnection.KEEPALIVE_FOREGROUND_S
                else SshConnection.KEEPALIVE_BACKGROUND_S
            authenticate(client, config)

            val sshSession = client.startSession()
            val (cols, rows) = synchronized(session.lock) {
                session.term.cols to session.term.rows
            }
            sshSession.allocatePTY("xterm-256color", cols, rows, 0, 0, emptyMap<PTYMode, Int>())
            val shell = sshSession.startShell()

            // All channel traffic is marshalled through one queue + thread: onOutput
            // and onResize fire on the UI thread (keystrokes, keyboard-driven grid
            // changes) AND the reader thread (DA/DSR responses), and a direct socket
            // write from the UI thread is a NetworkOnMainThreadException — which used
            // to vanish into a silent catch. One writer also keeps keystrokes and
            // emulator responses from interleaving mid-sequence.
            val outbound = LinkedBlockingQueue<ChannelOp>()
            thread(name = "charon-ssh-write-${session.id.take(8)}", isDaemon = true) {
                try {
                    while (true) {
                        when (val op = outbound.take()) {
                            is ChannelOp.Stop -> break
                            is ChannelOp.Write -> {
                                shell.outputStream.write(op.bytes)
                                shell.outputStream.flush()
                            }
                            is ChannelOp.Resize ->
                                shell.changeWindowDimensions(op.cols, op.rows, 0, 0)
                        }
                    }
                } catch (e: Exception) {
                    // channel gone; the reader thread reports the disconnect
                    Log.w(TAG, "writer stopped", e)
                }
            }
            session.onOutput = { bytes -> outbound.put(ChannelOp.Write(bytes)) }
            session.onResize = { cols, rows -> outbound.put(ChannelOp.Resize(cols, rows)) }

            // Startup command (tmux auto-attach and friends): typed into the fresh
            // shell so it runs on every crossing — including an auto-reconnect redial,
            // which is what lands you back in the same tmux after the network blips.
            config.startupCommand.trim().takeIf { it.isNotEmpty() }?.let { cmd ->
                outbound.put(ChannelOp.Write((cmd + "\n").toByteArray(Charsets.UTF_8)))
            }

            // Reader: remote bytes into the emulator until EOF. A clean EOF (n < 0)
            // means the remote closed the channel — you logged out, or the server hung
            // up; an exception means the transport died. The distinction rides out on
            // Disconnected.clean so SessionManager only redials true drops, never an
            // `exit`.
            thread(name = "charon-ssh-read-${session.id.take(8)}", isDaemon = true) {
                val buf = ByteArray(32 * 1024)
                var reason = "returned to shore"
                var clean = true
                try {
                    val input = shell.inputStream
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n > 0) session.feedRemote(buf, 0, n)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "reader stopped", e)
                    reason = e.message ?: e.javaClass.simpleName
                    clean = false
                } finally {
                    outbound.put(ChannelOp.Stop)
                    session.state.value = TerminalSession.State.Disconnected(reason, clean)
                    runCatching { sshSession.close() }
                    runCatching { client.disconnect() }
                }
            }

            session.state.value = TerminalSession.State.Connected
            return object : SshConnection {
                override val isConnected: Boolean get() = client.isConnected
                override fun disconnect() {
                    runCatching { sshSession.close() }
                    runCatching { client.close() }
                }

                override fun exec(command: String, timeoutSeconds: Int): String? = runCatching {
                    // A sibling channel on the live transport: invisible to the PTY.
                    // The read is deadline-polled, never a block-to-EOF: this channel
                    // shares the interactive session's socket (client.timeout must
                    // stay 0), and a probe that wedges would otherwise hang the read
                    // forever — with RemoteContext's in-flight latch never clearing,
                    // that argument kind would never be probed again all session.
                    val probe = client.startSession()
                    try {
                        val cmd = probe.exec(command)
                        val stream = cmd.inputStream
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        val deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L
                        while (System.nanoTime() < deadline && out.size() < MAX_PROBE_BYTES) {
                            val avail = stream.available()
                            if (avail > 0) {
                                val n = stream.read(buf, 0, minOf(buf.size, avail))
                                if (n < 0) break
                                out.write(buf, 0, n)
                            } else if (cmd.isOpen) {
                                Thread.sleep(20)
                            } else {
                                // Channel closed — reads now return buffered bytes
                                // or -1 without blocking.
                                val n = stream.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                            }
                        }
                        if (System.nanoTime() >= deadline && cmd.isOpen) null // wedged: report failure
                        else out.toString(Charsets.UTF_8.name())
                    } finally {
                        runCatching { probe.close() }
                    }
                }.getOrNull()

                override fun openSftp(): SftpChannel? =
                    runCatching { SshjSftp(client.newSFTPClient()) }.getOrNull()

                override fun setKeepAlive(intervalSeconds: Int) {
                    client.connection.keepAlive.keepAliveInterval = intervalSeconds
                }

                override fun nudge() {
                    // SSH_MSG_IGNORE: one packet, no reply expected. A live link
                    // absorbs it; a dead one surfaces as a transport error that the
                    // reader thread turns into Disconnected(clean=false) → redial.
                    client.transport.write(SSHPacket(Message.IGNORE))
                }

                override fun startForward(
                    type: String,
                    bindPort: Int,
                    targetHost: String,
                    targetPort: Int,
                ): ForwardHandle = when (type) {
                    "L" -> {
                        // Phone listens; each accepted socket rides a direct-tcpip channel.
                        val ss = ServerSocket().apply {
                            reuseAddress = true
                            bind(InetSocketAddress("127.0.0.1", bindPort))
                        }
                        val forwarder = client.newLocalPortForwarder(
                            Parameters("127.0.0.1", bindPort, targetHost, targetPort),
                            ss,
                        )
                        thread(name = "charon-fwd-L-$bindPort", isDaemon = true) {
                            runCatching { forwarder.listen() }
                        }
                        object : ForwardHandle {
                            override fun stop() {
                                runCatching { ss.close() }
                            }
                        }
                    }
                    "R" -> {
                        // Server listens; sshj hands each connection back and we
                        // socket it onward from the phone's side of the world.
                        val forward = client.remotePortForwarder.bind(
                            RemotePortForwarder.Forward(bindPort),
                            SocketForwardingConnectListener(
                                InetSocketAddress(targetHost, targetPort),
                            ),
                        )
                        object : ForwardHandle {
                            override fun stop() {
                                runCatching { client.remotePortForwarder.cancel(forward) }
                            }
                        }
                    }
                    "D" -> {
                        // Charon's own SOCKS5 riding direct-tcpip channels.
                        val socks = Socks5Server(bindPort) { host, port ->
                            val ch = client.newDirectConnection(host, port)
                            object : Socks5Server.Tunnel {
                                override val input get() = ch.inputStream
                                override val output get() = ch.outputStream
                                override fun close() {
                                    runCatching { ch.close() }
                                }
                            }
                        }
                        socks.start()
                        object : ForwardHandle {
                            override fun stop() = socks.stop()
                        }
                    }
                    else -> error("unknown channel type $type")
                }
            }
        } catch (e: Exception) {
            runCatching { client.close() }
            throw e
        }
    }

    private fun client(verifier: KnownHostsVerifier): SSHClient {
        val sshConfig = DefaultConfig().apply {
            keepAliveProvider = KeepAliveProvider.KEEP_ALIVE
        }
        return SSHClient(sshConfig).apply {
            addHostKeyVerifier(verifier)
            connectTimeout = 15_000
            timeout = 0
            // A human comparing a TOFU fingerprint may take longer than sshj's default.
            transport.timeoutMs = 300_000
        }
    }

    private fun authenticate(client: SSHClient, config: ConnectConfig) {
        var keyFailure: Exception? = null
        if (config.privateKeyPem != null) {
            try {
                val finder = config.keyPassphrase
                    ?.let { PasswordUtils.createOneOff(it.toCharArray()) }
                client.authPublickey(
                    config.username,
                    client.loadKeys(config.privateKeyPem, null, finder),
                )
                return
            } catch (e: Exception) {
                keyFailure = e
            }
        }
        if (config.password != null) {
            client.authPassword(config.username, config.password)
            return
        }
        if (keyFailure != null) throw keyFailure
        error("no authentication method provided")
    }

    companion object {
        /** Read bound for a one-shot errand — long enough for a slow tailnet RTT,
         *  short enough that a wedged command doesn't strand the caller. */
        private const val ONE_SHOT_TIMEOUT_S = 25

        /** Output cap for a silent probe (`compgen -c` on a busy host runs ~60 KB;
         *  anything past this is a runaway command, not an inventory). */
        private const val MAX_PROBE_BYTES = 512 * 1024
    }
}

internal fun shellQuote(value: String): String =
    "'" + value.replace("'", "'\"'\"'") + "'"
