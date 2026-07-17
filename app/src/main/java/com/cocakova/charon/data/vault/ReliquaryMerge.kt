package com.cocakova.charon.data.vault

/**
 * The merge law, pure and testable: entities carry UUIDs and lastModified stamps,
 * and on import **newer wins** — an incoming record lands when we've never seen its
 * id, or when its stamp beats ours. Anything older stays ashore. (Known host keys
 * follow a stricter law — absent-only — decided at the call site: a pinned key is
 * never silently replaced by an import.)
 */
object ReliquaryMerge {

    /** What an import would do for one table. [land] = the records that win. */
    data class Tally<T>(
        val land: List<T>,
        val fresh: Int,
        val refreshed: Int,
        val keptOurs: Int,
    )

    fun <T> plan(
        ours: Map<String, Long>,
        theirs: List<T>,
        id: (T) -> String,
        modified: (T) -> Long,
    ): Tally<T> {
        val land = ArrayList<T>()
        var fresh = 0
        var refreshed = 0
        var kept = 0
        for (t in theirs) {
            val mine = ours[id(t)]
            when {
                mine == null -> { land += t; fresh++ }
                modified(t) > mine -> { land += t; refreshed++ }
                else -> kept++
            }
        }
        return Tally(land, fresh, refreshed, kept)
    }
}
