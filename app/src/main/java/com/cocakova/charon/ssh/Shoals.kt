package com.cocakova.charon.ssh

import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Shoals: what went wrong on a crossing, said twice — the literal error (so nothing
 * is hidden from someone who knows what ECONNREFUSED means) and, beneath it, what it
 * most likely means in plain words. Pure, so every reading is tested.
 */
object Shoals {

    /** "literal\nplain hint" — the hint line is left off when there's nothing better to say. */
    fun describe(e: Throwable, host: String, port: Int): String {
        val literal = literal(e)
        val hint = hint(e, host, port)
        return if (hint == null) literal else "$literal\n$hint"
    }

    fun literal(e: Throwable): String {
        val msg = e.message?.takeIf { it.isNotBlank() } ?: e.cause?.message?.takeIf { it.isNotBlank() }
        return msg ?: e.javaClass.simpleName
    }

    fun hint(e: Throwable, host: String, port: Int): String? {
        val chain = generateSequence(e) { it.cause }.take(6).toList()
        val text = chain.joinToString(" ") { (it.message ?: "") + " " + it.javaClass.simpleName }.lowercase()
        return when {
            chain.any { it is UnknownHostException } ->
                "no shore by the name $host — check the spelling, or whether its DNS or tailnet is up on the phone"
            chain.any { it is NoRouteToHostException } || "no route to host" in text ->
                "no way across to $host — the phone isn't on a network that reaches it (VPN or tailnet off?)"
            chain.any { it is ConnectException } || "connection refused" in text ->
                "$host answered but nothing is listening on port $port — is sshd running, on that port?"
            chain.any { it is SocketTimeoutException } || "timed out" in text || "timeout" in text ->
                "no answer from $host:$port — asleep, off the network, or a firewall between"
            "network is unreachable" in text || "enetunreach" in text ->
                "the phone has no network that reaches $host right now"
            "host key" in text || "hostkey" in text || "host_key" in text ->
                "the host's key wasn't trusted, so nothing crossed"
            chain.any { it is UserAuthException } || "exhausted available authentication" in text ->
                "the shore turned away every key and password offered — check the username, and which key is attached"
            "passphrase" in text || "decrypt" in text || "bad padding" in text ->
                "the key wouldn't open — the passphrase may be wrong"
            "connection reset" in text || "broken pipe" in text || "end of file" in text || "eof" in text ->
                "the shore hung up mid-crossing — sshd may have restarted, or a limit (MaxStartups) was hit"
            chain.any { it is TransportException } ->
                "the crossing failed during the handshake — the two sides may share no cipher or key type"
            else -> null
        }
    }
}
