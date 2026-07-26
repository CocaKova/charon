package com.cocakova.charon.terminal.conformance

import com.cocakova.charon.terminal.TerminalEmulator
import java.io.File
import java.util.Base64

/**
 * Stdin/stdout bridge that puts the real emulator in the loop with a live conformance
 * suite (vttest/esctest) running under a pty driven by tools/conformance_vttest.py.
 *
 * Protocol (line-framed, one command per line):
 *   in:  "B <base64>"    feed raw bytes to the emulator
 *   in:  "DUMP <path>"   write a grid snapshot to <path>, reply "OK <path>"
 *   in:  "EXIT"          quit
 *   out: "R <base64>"    bytes the emulator answered (DA/DSR/CPR/…) — write them to
 *                        the pty so the suite under test receives them
 *
 * Responses are emitted synchronously from write(), so they are always on the wire
 * before the OK of any later DUMP — the driver never snapshots a half-answered query.
 */
object ConformanceBridge {
    @JvmStatic
    fun main(args: Array<String>) {
        val cols = args.getOrNull(0)?.toIntOrNull() ?: 80
        val rows = args.getOrNull(1)?.toIntOrNull() ?: 24
        val out = System.out
        val enc = Base64.getEncoder()
        val dec = Base64.getDecoder()
        val term = TerminalEmulator(cols, rows, onResponse = { resp ->
            out.println("R " + enc.encodeToString(resp.toByteArray(Charsets.ISO_8859_1)))
            out.flush()
        })
        System.`in`.bufferedReader().forEachLine { line ->
            when {
                line.startsWith("B ") -> term.write(dec.decode(line.substring(2)))
                line.startsWith("DUMP ") -> {
                    val path = line.substring(5)
                    File(path).writeText(snapshot(term))
                    out.println("OK $path")
                    out.flush()
                }
                line == "EXIT" -> return@forEachLine
            }
        }
    }

    private fun snapshot(term: TerminalEmulator): String = buildString {
        appendLine(
            "# cursor=${term.cursorX},${term.cursorY} visible=${term.cursorVisible} " +
                "alt=${term.usingAlt} autowrap=${term.autowrap} origin=${term.originMode}"
        )
        for (r in 0 until term.rows) {
            append('|')
            appendLine(term.screen.line(r).toText())
        }
    }
}
