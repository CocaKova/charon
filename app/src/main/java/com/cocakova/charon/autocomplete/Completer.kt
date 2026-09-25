package com.cocakova.charon.autocomplete

/**
 * One inline offer. [display] is what the chip shows, its first [matched] chars
 * being what the user already typed (rendered dim); [insert] is exactly the bytes
 * to type on accept — only the missing tail, so the remote echoes it in place.
 */
data class Suggestion(
    val display: String,
    val matched: Int,
    val insert: String,
    /** True when [display] is a remembered full line — the ones the user may ask
     *  the river to forget with a long-press. */
    val fromHistory: Boolean = false,
)

/**
 * The smart-autofill brain: blends three sources into ranked inline suggestions —
 *
 *  1. **Your history** — full past command lines that continue what's typed
 *     (most personal, ranked first). Callers feed it history already screened by
 *     [CommandGate], so a sentence typed into a remote chat never suggests back.
 *  2. **Command grammar** — curated [Specs]: subcommands, flags, and *live* argument
 *     values from the host ([RemoteContext]: running tmux sessions, containers,
 *     units). `tmux at` → `attach`; `tmux attach -t ` → the sessions running now.
 *  3. **The host's PATH** — every installed executable, so `tm` finds `tmux` even
 *     with empty history, and never offers a tool the host doesn't have.
 *
 * Grammar completion follows the shell's own structure: only the segment after the
 * last `&&`/`||`/`|`/`;` completes as a fresh command, and env assignments plus
 * `sudo`/`doas` are transparent prefixes.
 *
 * When the shell has said where it stands ([cwd], from OSC 7 — already checked to
 * be a directory on this host), bare names in file positions complete from that
 * directory, and git positions offer the repo's branches. Without it, only the
 * absolute and `~/` paths that need no cwd complete.
 *
 * Pure and synchronous — dynamic fetches happen in RemoteContext off-thread; this
 * only reads caches (pre-built set + sorted list), so it can run on every keystroke.
 */
object Completer {

    fun complete(
        draft: String,
        history: List<String>,
        remote: RemoteContext?,
        cwd: String? = null,
        max: Int = 6,
    ): List<Suggestion> {
        if (draft.isBlank()) return emptyList()

        // Only the segment after the last connector completes as a fresh command
        // (`ssh spark && tm` → tmux); full-line history recall still sees everything.
        val cut = CONNECTOR.findAll(draft).lastOrNull()?.let { it.range.last + 1 } ?: 0
        val active = draft.substring(cut).trimStart()

        // Tokenise the active segment: env assignments and sudo/doas are transparent
        // (complete what follows as a fresh command).
        val endsOpen = !draft.endsWith(' ')
        var words = active.split(WS).filter { it.isNotEmpty() }
        while (words.isNotEmpty() && (words.size > 1 || !endsOpen) && isWrapper(words.first())) {
            words = words.drop(1)
        }
        val partial = if (endsOpen) words.lastOrNull().orEmpty() else ""
        val complete = if (endsOpen) words.dropLast(1) else words

        // Grammar + live host first, so we know whether this is a *value position* —
        // a spot a dynamic argument kind governs (`-t ` → tmux sessions).
        val grammar = LinkedHashMap<String, Suggestion>()
        val position = if (complete.isEmpty()) {
            completeCommandName(partial, history, remote, grammar)
            // A command named by its path (`./deploy.sh`, `bin/run`) is a file too.
            Position(null, if ('/' in partial) ArgKind.PATH else ArgKind.NONE)
        } else {
            completeArguments(complete, partial, remote, cwd, grammar)
        }
        val valueKind = position.value

        // In a *closed-world* value position the live host has answered, it is the
        // authority: history recall would resurrect session/container names that no
        // longer exist, ranked above the ones that do. Open-world kinds (ssh hosts)
        // keep history as a legitimate voice — the probe knows only a subset.
        val hostRules = valueKind != null && valueKind.closedWorld &&
            remote?.landed(valueKind, cwd) == true

        // Keyed by display so a history line and a token offer of the same word
        // become one chip; the first writer wins the insert.
        val out = LinkedHashMap<String, Suggestion>()
        if (!hostRules) {
            // History lines extending the draft — the whole line first, then the
            // command being chained after a connector.
            historyMatches(history, draft, 3, out)
            if (cut > 0) historyMatches(history, active, 2, out)
        }
        completePath(partial, remote, out)
        grammar.values.forEach { offer(out, it) }
        // Relative names rank after the grammar: a branch, subcommand or live value
        // is the more specific answer where both could apply (`git checkout ma`).
        completeRelative(partial, position.files, remote, cwd, out)
        return out.values.take(max)
    }

    /** What the cursor's position takes: the dynamic kind governing it (for the
     *  closed-world rule), and whether — and which — host files belong there. */
    private data class Position(val value: ArgKind?, val files: ArgKind)

    // ---- paths: absolute and ~/ need no cwd; relative ones need the shell's word ----

    /** Complete `/abs/…` and `~/…` tokens from a live listing of their directory —
     *  works in any argument position, for any command, spec'd or not. Directories
     *  cascade (`etc/` keeps completing); files close the token with a space. */
    private fun completePath(
        partial: String,
        remote: RemoteContext?,
        out: MutableMap<String, Suggestion>,
    ) {
        if (remote == null) return
        if (!partial.startsWith("/") && !partial.startsWith("~/")) return
        val slash = partial.lastIndexOf('/')
        val dir = partial.substring(0, slash + 1)
        val base = partial.substring(slash + 1)
        remote.pathEntries(dir).asSequence()
            .filter { it.startsWith(base) && it != base }
            .take(6)
            .forEach { entry ->
                val insert = entry.substring(base.length) + if (entry.endsWith("/")) "" else " "
                offer(out, Suggestion(entry, base.length, insert))
            }
    }

    /**
     * Complete a bare name (`no`, `src/ma`, `../lib/`) from the shell's reported
     * [cwd] — only in a position that takes files ([files] is PATH or DIRECTORY),
     * never for a flag, and never for a token the shell would rewrite before it
     * names a file (quotes, `$VAR`, globs, `--opt=`, `host:path`). The listing is
     * keyed by the joined absolute directory, so it shares [RemoteContext]'s path
     * cache with absolute completion. Dotfiles wait until you type the dot; the
     * inserted tail is backslash-escaped so a name with a space stays one word.
     */
    private fun completeRelative(
        partial: String,
        files: ArgKind,
        remote: RemoteContext?,
        cwd: String?,
        out: MutableMap<String, Suggestion>,
    ) {
        if (remote == null || cwd == null || !files.isPath) return
        if (partial.startsWith("/") || partial.startsWith("~") || partial.startsWith("-")) return
        if (partial.any { it in NOT_LITERAL }) return
        val slash = partial.lastIndexOf('/')
        val dir = partial.substring(0, slash + 1)
        val base = partial.substring(slash + 1)
        // `./src/` and `src/` are one directory — and one cache entry.
        var rel = dir
        while (rel.startsWith("./")) rel = rel.substring(2)
        remote.pathEntries(cwd.trimEnd('/') + "/" + rel).asSequence()
            .filter { it.startsWith(base) && it != base }
            .filter { base.startsWith(".") || !it.startsWith(".") }
            .filter { files != ArgKind.DIRECTORY || it.endsWith("/") }
            .take(6)
            .forEach { entry ->
                val tail = shellEscape(entry.substring(base.length))
                offer(out, Suggestion(entry, base.length, tail + if (entry.endsWith("/")) "" else " "))
            }
    }

    /** Backslash the characters a shell would otherwise split or expand on. */
    private fun shellEscape(s: String): String =
        if (s.none { it in ESCAPE }) s
        else buildString { s.forEach { if (it in ESCAPE) append('\\'); append(it) } }

    private fun historyMatches(
        history: List<String>,
        prefix: String,
        take: Int,
        out: MutableMap<String, Suggestion>,
    ) {
        if (prefix.length < 2) return
        history.asSequence()
            .filter { it.length > prefix.length && it.startsWith(prefix) }
            .take(take)
            .forEach {
                offer(out, Suggestion(it, prefix.length, it.substring(prefix.length), fromHistory = true))
            }
    }

    private fun isWrapper(word: String) =
        word == "sudo" || word == "doas" || ASSIGN.matches(word)

    // ---- first token: the command itself -------------------------------------------

    private fun completeCommandName(
        partial: String,
        history: List<String>,
        remote: RemoteContext?,
        out: MutableMap<String, Suggestion>,
    ) {
        if (partial.isEmpty()) return
        val installed = remote?.commandSet.orEmpty()

        // Specs the host actually has (or all of them until the inventory lands).
        Specs.all.keys.asSequence()
            .filter { it.startsWith(partial) && it != partial }
            .filter { installed.isEmpty() || it in installed }
            .sorted()
            .forEach { offer(out, token(it, partial)) }

        // Commands you've begun lines with before.
        history.asSequence()
            .mapNotNull { it.substringBefore(' ').takeIf { w -> w.startsWith(partial) && w != partial } }
            .distinct().take(3)
            .forEach { offer(out, token(it, partial)) }

        // Everything else installed. The inventory is sorted, so jump straight to
        // the prefix run instead of scanning a few thousand names per keystroke.
        prefixRun(remote?.commands.orEmpty(), partial)
            .filter { it != partial }
            .take(8)
            .forEach { offer(out, token(it, partial)) }
    }

    /** The contiguous run of entries in [sorted] that start with [prefix]. */
    private fun prefixRun(sorted: List<String>, prefix: String): Sequence<String> {
        if (sorted.isEmpty()) return emptySequence()
        var lo = sorted.binarySearch(prefix)
        if (lo < 0) lo = -lo - 1
        return generateSequence(lo) { it + 1 }
            .takeWhile { it < sorted.size && sorted[it].startsWith(prefix) }
            .map { sorted[it] }
    }

    // ---- later tokens: subcommands, flags, live argument values --------------------

    /** Offers completions and returns the [Position]: the dynamic [ArgKind] governing
     *  it (a flag awaiting its value, or a dynamic positional) and the files it takes. */
    private fun completeArguments(
        complete: List<String>,
        partial: String,
        remote: RemoteContext?,
        cwd: String?,
        out: MutableMap<String, Suggestion>,
    ): Position {
        // No grammar: the shell's own default is that arguments are files — except
        // for the handful of everyday commands whose arguments plainly aren't.
        var spec = Specs.all[complete.first()]
            ?: return Position(null, if (complete.first() in NOT_FILES) ArgKind.NONE else ArgKind.PATH)
        var argKind = spec.argKind
        var pendingFlagKind: ArgKind? = null

        // Walk the completed tokens through the grammar.
        for (tok in complete.drop(1)) {
            pendingFlagKind = null
            val sub = spec.subs.firstOrNull { it.name == tok }
            if (sub != null) {
                spec = sub
                argKind = sub.argKind
                continue
            }
            spec.flagArgs[tok]?.let { pendingFlagKind = it }
        }

        // A flag awaiting its value pins the kind (`-t ` → tmux sessions, nothing else).
        val kinds = pendingFlagKind?.let { listOf(it) }
            ?: listOfNotNull(argKind.takeIf { it != ArgKind.NONE })

        for (kind in kinds) {
            if (kind.isPath) continue // listings, not values: completeRelative's job
            // `user@host`: the user half is the traveller's own — match host names
            // past the @ and keep the prefix in the offer.
            val at = if (kind == ArgKind.SSH_HOST) partial.lastIndexOf('@') else -1
            val user = if (at >= 0) partial.substring(0, at + 1) else ""
            val base = if (at >= 0) partial.substring(at + 1) else partial
            remote?.args(kind, cwd).orEmpty().asSequence()
                .filter { it.startsWith(base) && it != base }
                .take(4)
                .forEach {
                    offer(out, Suggestion(user + it, partial.length, it.substring(base.length) + " "))
                }
        }
        val governing = kinds.firstOrNull()
        // Value position: only values make sense — files only if the flag takes one.
        pendingFlagKind?.let { return Position(governing, it.takeIf { k -> k.isPath } ?: ArgKind.NONE) }

        spec.subs.asSequence()
            .map { it.name }
            .filter { it.startsWith(partial) && it != partial }
            .forEach { offer(out, token(it, partial)) }
        if (partial.isEmpty() || partial.startsWith("-")) {
            spec.flags.asSequence()
                .filter { it.startsWith(partial) && it != partial }
                .forEach { offer(out, token(it, partial)) }
        }
        return Position(governing, spec.paths.takeIf { it.isPath } ?: argKind.takeIf { it.isPath } ?: ArgKind.NONE)
    }

    private fun offer(out: MutableMap<String, Suggestion>, s: Suggestion) {
        out.putIfAbsent(s.display, s)
    }

    /** A token completion: type the tail plus the space that moves to the next word. */
    private fun token(full: String, partial: String) =
        Suggestion(full, partial.length, full.substring(partial.length) + " ")

    private val WS = Regex("\\s+")
    private val CONNECTOR = Regex("\\|\\||&&|[|;]")
    private val ASSIGN = Regex("[A-Za-z_][A-Za-z0-9_]*\\+?=.*")

    /** A token carrying any of these is not a literal file name yet — the shell
     *  will unquote, expand or split it first (or it names another host's file). */
    private const val NOT_LITERAL = "'\"\\\$`*?[{=:"

    /** What a completed name must have backslashed to stay one shell word. */
    private const val ESCAPE = " \t'\"\\\$`!&;|<>()*?[]{}#"

    /** Everyday commands with no spec whose arguments are plainly not files: a bare
     *  word after them is a name, a pid, a word — never a probe of the cwd. */
    private val NOT_FILES = setOf(
        "man", "which", "whatis", "apropos", "type", "command", "help", "alias", "unalias",
        "export", "unset", "echo", "printf", "killall", "pkill", "pgrep", "pidof", "sleep",
        "whoami", "hostname", "uptime", "date", "id", "groups", "passwd", "su", "history",
        "exit", "logout", "clear", "reset", "jobs", "fg", "bg", "disown", "wait", "host",
        "dig", "nslookup", "ps", "top", "htop", "uname", "service", "useradd", "userdel",
        "usermod", "groupadd",
    )
}
