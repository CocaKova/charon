package com.cocakova.charon.terminal

/**
 * One screen's stack of Kitty keyboard-protocol enhancement flags (progressive
 * enhancement: `CSI > f u` push, `CSI < n u` pop, `CSI = f ; m u` set, `CSI ? u`
 * query). The flags in force are the top entry; an empty stack means legacy
 * encoding (0). The emulator keeps one of these per screen, as the spec requires,
 * so a TUI that pushes on the alternate screen can't leak its flags into the shell
 * underneath.
 *
 * Semantics follow kitty's own implementation where the spec is silent: a set on
 * an empty stack creates the entry it modifies, a push onto a full stack evicts
 * the oldest entry, and a pop past the bottom simply empties it.
 */
class KittyKeyboardStack {

    private val entries = IntArray(CAPACITY)
    private var size = 0

    /** The enhancement flags currently in force (0 = legacy). */
    val flags: Int get() = if (size == 0) 0 else entries[size - 1]

    val depth: Int get() = size

    fun push(flags: Int) {
        if (size == CAPACITY) {
            System.arraycopy(entries, 1, entries, 0, CAPACITY - 1)
            size--
        }
        entries[size++] = flags and MASK
    }

    fun pop(count: Int) {
        size = (size - count.coerceAtLeast(1)).coerceAtLeast(0)
    }

    /** `CSI = flags ; mode u`: 1 replaces, 2 ORs in, 3 clears the given bits. */
    fun set(flags: Int, mode: Int) {
        val f = flags and MASK
        val next = when (mode) {
            1 -> f
            2 -> this.flags or f
            3 -> this.flags and f.inv()
            else -> return
        }
        if (size == 0) size = 1
        entries[size - 1] = next
    }

    fun reset() {
        size = 0
    }

    companion object {
        /** Deep enough for nested TUIs (shell → tmux → nvim → a picker); kitty uses 8. */
        const val CAPACITY = 16

        /** The five defined bits (disambiguate … report associated text). */
        const val MASK = 0b11111
    }
}
