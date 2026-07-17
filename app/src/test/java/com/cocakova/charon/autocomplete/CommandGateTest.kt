package com.cocakova.charon.autocomplete

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate between shell commands and prose: sentences typed into a chat or REPL
 * running on the host must never enter (or leave) the autofill history.
 */
class CommandGateTest {

    private val installed = setOf(
        "ls", "git", "tmux", "htop", "rsync", "sudo", "apt", "make", "vim", "echo", "grep",
    )

    private fun command(line: String) = assertTrue(line, CommandGate.isCommandLine(line, installed))
    private fun prose(line: String) = assertFalse(line, CommandGate.isCommandLine(line, installed))

    @Test
    fun sentencesAreRejected() {
        prose("I noticed that while typing a response it remembered my message from before")
        prose("please fix the login bug on the dashboard when you get a chance")
        prose("what do you think about the new design for the fleet screen")
        prose("that looks great but the colors feel a bit washed out to me")
        prose("don't hold back on the refactor just keep the tests green")
    }

    @Test
    fun realCommandsAreAccepted() {
        command("ls -la")
        command("git status")
        command("sudo apt install moonlight-qt")
        command("tmux attach -t main")
        command("grep -rn pattern src")
    }

    @Test
    fun shellGrammarFormsAreAccepted() {
        command("FOO=1 make test")
        command("PATH=/opt/bin:\$PATH ./run.sh")
        command("./gradlew :app:assembleDebug")
        command("~/bin/deploy prod")
        command("/usr/local/bin/backup --now")
        command("(cd /tmp && ls)")
        command("\$EDITOR notes.txt")
        command("cd /var/log")
        command("for f in a b c") // keyword start
    }

    @Test
    fun unknownShortLinesPassAsAliases() {
        // Aliases and functions aren't in the PATH inventory; short invocations pass.
        command("ll")
        command("gs")
        command("k get pods")
    }

    @Test
    fun unknownLongBareLinesFailEvenWithoutInventory() {
        assertFalse(
            CommandGate.isCommandLine(
                "I noticed that while typing a response it remembered my message",
                emptySet(),
            ),
        )
    }

    @Test
    fun flaggyLinesPassWithoutInventory() {
        // Before the inventory lands, shape carries: options/paths/operators.
        assertTrue(CommandGate.isCommandLine("rsync -avz src/ host:/dst", emptySet()))
        assertTrue(CommandGate.isCommandLine("ffmpeg -i in.mp4 -c copy out.mkv", emptySet()))
        assertTrue(CommandGate.isCommandLine("journalctl -u nginx --since today", emptySet()))
    }

    @Test
    fun blankAndEmptyAreRejected() {
        prose("")
        prose("   ")
    }
}
