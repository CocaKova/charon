package com.cocakova.charon.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

/** Keyboard-interactive: who answers which of the far shore's questions. */
class ChallengeResponderTest {

    private fun ask(prompt: String, echo: Boolean = false) = Challenge("h", "", "", prompt, echo)

    @Test
    fun aStoredPasswordAnswersThePamPromptOnce() {
        val asked = mutableListOf<String>()
        val r = ChallengeResponder("hunter2") { asked += it.prompt; "000111" }
        assertEquals("hunter2", r.respond(ask("Password: ")))
        // A re-ask means the stored one was refused: the traveller answers now.
        assertEquals("000111", r.respond(ask("Password: ")))
        assertEquals(listOf("Password: "), asked)
    }

    @Test
    fun aOneTimeCodeAlwaysGoesToTheTraveller() {
        val r = ChallengeResponder("hunter2") { "123456" }
        assertEquals("123456", r.respond(ask("Verification code: ")))
        assertEquals("hunter2", r.respond(ask("Password: ")))
    }

    @Test
    fun anEchoedPromptIsNeverAnsweredWithThePassword() {
        val r = ChallengeResponder("hunter2") { "jonny" }
        assertEquals("jonny", r.respond(ask("Password hint (shown): ", echo = true)))
    }

    @Test
    fun noOneToAskAnswersNothing() {
        val r = ChallengeResponder(null, null)
        assertEquals("", r.respond(ask("Verification code: ")))
    }

    @Test
    fun aChallengeNeverPrintsItsPrompt() {
        assertEquals(false, ask("secret prompt").toString().contains("secret"))
    }
}
