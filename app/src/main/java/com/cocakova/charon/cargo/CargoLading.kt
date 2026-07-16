package com.cocakova.charon.cargo

/**
 * The lading: recognizing when a crossing is hauling cargo — a package manager
 * fetching and installing — so the terminal can show the barge being loaded instead
 * of a wall of scrolling text.
 *
 * Two grammars, both documented interfaces rather than guesswork:
 *  - [match] reads the submitted command line (the same line the autofill
 *    reconstructor commits) against each manager's verb grammar.
 *  - [glean] reads the bottom of the live screen against the managers' output
 *    formats — the verbs they print per package and the percent of their
 *    progress meters.
 */
object CargoLading {

    /** Managers whose verbs take a subcommand word (`apt install`, `pip install`). */
    private val wordVerbs: Map<String, Set<String>> = mapOf(
        "apt" to setOf("install", "reinstall", "remove", "purge", "upgrade", "full-upgrade", "update", "autoremove"),
        "apt-get" to setOf("install", "reinstall", "remove", "purge", "upgrade", "dist-upgrade", "update", "autoremove"),
        "aptitude" to setOf("install", "remove", "upgrade", "full-upgrade", "update"),
        "dnf" to setOf("install", "reinstall", "remove", "erase", "upgrade", "update", "downgrade"),
        "yum" to setOf("install", "reinstall", "remove", "erase", "upgrade", "update"),
        "zypper" to setOf("install", "in", "remove", "rm", "update", "up", "dup"),
        "apk" to setOf("add", "del", "upgrade", "fix"),
        "pip" to setOf("install", "uninstall", "download"),
        "pip3" to setOf("install", "uninstall", "download"),
        "npm" to setOf("install", "i", "ci", "update", "uninstall", "add"),
        "pnpm" to setOf("install", "i", "add", "update", "remove"),
        "yarn" to setOf("install", "add", "upgrade", "remove"),
        "cargo" to setOf("install", "build", "update"),
        "gem" to setOf("install", "update", "uninstall"),
        "brew" to setOf("install", "reinstall", "upgrade", "uninstall"),
        "snap" to setOf("install", "refresh", "remove"),
        "flatpak" to setOf("install", "update", "uninstall"),
        "go" to setOf("install"),
    )

    /** Managers whose verbs ride in the flag cluster (`pacman -Syu`, `dpkg -i`). */
    private val dashVerbs: Map<String, List<String>> = mapOf(
        "pacman" to listOf("-S", "-U", "-R"),
        "dpkg" to listOf("-i", "--install", "-r", "--remove"),
        "rpm" to listOf("-i", "-U", "-e"),
    )

    /**
     * The package manager a command line invokes for a cargo run, or null.
     * `sudo`, env assignments, and path prefixes are transparent.
     */
    fun match(command: String): String? {
        val tokens = command.trim().split(Regex("\\s+")).toMutableList()
        // Env assignments and sudo (with its own flags) precede the real command.
        while (tokens.isNotEmpty() && tokens[0].matches(Regex("[A-Za-z_][A-Za-z0-9_]*=.*"))) tokens.removeAt(0)
        if (tokens.firstOrNull() == "sudo" || tokens.firstOrNull() == "doas") {
            tokens.removeAt(0)
            while (tokens.isNotEmpty() && tokens[0].startsWith("-")) tokens.removeAt(0)
        }
        val name = tokens.firstOrNull()?.substringAfterLast('/') ?: return null
        val rest = tokens.drop(1)

        wordVerbs[name]?.let { verbs ->
            val verb = rest.firstOrNull { !it.startsWith("-") } ?: return null
            return if (verb in verbs) name else null
        }
        dashVerbs[name]?.let { prefixes ->
            return if (rest.any { tok -> prefixes.any { tok.startsWith(it) } }) name else null
        }
        return null
    }

    /** What the bottom of the screen says about a lading in progress. */
    data class Glean(
        /** A manager's per-package verb line is visible — cargo is moving. */
        val verbSeen: Boolean,
        /** The package currently being handled, when a verb line names one. */
        val item: String?,
        /** The freshest percent on screen (progress meters, apt fancy bar). */
        val percent: Int?,
    )

    // Per-package verb lines the managers print, with the package as group 1.
    // dnf indents its transaction lines; pacman lowercases; pip says Collecting.
    private val verbLines = listOf(
        Regex("^Unpacking ([^ ]+)"),
        Regex("^Setting up ([^ ]+)"),
        Regex("^Preparing to unpack .*?([^ /]+)\\.deb"),
        Regex("^Get:\\d+ \\S+ \\S+ \\S+ ([^ ]+)"),
        Regex("^Processing triggers for ([^ ]+)"),
        Regex("^Selecting previously unselected package ([^ .]+)"),
        Regex("^\\s*(?:Installing|Upgrading|Downgrading)\\s*:?\\s+([A-Za-z0-9][^ ]*)"),
        Regex("^(?:installing|upgrading|reinstalling) ([^ .]+)"),
        Regex("^Collecting ([^ =<>!;\\[]+)"),
        Regex("^\\s*Downloading ([^ ]+)"),
        Regex("^Installing collected packages: ([^,\\s]+)"),
        Regex("^Successfully installed (\\S+)"),
        Regex("^\\s+Compiling ([^ ]+)"),
        Regex("^added (\\d+ packages?)"),
    )

    // Verb lines that mark activity without naming a package.
    private val pulseLines = listOf(
        Regex("^(Reading package lists|Building dependency tree|Reading state information|Fetched |Extracting |Calculating upgrade)"),
        Regex("^(resolving dependencies|looking for conflicting packages|checking keys in keyring|loading packages|:: Retrieving packages)"),
        Regex("^(Downloading Packages|Verifying |Running transaction|Transaction Summary|Dependencies resolved)"),
        Regex("^(Building wheels? for|Resolving dependencies|Progress: \\[)"),
    )

    private val percentRe = Regex("(\\d{1,3})\\s?%")

    fun glean(rows: List<String>): Glean {
        var verbSeen = false
        var item: String? = null
        var percent: Int? = null
        for (row in rows) {
            if (row.isEmpty()) continue
            for (re in verbLines) {
                val m = re.find(row) ?: continue
                verbSeen = true
                item = m.groupValues[1].trimEnd(':', ',', '.')
                break
            }
            if (!verbSeen && pulseLines.any { it.containsMatchIn(row) }) verbSeen = true
            percentRe.findAll(row).lastOrNull()?.let {
                val p = it.groupValues[1].toInt()
                if (p in 0..100) percent = p
            }
        }
        return Glean(verbSeen, item, percent)
    }
}
