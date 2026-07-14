package com.cocakova.charon.autocomplete

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * What the connected host can actually do, probed live over a silent exec channel
 * (never through the user's PTY): every executable on PATH, the tmux sessions that
 * are running, docker containers, systemd units. This is the difference between
 * guessing and knowing — `tm` suggests `tmux` because the host *has* tmux, and
 * `tmux attach -t ` offers the sessions that exist right now.
 *
 * All probes are best-effort with timeouts: a missing tool or a slow host just means
 * an empty list, never a hang or an error surfaced to the user. Results are cached
 * ([version] ticks on every landing so Compose recomputes); dynamic kinds refresh on
 * a short TTL, the PATH inventory once per crossing.
 */
class RemoteContext(
    private val scope: CoroutineScope,
    private val exec: (command: String) -> String?,
) {
    /** Bumped whenever any probe lands — the recomposition key for suggestion UIs. */
    val version = MutableStateFlow(0)

    @Volatile
    var commands: List<String> = emptyList()
        private set

    private class Cached(val values: List<String>, val at: Long)

    private val argCache = ConcurrentHashMap<ArgKind, Cached>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** Inventory the host's PATH. Called once per (re)connect. */
    fun refreshCommands() {
        probe("commands") {
            val out = run(
                // compgen sees builtins/aliases too; the ls fallback covers bash-less hosts.
                "bash -c 'compgen -c' 2>/dev/null || ls /usr/local/bin /usr/bin /bin /usr/sbin /sbin 2>/dev/null",
            ) ?: return@probe
            val names = out.lineSequence()
                .map { it.trim() }
                .filter { it.length > 1 && it.all { ch -> ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.' } }
                .toSortedSet()
            if (names.isNotEmpty()) {
                commands = names.toList()
                version.value++
            }
        }
    }

    /**
     * Current values for a dynamic argument kind — returns the cache immediately and
     * refreshes in the background when stale, so typing never blocks on the network.
     */
    fun args(kind: ArgKind): List<String> {
        if (kind == ArgKind.NONE) return emptyList()
        val cached = argCache[kind]
        val now = System.currentTimeMillis()
        if (cached == null || now - cached.at > TTL_MS) {
            probe(kind.name) {
                val out = run(PROBES.getValue(kind)) ?: ""
                val values = out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
                argCache[kind] = Cached(values, System.currentTimeMillis())
                version.value++
            }
        }
        return cached?.values ?: emptyList()
    }

    private fun probe(key: String, body: suspend () -> Unit) {
        if (!inFlight.add(key)) return
        scope.launch {
            try {
                body()
            } finally {
                inFlight.remove(key)
            }
        }
    }

    private suspend fun run(command: String): String? =
        withContext(Dispatchers.IO) { runCatching { exec(command) }.getOrNull() }

    private companion object {
        const val TTL_MS = 15_000L

        val PROBES = mapOf(
            ArgKind.TMUX_SESSION to "tmux list-sessions -F '#S' 2>/dev/null",
            ArgKind.DOCKER_CONTAINER to "docker ps --format '{{.Names}}' 2>/dev/null",
            ArgKind.SYSTEMD_UNIT to
                "systemctl list-units --type=service --all --no-legend --plain 2>/dev/null | awk '{print \$1}'",
        )
    }
}
