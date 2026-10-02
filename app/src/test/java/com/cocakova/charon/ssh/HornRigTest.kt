package com.cocakova.charon.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The rig: the right file, the exact block, appended once, and every block parses. */
class HornRigTest {

    @Test
    fun theLoginShellNamesTheFile() {
        assertEquals(HornRig.Shell.BASH, HornRig.shellOf("/bin/bash\n"))
        assertEquals(HornRig.Shell.ZSH, HornRig.shellOf("/usr/bin/zsh"))
        assertEquals(HornRig.Shell.FISH, HornRig.shellOf("/opt/homebrew/bin/fish"))
        assertNull(HornRig.shellOf("/bin/sh"))
        assertNull(HornRig.shellOf(""))
    }

    @Test
    fun theErrandIsBase64IntoShSoNoLoginShellReadsItsQuotes() {
        val cmd = HornRig.installCommand(HornRig.Shell.FISH)
        assertTrue(cmd.matches(Regex("echo [A-Za-z0-9+/=]+ \\| base64 -d \\| sh")))
    }

    private fun sh(home: File, command: String): String {
        val p = ProcessBuilder("sh", "-c", command).apply {
            environment()["HOME"] = home.path
            redirectErrorStream(true)
        }.start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out
    }

    private fun has(tool: String) = File("/bin/$tool").exists() || File("/usr/bin/$tool").exists()

    @Test
    fun riggingAppendsTheShownTextOnceAndOnlyOnce() {
        assumeTrue(has("sh") && has("base64"))
        for (shell in HornRig.Shell.values()) {
            val home = Files.createTempDirectory("rig").toFile()
            val rc = File(home, shell.rcFile.removePrefix("~/"))
            if (shell == HornRig.Shell.BASH) rc.writeText("alias ll='ls -l'\n")
            assertEquals(HornRig.Outcome.RIGGED, HornRig.outcomeOf(sh(home, HornRig.installCommand(shell))))
            assertEquals(HornRig.Outcome.ALREADY, HornRig.outcomeOf(sh(home, HornRig.installCommand(shell))))
            val text = rc.readText()
            val before = if (shell == HornRig.Shell.BASH) "alias ll='ls -l'\n" else ""
            assertEquals(before + HornRig.appended(shell), text)
            home.deleteRecursively()
        }
    }

    @Test
    fun theBashBlockParsesAndEmitsTheMarks() {
        assumeTrue(has("bash"))
        val home = Files.createTempDirectory("rig").toFile()
        val rc = File(home, "rc.bash").apply { writeText(HornRig.block(HornRig.Shell.BASH)) }
        val p = ProcessBuilder(
            "bash", "-c", "source '${rc.path}'; cd /tmp; false; __charon_prompt; echo; echo \"\$PS0\"",
        ).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(0, p.waitFor())
        assertTrue(out, "\u001b]133;D;1\u0007" in out)
        assertTrue(out, "\u001b]7;file://" in out && "/tmp\u0007" in out)
        assertTrue(out, "\u001b]133;A\u0007" in out)
        assertTrue(out, "__charon_osc \"133;C\"" in out)
        home.deleteRecursively()
    }

    @Test
    fun theZshAndFishBlocksParseWhereThoseShellsAreAboard() {
        if (has("zsh")) {
            val f = Files.createTempFile("rig", ".zsh").toFile().apply { writeText(HornRig.block(HornRig.Shell.ZSH)) }
            val p = ProcessBuilder("zsh", "-n", f.path).redirectErrorStream(true).start()
            assertEquals(p.inputStream.bufferedReader().readText(), 0, p.waitFor())
        }
        if (has("fish")) {
            val f = Files.createTempFile("rig", ".fish").toFile().apply { writeText(HornRig.block(HornRig.Shell.FISH)) }
            val p = ProcessBuilder("fish", "--no-execute", f.path).redirectErrorStream(true).start()
            assertEquals(p.inputStream.bufferedReader().readText(), 0, p.waitFor())
        }
    }
}
