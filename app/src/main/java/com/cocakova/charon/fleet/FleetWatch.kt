package com.cocakova.charon.fleet

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.InetSocketAddress
import java.net.Socket

/** How the water answered the last sounding of a mooring. */
enum class Reach { REACHABLE, UNREACHABLE }

/** One depth sounding: did the mooring answer, and how quickly. */
data class Sounding(
    val reach: Reach,
    /** Round-trip of the TCP dial, ms; null when the water is dark. */
    val latencyMs: Long? = null,
    val at: Long = System.currentTimeMillis(),
)

/** A mooring to sound: the saved host's id plus where its lantern hangs. */
data class SoundingTarget(val id: String, val host: String, val port: Int)

/**
 * The fleet's soundings: a parallel TCP dial to every mooring's own SSH port —
 * no daemon, no root, no ICMP. The Dock drives it while it's on screen (a
 * LaunchedEffect loop) and lets it rest the moment the user casts off; results
 * live here, app-wide, so stepping ashore again shows the last-known water
 * instead of a blank harbor.
 *
 * [dial] is the single side-effecting seam (host, port, timeoutMs) → latency ms
 * or null, injectable so the JVM tests never open a socket.
 */
class FleetWatch(
    private val dial: (String, Int, Int) -> Long? = ::tcpDial,
) {
    private val _soundings = MutableStateFlow<Map<String, Sounding>>(emptyMap())
    val soundings: StateFlow<Map<String, Sounding>> = _soundings.asStateFlow()

    /**
     * Sound every target once, in parallel (capped so a big fleet doesn't open a
     * socket storm), publishing each result as it lands — dots light one by one
     * rather than all at once when the slowest timeout expires.
     */
    suspend fun soundAll(targets: List<SoundingTarget>) {
        dialMany(targets, MAX_PARALLEL_DIALS) { target ->
            val latency = runCatching {
                dial(target.host, target.port, DIAL_TIMEOUT_MS)
            }.getOrNull()
            record(target.id, latency)
        }
        // Moorings deleted since the last pass drop out of the chart.
        val known = targets.mapTo(HashSet()) { it.id }
        _soundings.update { chart -> chart.filterKeys { it in known } }
    }

    private fun record(id: String, latencyMs: Long?) {
        val sounding =
            if (latencyMs != null) Sounding(Reach.REACHABLE, latencyMs)
            else Sounding(Reach.UNREACHABLE)
        // Atomic — up to MAX_PARALLEL_DIALS coroutines land here at once.
        _soundings.update { it + (id to sounding) }
    }

    companion object {
        const val DIAL_TIMEOUT_MS = 1500
        private const val MAX_PARALLEL_DIALS = 16

        /** The real dial: one TCP connect, then hang up. DNS resolves in-line. */
        fun tcpDial(host: String, port: Int, timeoutMs: Int): Long? = runCatching {
            Socket().use { socket ->
                val started = System.nanoTime()
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                (System.nanoTime() - started) / 1_000_000
            }
        }.getOrNull()
    }
}
