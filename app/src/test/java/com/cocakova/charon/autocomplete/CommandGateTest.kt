package com.cocakova.charon.autocomplete

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate between shell commands and prose: sentences typed into a chat or REPL
 * running on the host must never enter (or leave) the autofill history.
 */
class CommandGateTest {

    /** A real host's inventory is full of English: every one of these is a program
     *  or builtin on the Spark, and every one of them also opens sentences. */
    private val installed = setOf(
        "ls", "git", "tmux", "htop", "rsync", "sudo", "apt", "vim", "echo", "grep",
        "make", "test", "find", "time", "who", "which", "install", "look", "watch",
        "sort", "head", "tail", "yes", "last", "free", "more", "less", "kill", "clear",
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

    /**
     * The hole that let sentences keep reaching the history: the gate read only the
     * first token, and English is full of words that are also programs. Every line
     * here opens with something genuinely installed.
     */
    @Test
    fun sentencesOpeningWithRealProgramsAreRejected() {
        prose("make it more responsive on the smaller phones")
        prose("test that again when you have the terminal open")
        prose("find the bug that keeps eating my session")
        prose("time to move on to the next thing")
        prose("look at the way it renders on my phone")
        prose("install it on the phone when you get a chance")
        prose("watch out for the race there")
        prose("who else has been touching this file")
        prose("last thing before you go")
        prose("clear it out and start over")
    }

    /** Shell keywords open sentences too — `do`, `let`, `read`, `set`, `if`, `for`. */
    @Test
    fun sentencesOpeningWithShellKeywordsAreRejected() {
        prose("do that for me when you get the chance")
        prose("let me know how it goes")
        prose("read the whole thing before you change it")
        prose("set it up the way we talked about")
        prose("if you can get to it tonight that would be great")
        prose("for now just leave the old one alone")
        prose("type it out and see what happens")
        prose("wait for the build to finish first")
    }

    /** A sentence that happens to be short still gives itself away. */
    @Test
    fun shortSentencesAreRejectedOnMarkers() {
        prose("let me know")
        prose("do that now")
        prose("make it better")
        prose("test the thing")
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

    /** Commands whose arguments are all bare names carry no shell punctuation at all —
     *  the prose read must not mistake them for sentences. */
    @Test
    fun bareWordCommandsAreStillAccepted() {
        command("sudo apt install ripgrep")
        command("sudo systemctl restart palworld")
        command("git checkout main")
        command("tmux attach main")
        command("docker compose up")
        command("cp a b")
        command("sudo apt install ripgrep fd bat jq")
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
