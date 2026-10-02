package com.cocakova.charon.ssh

import kotlinx.coroutines.CompletableDeferred

/**
 * Keyboard-interactive auth: the far shore asks its own questions (PAM, a one-time
 * code, a second password). The connect thread parks on the traveller's answer the
 * same way it parks on a trust decision. Answers are never logged, never stored.
 */
fun interface AuthPrompter {
    /** Null = the traveller turned back; the server is answered with nothing. */
    fun answer(challenge: Challenge): String?
}

/** One question from the server, with whatever it said about why it asks. */
data class Challenge(
    val host: String,
    val name: String,
    val instruction: String,
    val prompt: String,
    /** The server allows the answer to be shown while typed (a username, not a code). */
    val echo: Boolean,
) {
    override fun toString(): String = "Challenge($host, prompt=${prompt.length}ch)"
}

/** A question in flight: the connect thread waits until the UI answers. */
class PendingChallenge(val challenge: Challenge) {
    internal val reply = CompletableDeferred<String?>()
    fun answer(text: String?) {
        reply.complete(text)
    }
}

/**
 * Who answers which question, so a stored password still crosses a server that
 * speaks only keyboard-interactive (PAM): the first prompt that asks for a password
 * gets it, once; every other prompt — a code, a re-ask after a refusal — goes to
 * the traveller. Pure, so the decision is tested without a server.
 */
class ChallengeResponder(
    private val password: String?,
    private val ask: ((Challenge) -> String?)?,
) {
    private var passwordSpent = false

    fun respond(challenge: Challenge): String {
        if (!passwordSpent && password != null && !challenge.echo && asksForPassword(challenge.prompt)) {
            passwordSpent = true
            return password
        }
        return ask?.invoke(challenge) ?: ""
    }

    private fun asksForPassword(prompt: String): Boolean {
        val p = prompt.lowercase()
        return "password" in p || "passphrase" in p
    }
}
