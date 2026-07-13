package com.cocakova.charon.terminal

import java.io.File
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Golden-corpus conformance: real byte streams recorded from vim/htop/tmux/ls on a
 * Linux pty (tools/record_corpus.py, 80x24, TERM=xterm-256color) are replayed into the
 * emulator and the resulting grid — text AND per-row attribute checksums — must match
 * the checked-in goldens exactly.
 *
 * Regenerate after intentional behavior changes:
 *   ./gradlew :terminal-core:test --tests '*CorpusTest*' \
 *     -Dcharon.regenGoldens=terminal-core/src/test/resources/corpus
 * then eyeball the golden diffs before committing — they ARE the spec.
 */
class CorpusTest {

    private val names = listOf("ls-color", "truecolor", "vim-kotlin", "htop", "tmux-split")

    @Test
    fun corpusGoldens() {
        val regenDir = System.getProperty("charon.regenGoldens")?.takeIf { it.isNotBlank() }
        val failures = mutableListOf<String>()
        for (name in names) {
            val bytes = javaClass.getResourceAsStream("/corpus/$name.bin")?.readBytes()
                ?: fail("missing corpus recording $name.bin")
            val term = TerminalEmulator(80, 24)
            term.write(bytes)
            val rendered = render(term)
            if (regenDir != null) {
                File(regenDir, "$name.golden").writeText(rendered)
                println("regenerated $name.golden")
            } else {
                val expected = javaClass.getResourceAsStream("/corpus/$name.golden")
                    ?.bufferedReader()?.readText()
                    ?: fail("missing golden $name.golden — regenerate (see class kdoc)")
                if (expected != rendered) failures.add(name)
            }
        }
        if (failures.isNotEmpty()) {
            // Re-render one failing case inline for a readable diff.
            val name = failures.first()
            val bytes = javaClass.getResourceAsStream("/corpus/$name.bin")!!.readBytes()
            val term = TerminalEmulator(80, 24)
            term.write(bytes)
            assertEquals(
                javaClass.getResourceAsStream("/corpus/$name.golden")!!.bufferedReader().readText(),
                render(term),
                "grid mismatch for $name (failing: $failures)",
            )
        }
    }

    private fun render(term: TerminalEmulator): String = buildString {
        appendLine("# cursor=${term.cursorX},${term.cursorY} alt=${term.usingAlt} title=${term.title}")
        for (r in 0 until term.rows) {
            append('|')
            appendLine(term.screen.line(r).toText())
        }
        appendLine("# row attr+text checksums")
        val crc = CRC32()
        for (r in 0 until term.rows) {
            val line = term.screen.line(r)
            crc.reset()
            for (c in 0 until term.cols) {
                crcInt(crc, line.codePoints[c])
                crcInt(crc, (line.attrs[c] ushr 32).toInt())
                crcInt(crc, line.attrs[c].toInt())
            }
            appendLine("%02d %08x".format(r, crc.value))
        }
    }

    private fun crcInt(crc: CRC32, v: Int) {
        crc.update(v ushr 24)
        crc.update((v ushr 16) and 0xFF)
        crc.update((v ushr 8) and 0xFF)
        crc.update(v and 0xFF)
    }
}
