package com.cocakova.charon.data.vault

import org.junit.Assert.assertEquals
import org.junit.Test

/** The merge law: unknown id lands fresh, newer stamp refreshes, older stays ashore. */
class ReliquaryMergeTest {

    private data class Rec(val id: String, val modified: Long)

    private fun plan(ours: Map<String, Long>, theirs: List<Rec>) =
        ReliquaryMerge.plan(ours, theirs, { it.id }, { it.modified })

    @Test
    fun `unknown ids land fresh`() {
        val tally = plan(emptyMap(), listOf(Rec("a", 5), Rec("b", 9)))
        assertEquals(2, tally.fresh)
        assertEquals(0, tally.refreshed)
        assertEquals(0, tally.keptOurs)
        assertEquals(listOf("a", "b"), tally.land.map { it.id })
    }

    @Test
    fun `newer stamps refresh, older stay ashore`() {
        val ours = mapOf("a" to 10L, "b" to 10L, "c" to 10L)
        val tally = plan(ours, listOf(Rec("a", 20), Rec("b", 10), Rec("c", 3), Rec("d", 1)))
        assertEquals(1, tally.fresh)      // d
        assertEquals(1, tally.refreshed)  // a
        assertEquals(2, tally.keptOurs)   // b (tie goes to ours), c
        assertEquals(listOf("a", "d"), tally.land.map { it.id })
    }

    @Test
    fun `an empty reliquary lands nothing`() {
        val tally = plan(mapOf("a" to 1L), emptyList())
        assertEquals(0, tally.land.size)
        assertEquals(0, tally.fresh + tally.refreshed + tally.keptOurs)
    }
}
