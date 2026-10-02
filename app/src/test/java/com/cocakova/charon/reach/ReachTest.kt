package com.cocakova.charon.reach

import com.cocakova.charon.data.db.HostEntity
import com.cocakova.charon.fleet.HailTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The ways in: what each intent asks for, and nothing it doesn't. */
class ReachTest {

    private val view = "android.intent.action.VIEW"

    @Test
    fun anSshLinkOpensTheHailFilledIn() {
        assertEquals(
            Reach.Ask.HailFor(HailTarget("me", "box.lan", 2222)),
            Reach.askOf(view, "ssh://me@box.lan:2222", null, null, -1),
        )
        assertEquals(
            Reach.Ask.HailFor(HailTarget(null, "box.lan", 22)),
            Reach.askOf(view, "SSH://box.lan/", null, null, -1),
        )
    }

    @Test
    fun aLinkThatIsNotAShoreAsksNothing() {
        assertNull(Reach.askOf(view, "ssh://me@box.lan/etc/passwd", null, null, -1))
        assertNull(Reach.askOf(view, "https://box.lan", null, null, -1))
        assertNull(Reach.askOf(view, null, null, null, -1))
        assertNull(Reach.askOf("android.intent.action.MAIN", null, null, null, -1))
    }

    @Test
    fun shortcutsAndHornsNameTheirMooringOrCrossing() {
        assertEquals(Reach.Ask.Moor("h1"), Reach.askOf(Reach.ACTION_MOOR, null, "h1", null, -1))
        assertNull(Reach.askOf(Reach.ACTION_MOOR, null, "", null, -1))
        assertEquals(Reach.Ask.Board("s1", 7), Reach.askOf(Reach.ACTION_BOARD, null, null, "s1", 7))
        assertNull(Reach.askOf(Reach.ACTION_BOARD, null, null, null, 7))
    }

    @Test
    fun theLatestCrossedComeFirstOnTheLauncher() {
        fun h(id: String, at: Long) = HostEntity(
            id = id, name = id, host = "$id.lan", port = 22, username = "u", passwordSealed = null,
            identityId = null, harbor = "", lastConnectedAt = at, createdAt = 0, lastModified = 0,
        )
        val picked = Reach.shortcutHosts(listOf(h("b", 0), h("a", 0), h("c", 9), h("d", 5)), 3)
        assertEquals(listOf("c", "d", "a"), picked.map { it.id })
    }
}
