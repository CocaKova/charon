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

    /**
     * The 07-22 hole, found live: typing "ple" autofilled a whole prompt. Prose
     * addressed to an assistant is full of paths, parens and dollar signs, and the
     * old rule let any one of them settle the line as a command — while "please"
     * itself passed the unknown-token shape check. Shell characters are evidence,
     * not proof: the function words still speak.
     */
    @Test
    fun proseCarryingPathsAndParensIsRejected() {
        prose(
            "please fix the light mode in ~/workspace/charon and then harden " +
                "the smart complete feature (see docs/PLAN.md)",
        )
        prose("make the dock look better and update docs/DESIGN.md while you're at it")
        prose("look at src/main/java and tell me what you think of the layout")
        prose("test it with the \$PATH inventory loaded and see if the gate holds")
        prose("install it under /opt when you get the chance")
        prose("find the bug in TerminalScreen.kt (line 214) and fix it")
    }

    /** A function word can never open a command — there is no program called
     *  "please" on this host, however command-shaped the rest of the line looks. */
    @Test
    fun functionWordOpenersAreRejectedOutright() {
        prose("please review ~/workspace/charon")
        prose("that one in src/app is the bug")
        prose("when you push use --force-with-lease")
        prose("it's rendering the dock twice")
    }

    /** Prose belongs in quotes inside real commands: masked, it never condemns
     *  them. This is what keeps commit messages autofilling. */
    @Test
    fun quotedProseInsideCommandsDoesNotCondemnThem() {
        command("git commit -m \"fix the gate for the light mode\"")
        command("git commit -am 'make it read the whole line'")
        command("grep -rn \"the water\" src/")
        command("tmux rename-session -t main 'the dock'")
        command("echo 'this is a test of the horn'")
    }

    /** Unquoted function words in value position still give a sentence away even
     *  when it carries shell characters. */
    @Test
    fun unquotedProseWithShellCharactersIsStillProse() {
        prose("make it more responsive and check ~/notes.md for the details")
        prose("who was in /var/log when the crossing failed and why")
    }
}
