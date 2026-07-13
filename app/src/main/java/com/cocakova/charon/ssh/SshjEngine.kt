package com.cocakova.charon.ssh

import android.util.Log
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.PTYMode
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

private const val TAG = "CharonSsh"

/** Work items for the single channel-writer thread. */
private sealed class ChannelOp {
    class Write(val bytes: ByteArray) : ChannelOp()
    class Resize(val cols: Int, val rows: Int) : ChannelOp()
    data object Stop : ChannelOp()
}

class SshjEngine : SshEngine {

    override fun connectShell(config: ConnectConfig, session: TerminalSession): SshConnection {
        val sshConfig = DefaultConfig().apply {
            keepAliveProvider = KeepAliveProvider.KEEP_ALIVE
        }
        val client = SSHClient(sshConfig)
        // v0.1 walking skeleton trusts on first sight. v0.2 replaces this with the
        // Room-backed TOFU verifier + fingerprint sheet (docs/PLAN.md).
        client.addHostKeyVerifier(PromiscuousVerifier())
        client.connectTimeout = 15_000
        client.timeout = 0 // interactive session: no read timeout

        try {
            client.connect(config.host, config.port)
            client.connection.keepAlive.keepAliveInterval = 15

            when {
                config.privateKeyPem != null -> {
                    val passwordFinder = config.keyPassphrase
                        ?.let { PasswordUtils.createOneOff(it.toCharArray()) }
                    val keys = client.loadKeys(config.privateKeyPem, null, passwordFinder)
                    client.authPublickey(config.username, keys)
                }
                config.password != null ->
                    client.authPassword(config.username, config.password)
                else -> error("no authentication method provided")
            }

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
}
