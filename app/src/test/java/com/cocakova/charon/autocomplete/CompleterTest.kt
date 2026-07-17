package com.cocakova.charon.autocomplete

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompleterTest {

    // ---- ranking & history ----------------------------------------------------------

    @Test
    fun historyFullLineRecallRanksFirst() {
        val out = Completer.complete("git st", listOf("git stash pop", "git status"), null)
        assertEquals("git stash pop", out.first().display)
        assertEquals(6, out.first().matched)
        assertEquals("ash pop", out.first().insert)
    }

    @Test
    fun sameWordFromHistoryAndGrammarIsOneChip() {
        val out = Completer.complete("tm", listOf("tmux"), null)
        assertEquals(1, out.count { it.display == "tmux" })
    }

    // ---- shell structure: connectors and transparent prefixes ------------------------

    @Test
    fun segmentAfterConnectorCompletesAsFreshCommand() {
        val out = Completer.complete("ssh spark && tm", emptyList(), null)
        assertTrue(out.any { it.display == "tmux" && it.insert == "ux " })
    }

    @Test
    fun pipeTailCompletesAsFreshCommand() {
        val out = Completer.complete("ps aux | gr", emptyList(), null)
        assertTrue(out.any { it.display == "grep" })
    }

    @Test
    fun historyMatchesTheChainedSegmentToo() {
        val out = Completer.complete("ssh spark && git s", listOf("git status"), null)
        assertTrue(out.any { it.display == "git status" })
    }

    @Test
    fun envAssignmentAndDoasAreTransparent() {
        assertTrue(
            Completer.complete("FOO=1 git ch", emptyList(), null)
                .any { it.display == "checkout" },
        )
        assertTrue(
            Completer.complete("doas git st", emptyList(), null)
                .any { it.display == "status" },
        )
    }

    @Test
    fun sudoStillBeingTypedIsNotStripped() {
        // A wrapper word with nothing after it is the token being typed, not a
        // prefix to strip: "sud" completes to "sudo" from your own history.
        val history = listOf("sudo apt update")
        assertTrue(Completer.complete("sud", history, null).any { it.display == "sudo" })
        assertTrue(Completer.complete("sudo", history, null).any { it.display == "sudo apt update" })
    }

    // ---- the live host: inventory + dynamic args -------------------------------------

    private fun fakeHost(command: String): String? = when {
        "compgen" in command -> "grep\nhtop\nls\nrsync\ntmux\ntop"
        "tmux list-sessions" in command -> "main\nwork"
        ".ssh/config" in command -> "spark\nspire\n*.internal\nblackpearl"
        command.startsWith("ls -1Ap -- '/var/'") -> "log/\nlib/\nlock\ntmp/"
        command.startsWith("ls -1Ap -- ~/''") -> "workspace/\nnotes.txt\n.bashrc"
        else -> ""
    }

    @Test
    fun inventoryGatesSpecsAndOffersInstalledCommands() = runBlocking {
        val rc = RemoteContext(this) { fakeHost(it) }
        rc.refreshCommands()
        rc.version.first { it >= 1 }

        // `to` prefix: sorted inventory offers top; git isn't suggested for `gi`
        // because this host doesn't have it.
        assertTrue(Completer.complete("to", emptyList(), rc).any { it.display == "top" })
        assertTrue(Completer.complete("gi", emptyList(), rc).isEmpty())
    }

    @Test
    fun flagValuePinsToLiveArguments() = runBlocking {
        val rc = RemoteContext(this) { fakeHost(it) }
        rc.refreshCommands()
        rc.version.first { it >= 1 }
        rc.args(ArgKind.TMUX_SESSION) // trigger the probe
        rc.version.first { it >= 2 }

        val out = Completer.complete("tmux attach -t ", emptyList(), rc)
        assertEquals(listOf("main", "work"), out.map { it.display })
    }

    @Test
    fun valuePositionBelongsToTheLiveHost() = runBlocking {
        // Once the host has answered, history must not resurrect dead session names.
        val rc = RemoteContext(this) { fakeHost(it) }
        rc.args(ArgKind.TMUX_SESSION)
        rc.version.first { it >= 1 }

        val history = listOf("tmux attach -t dead-session")
        val pinned = Completer.complete("tmux attach -t ", history, rc)
        assertEquals(listOf("main", "work"), pinned.map { it.display })
        val positional = Completer.complete("tmux attach ", history, rc)
        assertTrue(positional.any { it.display == "main" })
        assertTrue(positional.none { it.display == "tmux attach -t dead-session" })
    }

    @Test
    fun absolutePathsCompleteFromTheLiveDirectory() = runBlocking {
        val rc = RemoteContext(this) { fakeHost(it) }
        rc.pathEntries("/var/") // trigger the listing
        rc.version.first { it >= 1 }

        val out = Completer.complete("cat /var/l", emptyList(), rc)
        // Directories cascade with '/', files close the token with a space.
        assertTrue(out.any { it.display == "log/" && it.insert == "og/" })
        assertTrue(out.any { it.display == "lock" && it.insert == "ock " })
    }

    @Test
    fun homePathsCompleteToo() = runBlocking {
        val rc = RemoteContext(this) { fakeHost(it) }
        rc.pathEntries("~/")
        rc.version.first { it >= 1 }

        val out = Completer.complete("vim ~/w", emptyList(), rc)
        assertTrue(out.any { it.display == "workspace/" && it.insert == "orkspace/" })
    }

    @Test
    fun sshTargetsCompleteFromTheRemotesOwnBook() = runBlocking {
        val rc = RemoteContext(this) { fakeHost(it) }
        rc.args(ArgKind.SSH_HOST)
        rc.version.first { it >= 1 }

        val out = Completer.complete("ssh sp", emptyList(), rc)
        assertTrue(out.any { it.display == "spark" })
        assertTrue(out.any { it.display == "spire" })
        assertTrue(out.none { "*" in it.display }) // config patterns never offered

        // user@ keeps the traveller's own half and matches the host past the @.
        val at = Completer.complete("ssh jonny@bl", emptyList(), rc)
        assertTrue(at.any { it.display == "jonny@blackpearl" && it.insert == "ackpearl " })
    }

    @Test
    fun openWorldValuesKeepHistoryBeside() = runBlocking {
        // ssh targets are an open world: the config knows some, history knows others.
        val rc = RemoteContext(this) { fakeHost(it) }
        rc.args(ArgKind.SSH_HOST)
        rc.version.first { it >= 1 }

        val history = listOf("ssh cocakova@100.93.255.23 -p 22")
        val out = Completer.complete("ssh co", history, rc)
        assertTrue(out.any { it.display == "ssh cocakova@100.93.255.23 -p 22" })
    }

    @Test
    fun historyStillSpeaksUntilTheHostAnswers() = runBlocking {
        val history = listOf("tmux attach -t dead-session")
        // No remote at all…
        assertTrue(
            Completer.complete("tmux attach -t ", history, null)
                .any { it.display == "tmux attach -t dead-session" },
        )
        // …and a probe that FAILS must not count as an answer (nothing cached).
        val rc = RemoteContext(this) { cmd -> if ("tmux list-sessions" in cmd) null else "" }
        rc.args(ArgKind.TMUX_SESSION)
        delay(150) // let the failed probe finish; it must leave no cache
        assertTrue(!rc.landed(ArgKind.TMUX_SESSION))
        assertTrue(
            Completer.complete("tmux attach -t ", history, rc)
                .any { it.display == "tmux attach -t dead-session" },
        )
    }
}
