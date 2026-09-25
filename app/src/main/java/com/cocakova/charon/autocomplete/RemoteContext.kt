package com.cocakova.charon.autocomplete

import com.cocakova.charon.ssh.shellQuote
import com.cocakova.charon.terminal.ShellCwd
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * What the connected host can actually do, probed live over a silent exec channel
 * (never through the user's PTY): every executable on PATH, the tmux sessions that
 * are running, docker containers, systemd units — and, once a rigged shell says
 * where it stands (OSC 7), the files and branches there. This is the difference between
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
    fun args(kind: ArgKind, cwd: String? = null): List<String> {
        if (kind == ArgKind.NONE || kind.isPath) return emptyList()
        if (kind.needsCwd) return refs(kind, cwd ?: return emptyList())
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
     *  the moment the live host, not stale history, is the authority on values.
     *  Per-directory kinds (branches) have landed only for the [cwd] asked about. */
    fun landed(kind: ArgKind, cwd: String? = null): Boolean =
        if (kind.needsCwd) cwd != null && refCache.containsKey(cwd) else argCache.containsKey(kind)

    // ---- where the shell stands (OSC 7) --------------------------------------------

    /** The host's own name (`hostname`), once probed — what a trustworthy OSC 7
     *  report must name, if it names a host at all. */
    @Volatile
    var hostName: String? = null
        private set

    /** Learn the host's name. Called once per (re)connect, beside the inventory. */
    fun refreshHost() {
        probe("hostname") {
            val name = run("hostname 2>/dev/null || uname -n")?.trim()?.lineSequence()?.firstOrNull()
            if (!name.isNullOrBlank()) {
                hostName = name
                version.value++
            }
        }
    }

    /**
     * The reported cwd, if it is a directory on *this* host — else null. A shell
     * you ssh'd onward to reports its own paths, and every probe here runs on the
     * host this session is connected to, so a report naming another machine is
     * ignored. Short names compare (fish reports `spark`, bash `spark.lan`); a
     * report with no host, or `localhost`, is taken as ours — the shell can't be
     * checked, but it also isn't claiming to be elsewhere.
     */
    fun resolveCwd(reported: ShellCwd?): String? {
        if (reported == null) return null
        val host = reported.host
        if (host.isEmpty() || host.equals("localhost", ignoreCase = true)) return reported.path
        val mine = hostName ?: return null     // can't vouch for it yet
        return reported.path.takeIf { host.shortName().equals(mine.shortName(), ignoreCase = true) }
    }

    private fun String.shortName() = substringBefore('.')

    // ---- directory listings ----------------------------------------------------------

    private val pathCache = ConcurrentHashMap<String, Cached>()

    /**
     * Entries of a remote directory (`ls -1Ap`: dotfiles included, directories
     * marked with a trailing `/`), for completing paths: absolute and `~/` ones as
     * typed, relative ones joined onto the shell's reported cwd by the caller. Same
     * contract as [args]: cache now, refresh in the background, never block a
     * keystroke.
     */
    fun pathEntries(dir: String): List<String> =
        listing(pathCache, dir, "path:$dir", listCommand(dir), MAX_DIR_ENTRIES)

    // ---- branches of the repo the shell stands in ------------------------------------

    /** Full refnames per cwd (`refs/heads/main`, `refs/remotes/origin/feat`); each
     *  branch kind is a view over the one probe. */
    private val refCache = ConcurrentHashMap<String, Cached>()

    private fun refs(kind: ArgKind, cwd: String): List<String> {
        // Full names, not %(refname:short): short names can't tell a local branch
        // called `origin/x` from the remote-tracking one. Not a repo → git fails
        // quietly → an empty answer, which is the truth: no branches here.
        val command = "git -C " + shellQuote(cwd) +
            " for-each-ref --format='%(refname)' refs/heads refs/remotes 2>/dev/null"
        val all = listing(refCache, cwd, "refs:$cwd", command, MAX_REFS)
        val heads = all.mapNotNull { it.removePrefixOrNull("refs/heads/") }
        if (kind == ArgKind.GIT_LOCAL_BRANCH) return heads
        val remotes = all.mapNotNull { it.removePrefixOrNull("refs/remotes/") }
            .filter { '/' in it && !it.endsWith("/HEAD") }
        return when (kind) {
            ArgKind.GIT_BRANCH -> (heads + remotes.map { it.substringAfter('/') }).distinct()
            else -> heads + remotes
        }
    }

    private fun String.removePrefixOrNull(prefix: String) =
        if (startsWith(prefix)) substring(prefix.length) else null

    /**
     * A cached line listing keyed per directory — the path and branch caches share
     * this contract: the cache answers now, a stale or missing entry refreshes in
     * the background, a failed channel caches nothing, and only the last [MAX_DIRS]
     * directories are kept.
     */
    private fun listing(
        cache: ConcurrentHashMap<String, Cached>,
        key: String,
        probeKey: String,
        command: String,
        cap: Int,
    ): List<String> {
        val cached = cache[key]
        val now = System.currentTimeMillis()
        if (cached == null || now - cached.at > TTL_MS) {
            probe(probeKey) {
                val out = run(command) ?: return@probe
                val entries = out.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .take(cap)
                    .toList()
                cache[key] = Cached(entries, System.currentTimeMillis())
                // A phone types through a handful of directories, not a filesystem.
                while (cache.size > MAX_DIRS) {
                    cache.entries.minByOrNull { it.value.at }?.let { cache.remove(it.key) }
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
        const val MAX_REFS = 400

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
