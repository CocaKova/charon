package com.cocakova.charon.data.repository

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A lightweight recent-commands memory — the engine behind the smart-autofill strip.
 * Commands are the lines the user submits (reconstructed from keystrokes by
 * [com.cocakova.charon.ssh.TerminalSession]); this dedupes them most-recent-first and
 * persists a capped list across launches. Global on purpose: homelab incantations
 * repeat across hosts, so a command learned on one is offered on the next.
 *
 * Passwords never reach here — the tracker only records a line once Enter is pressed
 * at a shell prompt, and password prompts don't echo the line back into our tracker.
 */
class CommandHistory(context: Context) {
    private val prefs = context.getSharedPreferences("charon_history", Context.MODE_PRIVATE)

    private val _entries = MutableStateFlow(load())
    /** All remembered commands, most-recent first. */
    val entries: StateFlow<List<String>> = _entries

    private fun load(): List<String> =
        prefs.getString(KEY, "").orEmpty().split("\n").filter { it.isNotBlank() }

    /** Remember a submitted command, floating it to the front. */
    fun record(command: String) {
        val cmd = command.trim()
        if (cmd.length < MIN_LEN) return
        val next = (listOf(cmd) + _entries.value.filter { it != cmd }).take(CAP)
        _entries.value = next
        prefs.edit().putString(KEY, next.joinToString("\n")).apply()
    }

    companion object {
        private const val KEY = "commands"
        private const val CAP = 300
        private const val MIN_LEN = 2
    }
}
