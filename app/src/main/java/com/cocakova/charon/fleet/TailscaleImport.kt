package com.cocakova.charon.fleet

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One ship sighted on the tailnet, offered for mooring. */
data class FleetCandidate(
    val name: String,
    /** What we'd dial: the 100.x tailnet IPv4, or the MagicDNS name as fallback. */
    val host: String,
    val online: Boolean,
    val os: String,
)

/**
 * Reads the fleet out of `tailscale status --json` — pasted in, or fetched over a
 * one-shot exec on a mooring that's already on the tailnet. Pure string → list, so
 * the whole thing is JVM-tested against real-shaped status output.
 *
 * Only the `Peer` map is read: `Self` is the phone in your hand, and it has no
 * business mooring itself.
 */
object TailscaleImport {

    private val json = Json { ignoreUnknownKeys = true }

    /** @throws IllegalArgumentException when the text isn't a tailscale status. */
    fun parse(text: String): List<FleetCandidate> {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }
            .getOrElse { throw IllegalArgumentException("that doesn't read as a tailscale status") }
        val peers = root["Peer"]?.jsonObject
            ?: throw IllegalArgumentException("no fleet in this status — is Tailscale up on that mooring?")
        return peers.values.mapNotNull { element ->
            val peer = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val ipv4 = peer["TailscaleIPs"]?.let { ips ->
                runCatching { ips.jsonArray }.getOrNull()
                    ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
                    ?.firstOrNull { '.' in it }
            }
            val dnsName = peer.string("DNSName")?.trimEnd('.')?.takeIf { it.isNotBlank() }
            val host = ipv4 ?: dnsName ?: return@mapNotNull null
            FleetCandidate(
                name = peer.string("HostName")?.takeIf { it.isNotBlank() }
                    ?: dnsName?.substringBefore('.')
                    ?: host,
                host = host,
                online = peer["Online"]?.let {
                    runCatching { it.jsonPrimitive.content.toBoolean() }.getOrNull()
                } ?: false,
                os = peer.string("OS").orEmpty(),
            )
        }.sortedWith(compareByDescending<FleetCandidate> { it.online }.thenBy { it.name.lowercase() })
    }

    private fun kotlinx.serialization.json.JsonObject.string(key: String): String? =
        this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
}
