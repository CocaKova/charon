package com.cocakova.charon.fleet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** A ship answering on :22 in the near waters. */
data class SweepFind(
    val ip: String,
    /** Reverse-DNS name when the water gives one up; null = just the address. */
    val hostname: String?,
)

/** Where the phone sits right now: its own address and the /24 around it. */
data class NearWaters(val ownIp: String, val subnetLabel: String)

/**
 * The near-waters sweep: dial :22 across the phone's own /24 and see who answers.
 * No permissions needed — plain outbound TCP, same as connecting. The candidate
 * enumeration is a pure function so the JVM tests cover it without a network.
 */
object LanSweep {

    const val SSH_PORT = 22
    private const val DIAL_TIMEOUT_MS = 400
    private const val MAX_PARALLEL_DIALS = 64

    // Interfaces that carry a site-local address but are NOT the LAN we mean to
    // sweep: cellular (rmnet/clat), VPN/Tailscale (tun/ppp), tethering (rndis), and
    // container bridges. A carrier's CGNAT hands out 10.x on rmnet — sweeping that
    // /24 would dial 253 strangers' devices.
    private val NON_LAN_IFACES = listOf("rmnet", "clat", "tun", "ppp", "rndis", "docker")

    /** Wi-Fi/ethernet sort ahead of anything else that slipped through the filter. */
    private fun ifaceRank(name: String): Int = when {
        name.startsWith("wlan") -> 0
        name.startsWith("eth") -> 1
        else -> 2
    }

    /**
     * The phone's current IPv4 berth — the Wi-Fi/ethernet LAN it sits on, never the
     * cellular or VPN interface. Null when there's no such network under us.
     */
    fun nearWaters(): NearWaters? {
        val addr = runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .filter { iface -> NON_LAN_IFACES.none { iface.name.startsWith(it) } }
                .mapNotNull { iface ->
                    val ip = iface.inetAddresses.asSequence()
                        .filterIsInstance<Inet4Address>()
                        .firstOrNull { it.isSiteLocalAddress }
                    ip?.let { ifaceRank(iface.name) to it }
                }
                .sortedBy { it.first }
                .firstOrNull()?.second
        }.getOrNull() ?: return null
        val ip = addr.hostAddress ?: return null
        return NearWaters(ownIp = ip, subnetLabel = ip.substringBeforeLast('.') + ".0/24")
    }

    /**
     * Every other berth in the /24 around [ownIp]. Wider LANs still sweep only the
     * /24 the phone sits in — 254 dials is a sweep, 65k is a siege.
     */
    fun candidates(ownIp: String): List<String> {
        val stem = ownIp.substringBeforeLast('.', missingDelimiterValue = "")
        if (stem.isEmpty()) return emptyList()
        return (1..254).map { "$stem.$it" }.filter { it != ownIp }
    }

    /**
     * Dial the whole harbor. [onProgress] ticks with (dialed, found-so-far) so the
     * sheet can narrate the sweep. [dial] injectable for tests, like FleetWatch.
     */
    suspend fun sweep(
        ownIp: String,
        dial: (String, Int, Int) -> Long? = FleetWatch.Companion::tcpDial,
        resolve: (String) -> String? = ::reverseName,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): List<SweepFind> = withContext(Dispatchers.IO) {
        val progressLock = Any()
        var dialed = 0
        var found = 0
        val finds = dialMany(candidates(ownIp), MAX_PARALLEL_DIALS) { ip ->
            val open = runCatching { dial(ip, SSH_PORT, DIAL_TIMEOUT_MS) }
                .getOrNull() != null
            synchronized(progressLock) {
                dialed++
                if (open) found++
                onProgress(dialed, found)
            }
            if (open) ip else null
        }.filterNotNull()
        // Names only for the ships that answered — reverse DNS on 254 addrs would
        // stall the sweep for nothing.
        finds.map { ip -> SweepFind(ip = ip, hostname = resolve(ip)) }
            .sortedBy { it.ip.substringAfterLast('.').toIntOrNull() ?: 255 }
    }

    private fun reverseName(ip: String): String? =
        runCatching { InetAddress.getByName(ip).canonicalHostName }
            .getOrNull()?.takeIf { it != ip }
}
