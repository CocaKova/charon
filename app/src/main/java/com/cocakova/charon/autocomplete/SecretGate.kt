package com.cocakova.charon.autocomplete

/**
 * The second wall around the autofill history: even a genuine command line is
 * never learned when it visibly carries a secret. The toll guards hidden prompts;
 * this guards the secrets typed in the open — `PGPASSWORD=… psql`, `curl -H
 * 'Authorization: Bearer …'`, `vault login --token=…` — which would otherwise
 * come back as suggestion chips for anyone holding the phone.
 *
 * The rule is structural, not a blocklist of incidents: a token whose *name*
 * declares secrecy (an env assignment or long flag named with pass/token/secret/
 * cred/auth), or a credential scheme announcing itself (`Authorization:`,
 * `Bearer <blob>`). Values are never inspected — entropy guessing produces false
 * alarms, and a missed exotic secret costs one unlearned history line, while a
 * resurfaced one costs the secret. Applied at record time and again at suggest
 * time, so lines learned before this wall existed go silent too.
 */
object SecretGate {

    /** Whether [line] visibly carries a secret and must never be learned or offered. */
    fun carriesSecret(line: String): Boolean {
        for (token in line.trim().split(WS)) {
            // NAME=value / NAME+=value — judged by the name left of the `=`.
            ASSIGN.find(token)?.let {
                if (declaresSecrecy(it.groupValues[1])) return true
            }
            // --long-flag or --long-flag=value — judged by the flag's own name.
            if (token.startsWith("--") && declaresSecrecy(token.substring(2).substringBefore('='))) {
                return true
            }
        }
        return AUTH_SCHEME.containsMatchIn(line)
    }

    private fun declaresSecrecy(name: String): Boolean {
        val n = name.lowercase()
        // `author` contains `auth` but declares a person, not a credential
        // (`git log --author=…` must keep autofilling).
        if ("author" in n) return false
        return SECRET_ROOTS.any { it in n }
    }

    private val WS = Regex("\\s+")
    private val ASSIGN = Regex("^([A-Za-z_][A-Za-z0-9_]*)\\+?=")

    /** HTTP credential headers and bearer blobs, however they're quoted. */
    private val AUTH_SCHEME = Regex(
        "(?i)(authorization\\s*:|\\bbearer\\s+[A-Za-z0-9._~+/=-]{8,})",
    )

    /** Names that declare their value secret. `key` alone is deliberately absent —
     *  it names files and identifiers (`ssh-keygen`, `--key-file`) far more often
     *  than raw secrets, and key *files* are paths, not credentials. */
    private val SECRET_ROOTS =
        listOf("pass", "pwd", "token", "secret", "cred", "auth", "apikey", "api-key", "api_key")
}
