package com.cocakova.charon.ssh

import android.util.Log
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.PTYMode
import net.schmizz.sshj.userauth.password.PasswordUtils
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
        val client = client(verifier)
        try {
            client.connect(config.host, config.port)
            authenticate(client, config)
            val sshSession = client.startSession()
            try {
                val line = shellQuote(publicLine.trim())
                val file = "\"\$HOME/.ssh/authorized_keys\""
                val command = sshSession.exec(
                    "umask 077 && mkdir -p \"\$HOME/.ssh\" && touch $file && " +
                        "chmod 700 \"\$HOME/.ssh\" && chmod 600 $file && " +
                        "{ grep -qxF $line $file || printf '%s\\n' $line >> $file; }",
                )
                command.join(30, TimeUnit.SECONDS)
                val status = command.exitStatus
                    ?: throw IllegalStateException("the courier timed out")
                if (status != 0) {
                    val detail = command.errorStream.bufferedReader().readText().trim()
                    throw IllegalStateException(
                        detail.ifBlank { "the remote host refused the key (exit $status)" },
                    )
                }
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
            client.connection.keepAlive.keepAliveInterval = 15
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

            // Reader: remote bytes into the emulator until EOF.
            thread(name = "charon-ssh-read-${session.id.take(8)}", isDaemon = true) {
                val buf = ByteArray(32 * 1024)
                var reason = "connection closed"
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
                } finally {
                    outbound.put(ChannelOp.Stop)
                    session.state.value = TerminalSession.State.Disconnected(reason)
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
}

internal fun shellQuote(value: String): String =
    "'" + value.replace("'", "'\"'\"'") + "'"
