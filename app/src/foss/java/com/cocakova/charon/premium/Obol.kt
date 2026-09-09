package com.cocakova.charon.premium

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import com.cocakova.charon.theme.TerminalScheme

/**
 * The Obol seam. Premium cosmetics live behind this object; the foss build is
 * the complete free Charon — no locks, no upsells, no empty chrome for what it
 * doesn't carry. An obol build swaps this object at compile time from a source
 * tree that never enters the public repo.
 */
object Obol {
    /** Liveries of the traveller's own making; the foss hold carries none. */
    @Suppress("UNUSED_PARAMETER")
    fun customLiveries(prefs: SharedPreferences): List<TerminalScheme> = emptyList()

    /** The shipwright's berth at the helm; absent from the foss build. */
    @Suppress("UNUSED_PARAMETER")
    @Composable
    fun LiveryForge(
        prefs: SharedPreferences,
        selected: String,
        onSelect: (String) -> Unit,
        onChanged: () -> Unit,
    ) {
    }
}
