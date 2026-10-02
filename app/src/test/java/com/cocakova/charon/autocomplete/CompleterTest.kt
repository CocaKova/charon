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
        val at = Completer.complete("ssh user@bl", emptyList(), rc)
        assertTrue(at.any { it.display == "user@blackpearl" && it.insert == "ackpearl " })
    }

    @Test
    fun openWorldValuesKeepHistoryBeside() = runBlocking {
        // ssh targets are an open world: the config knows some, history knows others.
        val rc = RemoteContext(this) { fakeHost(it) }
        rc.args(ArgKind.SSH_HOST)
        rc.version.first { it >= 1 }

        val history = listOf("ssh user@100.101.102.103 -p 22")
        val out = Completer.complete("ssh us", history, rc)
        assertTrue(out.any { it.display == "ssh user@100.101.102.103 -p 22" })
    }

    // ---- soundings: the shell's cwd (OSC 7) ------------------------------------------

    /** A host whose shell stands in /home/j, a repo with a remote. Records every
     *  probe so a test can prove what was — and wasn't — asked. */
    private class Harbor {
        val asked = java.util.Collections.synchronizedList(mutableListOf<String>())
        fun exec(command: String): String? {
            asked += command
            return when {
                command.startsWith("ls -1Ap -- '/home/j/'") ->
                    "notes.txt\nnode_modules/\nsrc/\nMy Docs/\n.bashrc\n.config/"
                command.startsWith("ls -1Ap -- '/home/j/src/'") -> "main.c\nmakefile\nlib/"
                command.startsWith("ls -1Ap -- '/'") -> "etc/\nhome/"
                "for-each-ref" in command && "'/home/j'" in command ->
                    "refs/heads/main\nrefs/heads/feat-x\nrefs/remotes/origin/HEAD\n" +
                        "refs/remotes/origin/main\nrefs/remotes/origin/fix-y"
                "for-each-ref" in command -> "" // not a repo: git fails quietly
                command.startsWith("hostname") -> "spark\n"
                else -> ""
            }
        }
    }

    /** Ask twice: the first call triggers the probes (a position can take branches
     *  *and* files), the second reads their answers once every one has landed. */
    private suspend fun RemoteContext.settle(draft: String, cwd: String?): List<Suggestion> {
        val v = version.value
        Completer.complete(draft, emptyList(), this, cwd = cwd)
        version.first { it > v }
        var seen = -1
        while (seen != version.value) {
            seen = version.value
            delay(100)
        }
        return Completer.complete(draft, emptyList(), this, cwd = cwd)
    }

    @Test
    fun bareNamesCompleteFromTheShellsCwd() = runBlocking {
        val h = Harbor()
        val rc = RemoteContext(this) { h.exec(it) }
        val out = rc.settle("cat no", cwd = "/home/j")
        assertTrue(out.any { it.display == "notes.txt" && it.insert == "tes.txt " })
        assertTrue(out.any { it.display == "node_modules/" && it.insert == "de_modules/" })
    }

    @Test
    fun relativeDirectoriesCascade() = runBlocking {
        val h = Harbor()
        val rc = RemoteContext(this) { h.exec(it) }
        val out = rc.settle("vim src/ma", cwd = "/home/j")
        assertEquals(listOf("main.c", "makefile"), out.map { it.display })
        assertTrue(h.asked.any { it.startsWith("ls -1Ap -- '/home/j/src/'") })
    }

    @Test
    fun theRootCwdJoinsWithoutADoubleSlash() = runBlocking {
        val h = Harbor()
        val rc = RemoteContext(this) { h.exec(it) }
        val out = rc.settle("ls e", cwd = "/")
        assertTrue(out.any { it.display == "etc/" })
    }

    @Test
    fun withoutACwdNothingRelativeIsProbed() = runBlocking {
        val h = Harbor()
        val rc = RemoteContext(this) { h.exec(it) }
        val out = Completer.complete("cat no", emptyList(), rc, cwd = null)
        delay(100)
        assertTrue(out.isEmpty())
        assertTrue(h.asked.none { it.startsWith("ls ") || "for-each-ref" in it })
    }

    @Test
    fun onlyFilePositionsProbeTheCwd() = runBlocking {
        val h = Harbor()
        val rc = RemoteContext(this) { h.exec(it) }
        listOf(
            "no",                    // the command itself: a name, not a file
            "man no",                // an everyday command that takes no files
            "systemctl status no",   // spec'd, and its positional is a unit
            "tmux attach -t no",     // a flag's value that is a session
            "git commit -m no",      // a message
            "ls -no",                // a flag
            "cat \"no",              // quoted: the shell unquotes first
            "cat \$HOME/no",         // expanded first
            "scp spark:no",          // another host's file
        ).forEach { Completer.complete(it, emptyList(), rc, cwd = "/home/j") }
        delay(100)
        assertTrue(h.asked.toString(), h.asked.none { it.startsWith("ls ") })
    }

    @Test
    fun aPathInvokedCommandCompletesAsAFile() = runBlocking {
        val h = Harbor()
        val rc = RemoteContext(this) { h.exec(it) }
        val out = rc.settle("./src/ma", cwd = "/home/j")
        assertTrue(out.any { it.display == "main.c" })
    }

    @Test
    fun aFlagThatTakesAFileCompletesFromTheCwd() = runBlocking {
        val h = Harbor()
        val rc = RemoteContext(this) { h.exec(it) }
        val out = rc.settle("ssh -i no", cwd = "/home/j")
        assertTrue(out.any { it.display == "notes.txt" })
    }

    @Test
    fun dotfilesWaitForTheDot() = runBlocking {
        val h = Harbor()
        val rc = RemoteContext(this) { h.exec(it) }
        val bare = rc.settle("vim ", cwd = "/home/j")
        assertTrue(bare.none { it.display.startsWith(".") })
        assertTrue(bare.any { it.display == "notes.txt" })
        val dotted = Completer.complete("vim .b", emptyList(), rc, cwd = "/home/j")
        assertTrue(dotted.any { it.display == ".bashrc" })
    }

    @Test
    fun cdOffersOnlyDirectories() = runBlocking {
        val h = Harbor()
        val rc = RemoteContext(this) { h.exec(it) }
        val out = rc.settle("cd n", cwd = "/home/j")
        assertEquals(listOf("node_modules/"), out.map { it.display })
    }

    @Test
    fun aNameWithASpaceStaysOneWord() = runBlocking {
        val h = Harbor()
        val rc = RemoteContext(this) { h.exec(it) }
        val out = rc.settle("cd M", cwd = "/home/j")
        assertTrue(out.any { it.display == "My Docs/" && it.insert == "y\\ Docs/" })
    }

    @Test
    fun cwdWithAQuoteIsQuotedIntoTheProbe() = runBlocking {
        val h = Harbor()
        val rc = RemoteContext(this) { h.exec(it) }
        Completer.complete("cat x", emptyList(), rc, cwd = "/srv/it's")
        delay(100)
        assertTrue(h.asked.toString(), h.asked.any { it.startsWith("ls -1Ap -- '/srv/it'\"'\"'s/'") })
    }

    // ---- branches of the repo the shell stands in -----------------------------------

    @Test
    fun checkoutOffersLocalBranchesAndTheRemotesDwimNames() = runBlocking {
        val h = Harbor()
        val rc = RemoteContext(this) { h.exec(it) }
        val out = rc.settle("git checkout ", cwd = "/home/j")
        val names = out.map { it.display }
        assertTrue(names.toString(), names.containsAll(listOf("main", "feat-x", "fix-y")))
        assertTrue(names.none { it.startsWith("origin/") || it == "HEAD" })
        assertTrue(Completer.complete("git switch fi", emptyList(), rc, cwd = "/home/j")
            .any { it.display == "fix-y" && it.insert == "x-y " })
    }

    @Test
    fun mergeAndRebaseOfferRemoteTrackingRefs() = runBlocking {
        val h = Harbor()
        val rc = RemoteContext(this) { h.exec(it) }
        val out = rc.settle("git merge or", cwd = "/home/j")
        assertEquals(listOf("origin/main", "origin/fix-y"), out.map { it.display })
        assertTrue(out.none { it.display == "origin/HEAD" })
        assertTrue(Completer.complete("git rebase origin/m", emptyList(), rc, cwd = "/home/j")
            .any { it.display == "origin/main" })
    }

    @Test
    fun deletingABranchIsClosedWorld() = runBlocking {
        // The heads ARE the universe: a deleted branch in history must not resurface.
        val h = Harbor()
        val rc = RemoteContext(this) { h.exec(it) }
        val history = listOf("git branch -d long-gone")
        rc.settle("git branch -d ", cwd = "/home/j")
        val out = Completer.complete("git branch -d ", history, rc, cwd = "/home/j")
        assertEquals(listOf("main", "feat-x"), out.map { it.display })
    }

    @Test
    fun checkoutKeepsHistoryBesideTheBranches() = runBlocking {
        // Open world: tags, SHAs and files are valid checkouts the probe never lists.
        val h = Harbor()
        val rc = RemoteContext(this) { h.exec(it) }
        rc.settle("git checkout ", cwd = "/home/j")
        val out = Completer.complete("git checkout v", listOf("git checkout v1.2.0"), rc, cwd = "/home/j")
        assertTrue(out.any { it.display == "git checkout v1.2.0" })
    }

    @Test
    fun aNewBranchNameIsYoursToInvent() = runBlocking {
        val h = Harbor()
        val rc = RemoteContext(this) { h.exec(it) }
        rc.settle("git checkout ", cwd = "/home/j")
        assertTrue(Completer.complete("git checkout -b ", emptyList(), rc, cwd = "/home/j").isEmpty())
    }

    @Test
    fun branchesNeedACwd() = runBlocking {
        val h = Harbor()
        val rc = RemoteContext(this) { h.exec(it) }
        val out = Completer.complete("git checkout ", emptyList(), rc, cwd = null)
        delay(100)
        assertEquals(listOf("-b"), out.map { it.display }) // exactly as before soundings
        assertTrue(h.asked.none { "for-each-ref" in it })
    }

    // ---- is the report about this host? ---------------------------------------------

    @Test
    fun aReportedCwdCountsOnlyOnThisHost() = runBlocking {
        val h = Harbor()
        val rc = RemoteContext(this) { h.exec(it) }
        val here = com.cocakova.charon.terminal.ShellCwd("spark", "/home/j")
        assertEquals(null, rc.resolveCwd(here)) // the host's name hasn't landed yet
        rc.refreshHost()
        rc.version.first { it >= 1 }
        assertEquals("/home/j", rc.resolveCwd(here))
        assertEquals("/home/j", rc.resolveCwd(here.copy(host = "SPARK.lan")))
        assertEquals("/home/j", rc.resolveCwd(here.copy(host = "")))
        assertEquals("/home/j", rc.resolveCwd(here.copy(host = "localhost")))
        assertEquals(null, rc.resolveCwd(here.copy(host = "blackpearl"))) // onward ssh
        assertEquals(null, rc.resolveCwd(null))
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
