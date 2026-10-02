package com.cocakova.charon.presentation.sftp

import com.cocakova.charon.ssh.RemoteEntry

/**
 * How a deck is laid out: folders always first, then by name, by size (largest
 * first) or by when they last changed (newest first); dotfiles shown or kept below
 * deck. Pure, so the order is tested without a hold.
 */
enum class HoldSort(val label: String) { NAME("name"), SIZE("size"), TIME("time") }

object HoldOrder {
    fun arrange(entries: List<RemoteEntry>, sort: HoldSort, showHidden: Boolean): List<RemoteEntry> {
        val visible = if (showHidden) entries else entries.filterNot { it.name.startsWith('.') }
        val within: Comparator<RemoteEntry> = when (sort) {
            HoldSort.NAME -> compareBy { it.name.lowercase() }
            HoldSort.SIZE -> compareByDescending<RemoteEntry> { it.size }.thenBy { it.name.lowercase() }
            HoldSort.TIME -> compareByDescending<RemoteEntry> { it.mtime }.thenBy { it.name.lowercase() }
        }
        return visible.sortedWith(compareByDescending<RemoteEntry> { it.isDir }.then(within))
    }

    /** How many entries the hidden toggle is keeping below deck. */
    fun hiddenCount(entries: List<RemoteEntry>): Int = entries.count { it.name.startsWith('.') }

    private val IMAGE = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif")

    /** An image the phone can draw, small enough to bring aboard for a look. */
    fun isPicture(entry: RemoteEntry): Boolean =
        !entry.isDir && entry.size in 1..MAX_PICTURE_BYTES &&
            entry.name.substringAfterLast('.', "").lowercase() in IMAGE

    const val MAX_PICTURE_BYTES = 24L * 1024 * 1024

    /** Text small enough to edit in place and write back whole. */
    const val MAX_EDIT_BYTES = 256L * 1024
}
