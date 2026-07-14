package com.cocakova.charon.ssh

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

private const val TAG = "CharonSocks"

/**
 * Charon's own SOCKS5 server — the dynamic ("D") forward. Owned code, no NDK, no
 * dependency: it listens on localhost, speaks just enough SOCKS5 (no-auth, CONNECT,
 * IPv4/domain/IPv6 addresses), and pumps each accepted stream through a channel the
 * SSH layer opens via [openTunnel] (a `direct-tcpip` channel on the live transport).
 * Point the phone browser (or any app) at `socks5://127.0.0.1:port` and its traffic
 * rides the crossing.
 *
 * UDP ASSOCIATE and BIND are refused with the proper reply code — browsers only
 * need CONNECT.
 */
class Socks5Server(
    private val port: Int,
    private val openTunnel: (host: String, port: Int) -> Tunnel,
) {
    /** One open channel to the far shore: the two streams and a closer. */
    interface Tunnel {
        val input: InputStream
        val output: OutputStream
        fun close()
    }

    @Volatile private var server: ServerSocket? = null
    @Volatile private var closed = false

    /** Bind and start accepting. Throws if the port can't be had. */
    fun start() {
        val ss = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
        server = ss
        thread(name = "charon-socks-$port", isDaemon = true) {
            while (!closed) {
                val socket = try {
                    ss.accept()
                } catch (e: Exception) {
                    if (!closed) Log.w(TAG, "accept failed", e)
                    break
                }
                thread(isDaemon = true) { runCatching { handle(socket) } }
            }
        }
    }

    fun stop() {
        closed = true
        runCatching { server?.close() }
    }

    // ---- protocol -----------------------------------------------------------------

    private fun handle(socket: Socket) {
        socket.tcpNoDelay = true
        val inp = socket.getInputStream()
        val out = socket.getOutputStream()

        // Greeting: VER NMETHODS METHODS… — we only ever offer NO AUTH (0x00).
        if (inp.read() != 5) return socket.close()
        val nMethods = inp.read()
        if (nMethods < 0) return socket.close()
        inp.readNBytesCompat(nMethods)
        out.write(byteArrayOf(5, 0))
        out.flush()

        // Request: VER CMD RSV ATYP DST.ADDR DST.PORT
        val head = inp.readNBytesCompat(4)
        if (head.size < 4 || head[0].toInt() != 5) return socket.close()
        val cmd = head[1].toInt()
        val host: String = when (head[3].toInt()) {
            1 -> inp.readNBytesCompat(4).joinToString(".") { (it.toInt() and 0xFF).toString() }
            3 -> {
                val len = inp.read()
                if (len <= 0) return socket.close()
                String(inp.readNBytesCompat(len), Charsets.US_ASCII)
            }
            4 -> InetAddress.getByAddress(inp.readNBytesCompat(16)).hostAddress ?: return socket.close()
            else -> return socket.close()
        }
        val portBytes = inp.readNBytesCompat(2)
        if (portBytes.size < 2) return socket.close()
        val dstPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

        if (cmd != 1) { // only CONNECT; refuse BIND/UDP with "command not supported"
            reply(out, 7)
            return socket.close()
        }

        val tunnel = try {
            openTunnel(host, dstPort)
        } catch (e: Exception) {
            Log.w(TAG, "tunnel to $host:$dstPort failed", e)
            reply(out, 5) // connection refused
            return socket.close()
        }

        reply(out, 0) // success

        // Pump both directions; either side ending sinks the pair.
        val up = thread(isDaemon = true) {
            runCatching { pump(inp, tunnel.output) }
            runCatching { tunnel.close() }
            runCatching { socket.close() }
        }
        runCatching { pump(tunnel.input, out) }
        runCatching { tunnel.close() }
        runCatching { socket.close() }
        runCatching { up.join(2_000) }
    }

    /** REP with a zeroed BND — clients ignore it for CONNECT. */
    private fun reply(out: OutputStream, code: Int) {
        runCatching {
            out.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
            out.flush()
        }
    }

    private fun pump(from: InputStream, to: OutputStream) {
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = from.read(buf)
            if (n < 0) break
            to.write(buf, 0, n)
            to.flush()
        }
    }
}

/** readNBytes exists from API 33; minSdk is 24, so read the classic way. */
private fun InputStream.readNBytesCompat(n: Int): ByteArray {
    val buf = ByteArray(n)
    var got = 0
    while (got < n) {
        val r = read(buf, got, n - got)
        if (r < 0) return buf.copyOf(got)
        got += r
    }
    return buf
}
