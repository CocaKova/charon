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
 * Pure and synchronous — dynamic fetches happen in RemoteContext off-thread; this
 * only reads caches (pre-built set + sorted list), so it can run on every keystroke.
 */
object Completer {

    fun complete(
        draft: String,
        history: List<String>,
        remote: RemoteContext?,
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
        val valueKind = if (complete.isEmpty()) {
            completeCommandName(partial, history, remote, grammar)
            null
        } else {
            completeArguments(complete, partial, remote, grammar)
        }

        // In a *closed-world* value position the live host has answered, it is the
        // authority: history recall would resurrect session/container names that no
        // longer exist, ranked above the ones that do. Open-world kinds (ssh hosts)
        // keep history as a legitimate voice — the probe knows only a subset.
        val hostRules = valueKind != null && valueKind.closedWorld &&
            remote?.landed(valueKind) == true

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
        return out.values.take(max)
    }

    // ---- paths: absolute and ~/ — the only ones knowable without the shell's cwd ----

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

    /** Offers completions and returns the dynamic [ArgKind] governing this position
     *  (a flag awaiting its value, or a dynamic positional), or null. */
    private fun completeArguments(
        complete: List<String>,
        partial: String,
        remote: RemoteContext?,
        out: MutableMap<String, Suggestion>,
    ): ArgKind? {
        var spec = Specs.all[complete.first()] ?: return null
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
            // `user@host`: the user half is the traveller's own — match host names
            // past the @ and keep the prefix in the offer.
            val at = if (kind == ArgKind.SSH_HOST) partial.lastIndexOf('@') else -1
            val user = if (at >= 0) partial.substring(0, at + 1) else ""
            val base = if (at >= 0) partial.substring(at + 1) else partial
            remote?.args(kind).orEmpty().asSequence()
                .filter { it.startsWith(base) && it != base }
                .take(4)
                .forEach {
                    offer(out, Suggestion(user + it, partial.length, it.substring(base.length) + " "))
                }
        }
        val governing = kinds.firstOrNull()
        if (pendingFlagKind != null) return governing // value position: only values make sense

        spec.subs.asSequence()
            .map { it.name }
            .filter { it.startsWith(partial) && it != partial }
            .forEach { offer(out, token(it, partial)) }
        if (partial.isEmpty() || partial.startsWith("-")) {
            spec.flags.asSequence()
                .filter { it.startsWith(partial) && it != partial }
                .forEach { offer(out, token(it, partial)) }
        }
        return governing
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
}
