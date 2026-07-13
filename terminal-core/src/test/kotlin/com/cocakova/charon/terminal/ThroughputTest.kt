package com.cocakova.charon.terminal

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Parse-throughput floor: mixed SGR-heavy text must clear 50 MB/s on the JVM, which
 * guarantees `yes`/`cat bigfile` floods can't outrun the emulator on a phone (the
 * renderer conflates frames; the emulator just has to keep up with the socket).
 */
class ThroughputTest {

    @Test
    fun mixedSgrTextClears50MBps() {
        val rng = Random(1)
        val sb = StringBuilder()
        while (sb.length < 4 * 1024 * 1024) {
            when (rng.nextInt(10)) {
                0 -> sb.append("\u001B[").append(rng.nextInt(48)).append('m')
                1 -> sb.append("\u001B[38;5;").append(rng.nextInt(256)).append('m')
                2 -> sb.append("\u001B[").append(rng.nextInt(24) + 1).append(';')
                    .append(rng.nextInt(80) + 1).append('H')
                3 -> sb.append("\r\n")
                else -> repeat(40) { sb.append(('a' + rng.nextInt(26))) }
            }
        }
        val bytes = sb.toString().toByteArray(Charsets.UTF_8)
        val term = TerminalEmulator(80, 24)

        term.write(bytes) // warm-up: JIT + fill scrollback

        val start = System.nanoTime()
        term.write(bytes)
        val seconds = (System.nanoTime() - start) / 1e9
        val mbps = bytes.size / 1e6 / seconds
        println("throughput: %.1f MB/s (%.0f ms for %d MB)".format(mbps, seconds * 1000, bytes.size / 1_000_000))
        assertTrue(mbps > 50, "parse throughput %.1f MB/s below the 50 MB/s floor".format(mbps))
    }
}
