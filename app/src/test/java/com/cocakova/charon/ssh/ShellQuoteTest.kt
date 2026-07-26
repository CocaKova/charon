package com.cocakova.charon.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [shellQuote] guards every probe the autocomplete engine sends over the exec
 * channel — path completion quotes whatever directory the user has typed, so a
 * hostile-looking token must come out of the remote shell byte-for-byte intact,
 * never executed. Round-trips each string through a real `sh` to prove it.
 */
class ShellQuoteTest {

    private fun roundTrips(value: String) {
        val process = ProcessBuilder("sh", "-c", "printf '%s' " + shellQuote(value))
            .redirectErrorStream(true)
            .start()
        val out = process.inputStream.readBytes().decodeToString()
        process.waitFor()
        assertEquals(value, out)
    }

    @Test
    fun hostileStringsSurviveTheShellUnharmed() {
        listOf(
            "plain",
            "with space",
            "single'quote",
            "double\"quote",
            "\$(rm -rf /tmp/x)",
            "`whoami`",
            "semi;colon && chain || pipe | done",
            "star*glob?[set]",
            "back\\slash",
            "~tilde/!bang",
            "redirect > /dev/null < here",
            "hash#comment",
            "unicode — señor 日本語",
            "'; echo pwned; '",
        ).forEach { roundTrips(it) }
    }

    @Test
    fun newlinesStayData() {
        roundTrips("line one\nline two")
    }
}
