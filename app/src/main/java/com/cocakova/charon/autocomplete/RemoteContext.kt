package com.cocakova.charon.autocomplete

import com.cocakova.charon.ssh.shellQuote
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

    /** Every executable on the host's PATH, sorted — prefix runs binary-search this. */
    @Volatile
    var commands: List<String> = emptyList()
        private set

    /** The same inventory as a set, built once per landing — membership checks run
     *  on every keystroke and must never re-hash thousands of names. */
    @Volatile
    var commandSet: Set<String> = emptySet()
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
                commandSet = HashSet(names)
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
                // A failed channel is not an answer — leave the cache alone so the
                // next request retries, rather than caching "no sessions" as truth.
                val out = run(PROBES.getValue(kind)) ?: return@probe
                val values = out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
                    // ssh_config Host lines carry match patterns too; only real names complete.
                    .filter { kind != ArgKind.SSH_HOST || it.none { c -> c in "*?!" } }
                    .toList()
                argCache[kind] = Cached(values, System.currentTimeMillis())
                version.value++
            }
        }
        return cached?.values ?: emptyList()
    }

    /** True once [kind] has been answered by the host (even with an empty list) —
     *  the moment the live host, not stale history, is the authority on values. */
    fun landed(kind: ArgKind): Boolean = argCache.containsKey(kind)

    private val pathCache = ConcurrentHashMap<String, Cached>()

    /**
     * Entries of a remote directory (`ls -1Ap`: dotfiles included, directories
     * marked with a trailing `/`), for completing absolute and `~/` paths — the
     * only paths knowable without tracking the shell's cwd. Same contract as
     * [args]: cache now, refresh in the background, never block a keystroke.
     */
    fun pathEntries(dir: String): List<String> {
        val cached = pathCache[dir]
        val now = System.currentTimeMillis()
        if (cached == null || now - cached.at > TTL_MS) {
            probe("path:$dir") {
                val out = run(listCommand(dir)) ?: return@probe
                val entries = out.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .take(MAX_DIR_ENTRIES)
                    .toList()
                pathCache[dir] = Cached(entries, System.currentTimeMillis())
                // A phone types through a handful of directories, not a filesystem.
                while (pathCache.size > MAX_DIRS) {
                    pathCache.entries.minByOrNull { it.value.at }?.let { pathCache.remove(it.key) }
                        ?: break
                }
                version.value++
            }
        }
        return cached?.values ?: emptyList()
    }

    /** `~/` must stay outside the quotes so the remote shell expands it. */
    private fun listCommand(dir: String): String = when {
        dir.startsWith("~/") -> "ls -1Ap -- ~/" + shellQuote(dir.removePrefix("~/"))
        else -> "ls -1Ap -- " + shellQuote(dir)
    } + " 2>/dev/null"

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
        const val MAX_DIR_ENTRIES = 200
        const val MAX_DIRS = 8

        val PROBES = mapOf(
            ArgKind.TMUX_SESSION to "tmux list-sessions -F '#S' 2>/dev/null",
            ArgKind.DOCKER_CONTAINER to "docker ps --format '{{.Names}}' 2>/dev/null",
            ArgKind.SYSTEMD_UNIT to
                "systemctl list-units --type=service --all --no-legend --plain 2>/dev/null | awk '{print \$1}'",
            // The remote's own outbound ssh book — you ssh onward *from* the host.
            ArgKind.SSH_HOST to
                "awk 'tolower(\$1)==\"host\"{for(i=2;i<=NF;i++)print \$i}' ~/.ssh/config 2>/dev/null",
        )
    }
}
