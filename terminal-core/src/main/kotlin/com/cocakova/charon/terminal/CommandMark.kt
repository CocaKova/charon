package com.cocakova.charon.terminal

import java.util.Locale

/**
 * One command's soundings, from a rigged shell's OSC 133 marks: the line its prompt
 * began on (A), where the typed command begins (B), the line its output began on
 * (C) and how it ended (D). The same object hangs on the prompt line and on the
 * output line, the way an image hangs on the line it starts on: it scrolls with the
 * text and dies with it. It never holds the command's text — only where and how.
 */
class CommandMark(val id: Long) {
    /** Column on the prompt line where the typed command begins (B), or -1. */
    var inputCol: Int = -1
    /** Output began (C): a command actually ran, not an empty Enter. */
    var ran: Boolean = false
    /** The shell said it is done (D). */
    var finished: Boolean = false
    var exitCode: Int? = null
    /** Output-start to done, measured by whoever holds a clock; -1 = unknown. */
    var durationMs: Long = -1L

    val failed: Boolean get() = finished && exitCode != null && exitCode != 0

    /**
     * The whisper beside a finished prompt: "✓ 2m14s", "✕ exit 2 · 8s"; null when
     * there's nothing worth saying (an empty Enter, a command still running).
     */
    fun whisper(): String? {
        if (!ran || !finished) return null
        val took = if (durationMs >= 1000) duration(durationMs) else null
        return if (failed) {
            "✕ exit $exitCode" + (took?.let { " · $it" } ?: "")
        } else {
            "✓" + (took?.let { " $it" } ?: "")
        }
    }

    companion object {
        /** 0.4s · 8s · 2m14s · 1h05m — the voyage's length, short. */
        fun duration(ms: Long): String {
            val s = ms / 1000
            return when {
                ms < 1000 -> String.format(Locale.ROOT, "%.1fs", ms / 1000.0)
                s < 60 -> "${s}s"
                s < 3600 -> "${s / 60}m${"%02d".format(s % 60)}s"
                else -> "${s / 3600}h${"%02d".format((s % 3600) / 60)}m"
            }
        }
    }
}
