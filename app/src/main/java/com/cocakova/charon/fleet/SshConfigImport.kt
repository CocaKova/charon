package com.cocakova.charon.fleet

/**
 * Reading an OpenSSH client config (`~/.ssh/config`) into ships to moor. Pure text
 * in, sightings out, so it is tested against the shapes real configs take.
 *
 * Semantics follow ssh_config(5) where it matters for a mooring: options are taken
 * from every `Host` block whose patterns match the alias, **first value wins** in
 * file order (so a `Host *` block at the top sets defaults that later blocks can't
 * override, exactly as ssh itself reads it); `!pattern` negates; `%h` in HostName
 * is the alias. Wildcard-only blocks are defaults, never ships. `Match` blocks and
 * `Include` can't be followed from a phone and are reported, not guessed at.
 */
object SshConfigImport {

    /** One `Host` alias, resolved. */
    data class Entry(
        val alias: String,
        val hostName: String,
        val user: String?,
        val port: Int?,
        /** The first hop of ProxyJump (or a ProxyCommand-free alias), as written. */
        val proxyJump: String?,
        /** IdentityFile paths can't cross from the far side; named so the sheet can say so. */
        val identityFile: String?,
        /** ForwardAgent yes: lend the key onward (only once the mooring has a key). */
        val forwardAgent: Boolean = false,
    )

    data class Result(val entries: List<Entry>, val notes: List<String>)

    private class Block(val patterns: List<String>, val options: MutableList<Pair<String, String>> = ArrayList())

    fun parse(text: String): Result {
        val blocks = ArrayList<Block>()
        val notes = LinkedHashSet<String>()
        var current: Block? = Block(listOf("*")) // options before any Host apply to all
        val global = current!!
        blocks += global
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val (key, value) = splitOption(line) ?: continue
            when (key.lowercase()) {
                "host" -> {
                    current = Block(tokens(value))
                    blocks += current
                }
                "match" -> {
                    notes += "Match blocks are skipped — their conditions can't be checked from here"
                    current = null
                }
                "include" -> notes += "Include lines are skipped — the included files stay on the far shore"
                else -> current?.options?.add(key.lowercase() to unquote(value))
            }
        }

        val aliases = LinkedHashSet<String>()
        for (b in blocks) {
            for (p in b.patterns) {
                if (p.startsWith("!") || p.any { it == '*' || it == '?' }) continue
                aliases += p
            }
        }
        val entries = aliases.mapNotNull { alias ->
            val opts = HashMap<String, String>()
            for (b in blocks) {
                if (!matches(alias, b.patterns)) continue
                for ((k, v) in b.options) opts.putIfAbsent(k, v)
            }
            val hostName = (opts["hostname"] ?: alias).replace("%h", alias).replace("%%", "%")
            if (hostName.isBlank() || hostName.any { it.isWhitespace() }) return@mapNotNull null
            val port = opts["port"]?.toIntOrNull()?.takeIf { it in 1..65535 }
            val jump = opts["proxyjump"]?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() && !it.equals("none", true) }
            if (opts.containsKey("proxycommand")) {
                notes += "ProxyCommand can't run on a phone — those hosts moor without it"
            }
            val identity = opts["identityfile"]
            if (identity != null) notes += "IdentityFile keys stay on the far shore — import them in the keys, then attach"
            Entry(
                alias = alias,
                hostName = hostName,
                user = opts["user"]?.takeIf { it.isNotBlank() && !it.contains('%') },
                port = port,
                proxyJump = jump,
                identityFile = identity,
                forwardAgent = opts["forwardagent"]?.lowercase() == "yes",
            )
        }
        return Result(entries, notes.toList())
    }

    /**
     * `Key value`, `Key=value` or `Key = value`, the value as written. Quotes are
     * the caller's to read: a `Host` line is a list where each quoted pattern is one
     * token (`Host "quoted alias" other`), every other option is one value.
     */
    private fun splitOption(line: String): Pair<String, String>? {
        val m = OPTION.matchEntire(line) ?: return null
        return m.groupValues[1] to m.groupValues[2].trim()
    }

    private fun unquote(value: String): String =
        if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) value.substring(1, value.length - 1) else value

    private val OPTION = Regex("""^([A-Za-z][A-Za-z0-9]*)\s*(?:=\s*|\s+)(.*)$""")

    private fun tokens(value: String): List<String> =
        Regex("\"([^\"]*)\"|(\\S+)").findAll(value).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()

    /** ssh_config pattern lists: any positive match, and no negated one. */
    internal fun matches(alias: String, patterns: List<String>): Boolean {
        var hit = false
        for (p in patterns) {
            if (p.startsWith("!")) {
                if (glob(p.substring(1), alias)) return false
            } else if (glob(p, alias)) {
                hit = true
            }
        }
        return hit
    }

    private fun glob(pattern: String, text: String): Boolean {
        val re = buildString {
            for (c in pattern) {
                when (c) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(c.toString()))
                }
            }
        }
        return Regex(re, RegexOption.IGNORE_CASE).matches(text)
    }

    /** The jump written in a config (`user@host:port` or an alias), as a hail target. */
    fun jumpTarget(spec: String): HailTarget? = Hail.parse(spec)

    /** A mooring already in the vault, as far as a jump needs to know it. */
    data class Moored(val id: String, val name: String, val host: String, val port: Int, val username: String)

    /** One ship to moor from the config: its draft fields, and the jump if it found one. */
    data class Mooring(
        val id: String,
        val alias: String,
        val host: String,
        val port: Int,
        val username: String,
        val forwardAgent: Boolean,
        /** The id to cross by way of: another ship in this same mooring, or one already moored. */
        val jumpHostId: String?,
    )

    data class Plan(val moorings: List<Mooring>, val notes: List<String>)

    /**
     * Turn the chosen entries into moorings. Each keeps its own User and Port; a
     * missing one takes the sheet's shared value. A ProxyJump finds its shore among
     * the ships moored in the same breath first (by alias), then among the moorings
     * already kept (by name, or by address with any user/port it names); a jump
     * that finds no shore is named in a note and that ship crosses straight until
     * one is set. Pure, so the whole decision is tested without a sheet.
     */
    fun plan(
        chosen: List<Entry>,
        moored: List<Moored>,
        sharedUser: String,
        sharedPort: Int,
        newId: () -> String,
    ): Plan {
        val ids = chosen.associate { it.alias to newId() }
        val notes = ArrayList<String>()
        val moorings = chosen.map { e ->
            val jumpId = e.proxyJump?.let { spec ->
                ids[spec] ?: findMoored(spec, moored)
                    ?: null.also { notes += "${e.alias}: no mooring for the jump \"$spec\" — it crosses straight until you set one" }
            }
            Mooring(
                id = ids.getValue(e.alias),
                alias = e.alias,
                host = e.hostName,
                port = e.port ?: sharedPort,
                username = e.user ?: sharedUser,
                forwardAgent = e.forwardAgent,
                jumpHostId = jumpId,
            )
        }
        return Plan(moorings, notes)
    }

    private fun findMoored(spec: String, moored: List<Moored>): String? {
        moored.firstOrNull { it.name.equals(spec, ignoreCase = true) }?.let { return it.id }
        val t = Hail.parse(spec) ?: return null
        return moored.firstOrNull { m ->
            m.host.equals(t.host, ignoreCase = true) &&
                (t.port == 22 || t.port == m.port) &&
                (t.user == null || t.user == m.username)
        }?.id
    }
}
