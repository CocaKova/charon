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
)

/**
 * The smart-autofill brain: blends three sources into ranked inline suggestions —
 *
 *  1. **Your history** — full past command lines that continue what's typed
 *     (most personal, ranked first).
 *  2. **Command grammar** — curated [Specs]: subcommands, flags, and *live* argument
 *     values from the host ([RemoteContext]: running tmux sessions, containers,
 *     units). `tmux at` → `attach`; `tmux attach -t ` → the sessions running now.
 *  3. **The host's PATH** — every installed executable, so `tm` finds `tmux` even
 *     with empty history, and never offers a tool the host doesn't have.
 *
 * Pure and synchronous — dynamic fetches happen in RemoteContext off-thread; this
 * only reads caches, so it can run on every keystroke.
 */
object Completer {

    fun complete(
        draft: String,
        history: List<String>,
        remote: RemoteContext?,
        max: Int = 6,
    ): List<Suggestion> {
        if (draft.isBlank()) return emptyList()
        val out = LinkedHashSet<Suggestion>()

        // 1. History lines that extend the whole draft.
        if (draft.length >= 2) {
            history.asSequence()
                .filter { it.length > draft.length && it.startsWith(draft) }
                .take(3)
                .forEach { out += Suggestion(it, draft.length, it.substring(draft.length)) }
        }

        // Tokenise: `sudo` is transparent (complete what follows as a fresh command).
        val endsOpen = !draft.endsWith(' ')
        var words = draft.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.firstOrNull() == "sudo" && (words.size > 1 || !endsOpen)) {
            words = words.drop(1)
        }
        val partial = if (endsOpen) words.lastOrNull().orEmpty() else ""
        val complete = if (endsOpen) words.dropLast(1) else words

        if (complete.isEmpty()) {
            completeCommandName(partial, history, remote, out)
        } else {
            completeArguments(complete, partial, remote, out)
        }
        return out.take(max)
    }

    // ---- first token: the command itself -------------------------------------------

    private fun completeCommandName(
        partial: String,
        history: List<String>,
        remote: RemoteContext?,
        out: MutableSet<Suggestion>,
    ) {
        if (partial.isEmpty()) return
        val installed = remote?.commands.orEmpty()
        val installedSet = if (installed.isEmpty()) null else HashSet(installed)

        // Specs the host actually has (or all of them until the inventory lands).
        Specs.all.keys.asSequence()
            .filter { it.startsWith(partial) && it != partial }
            .filter { installedSet == null || it in installedSet }
            .sorted()
            .forEach { out += token(it, partial) }

        // Commands you've begun lines with before.
        history.asSequence()
            .mapNotNull { it.substringBefore(' ').takeIf { w -> w.startsWith(partial) && w != partial } }
            .distinct().take(3)
            .forEach { out += token(it, partial) }

        // Everything else installed on the host.
        installed.asSequence()
            .filter { it.startsWith(partial) && it != partial }
            .take(8)
            .forEach { out += token(it, partial) }
    }

    // ---- later tokens: subcommands, flags, live argument values --------------------

    private fun completeArguments(
        complete: List<String>,
        partial: String,
        remote: RemoteContext?,
        out: MutableSet<Suggestion>,
    ) {
        var spec = Specs.all[complete.first()] ?: return
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
            remote?.args(kind).orEmpty().asSequence()
                .filter { it.startsWith(partial) && it != partial }
                .take(4)
                .forEach { out += token(it, partial) }
        }
        if (pendingFlagKind != null) return // value position: only values make sense

        spec.subs.asSequence()
            .map { it.name }
            .filter { it.startsWith(partial) && it != partial }
            .forEach { out += token(it, partial) }
        if (partial.isEmpty() || partial.startsWith("-")) {
            spec.flags.asSequence()
                .filter { it.startsWith(partial) && it != partial }
                .forEach { out += token(it, partial) }
        }
    }

    /** A token completion: type the tail plus the space that moves to the next word. */
    private fun token(full: String, partial: String) =
        Suggestion(full, partial.length, full.substring(partial.length) + " ")
}
