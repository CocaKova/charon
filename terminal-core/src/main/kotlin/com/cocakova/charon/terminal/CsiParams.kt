package com.cocakova.charon.terminal

/**
 * Parameter list for CSI/DCS sequences, with colon-separated subparameters
 * (SGR `38:2:r:g:b` style). Preallocated and reused by the parser — the emulator
 * must consume it during dispatch, not retain it.
 *
 * A parameter that was never given digits reads as [UNSET]; callers use [get] with a
 * default to apply per-sequence semantics ("missing or zero means 1", etc.).
 */
class CsiParams {
    companion object {
        const val MAX_PARAMS = 32
        const val MAX_SUBPARAMS = 8
        const val MAX_VALUE = 65535
        const val UNSET = -1
    }

    private val values = Array(MAX_PARAMS) { IntArray(MAX_SUBPARAMS) { UNSET } }
    private val subCounts = IntArray(MAX_PARAMS)

    /** Number of parameters received (0 for a bare `CSI m`). */
    var count = 0
        private set

    private var curSub = 0
    private var overflowed = false

    internal fun clear() {
        for (i in 0 until count.coerceAtMost(MAX_PARAMS)) {
            val sc = subCounts[i].coerceAtMost(MAX_SUBPARAMS - 1)
            for (j in 0..sc) values[i][j] = UNSET
            subCounts[i] = 0
        }
        count = 0
        curSub = 0
        overflowed = false
    }

    internal fun digit(d: Int) {
        if (overflowed || curSub >= MAX_SUBPARAMS) return // overflow: swallow safely
        if (count == 0) count = 1
        val p = count - 1
        val old = values[p][curSub]
        val v = if (old == UNSET) d else old * 10 + d
        values[p][curSub] = v.coerceAtMost(MAX_VALUE)
    }

    internal fun nextParam() {
        if (overflowed) return
        if (count == 0) count = 1 // an initial ';' implies an empty first param
        if (count >= MAX_PARAMS) {
            overflowed = true // params beyond capacity are swallowed, not merged
            return
        }
        curSub = 0
        count++
    }

    internal fun nextSubParam() {
        if (overflowed) return
        if (count == 0) count = 1
        if (curSub < MAX_SUBPARAMS - 1) {
            curSub++
            subCounts[count - 1] = curSub
        }
    }

    /** Parameter i (primary value), or [default] when missing/unset. */
    fun get(i: Int, default: Int): Int {
        if (i >= count || i >= MAX_PARAMS) return default
        val v = values[i][0]
        return if (v == UNSET) default else v
    }

    /** Like [get] but also maps an explicit 0 to [default] (the common DEC "0 means 1"). */
    fun getOr1(i: Int): Int {
        val v = get(i, 1)
        return if (v == 0) 1 else v
    }

    /** Number of subparameters after the primary value of parameter i. */
    fun subCount(i: Int): Int =
        if (i >= count || i >= MAX_PARAMS) 0 else subCounts[i]

    /** Subparameter j (1-based after the primary value) of parameter i. */
    fun sub(i: Int, j: Int, default: Int): Int {
        if (i >= count || i >= MAX_PARAMS || j <= 0 || j > subCounts[i]) return default
        val v = values[i][j]
        return if (v == UNSET) default else v
    }

    /** True if parameter i has an explicit value (digits were seen). */
    fun isSet(i: Int): Boolean = i < count && i < MAX_PARAMS && values[i][0] != UNSET

    override fun toString(): String = buildString {
        for (i in 0 until count) {
            if (i > 0) append(';')
            if (values[i][0] != UNSET) append(values[i][0])
            for (j in 1..subCounts[i]) {
                append(':')
                if (values[i][j] != UNSET) append(values[i][j])
            }
        }
    }
}
