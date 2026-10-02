package com.cocakova.charon.fleet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ~/.ssh/config into moorings, read the way ssh itself reads it. */
class SshConfigImportTest {

    private val config = """
        # the fleet
        Host *
            ServerAliveInterval 30
            User fallback

        Host spark
            HostName 10.0.0.5
            User me
            Port 2222

        Host nas backup
            HostName %h.lan
            IdentityFile ~/.ssh/id_nas

        Host inner
            HostName 192.168.9.2
            ProxyJump spark

        Host *.corp !skip.corp
            User corp

        Host "quoted alias"
            HostName quoted.example

        Match host foo
            User nobody
        Include ~/.ssh/extra
    """.trimIndent()

    private fun entries() = SshConfigImport.parse(config).entries.associateBy { it.alias }

    @Test
    fun aHostBlockBecomesAShip() {
        val spark = entries().getValue("spark")
        assertEquals("10.0.0.5", spark.hostName)
        assertEquals(2222, spark.port)
    }

    @Test
    fun firstValueWinsSoAnEarlyStarSetsTheDefault() {
        // ssh_config(5): the first obtained value wins, and Host * came first.
        assertEquals("fallback", entries().getValue("spark").user)
        assertEquals("fallback", entries().getValue("nas").user)
    }

    @Test
    fun percentHIsTheAlias() {
        assertEquals("nas.lan", entries().getValue("nas").hostName)
        assertEquals("backup.lan", entries().getValue("backup").hostName)
    }

    @Test
    fun proxyJumpIsKeptAsWritten() {
        assertEquals("spark", entries().getValue("inner").proxyJump)
        assertNull(entries().getValue("spark").proxyJump)
    }

    @Test
    fun wildcardsAreDefaultsNeverShips() {
        val names = entries().keys
        assertFalse(names.any { '*' in it || it.startsWith("!") })
        assertTrue("quoted alias" in names)
    }

    @Test
    fun negationAndGlobsMatchLikeSsh() {
        assertTrue(SshConfigImport.matches("db.corp", listOf("*.corp", "!skip.corp")))
        assertFalse(SshConfigImport.matches("skip.corp", listOf("*.corp", "!skip.corp")))
        assertTrue(SshConfigImport.matches("web1", listOf("web?")))
    }

    @Test
    fun whatCantCrossIsReportedNotGuessed() {
        val notes = SshConfigImport.parse(config).notes
        assertTrue(notes.any { "Match" in it })
        assertTrue(notes.any { "Include" in it })
        assertTrue(notes.any { "IdentityFile" in it })
        // The Match block's options never leak into the ships.
        assertTrue(entries().values.none { it.user == "nobody" })
    }

    @Test
    fun equalsSignsAndCaseAreForgiven() {
        val e = SshConfigImport.parse("host Box\n  hostname=box.lan\n  PORT = 2200\n").entries.single()
        assertEquals("box.lan", e.hostName)
        assertEquals(2200, e.port)
    }
}

/** Chosen entries into moorings: their own user/port, shared fallbacks, jumps found or named. */
class SshConfigPlanTest {

    private val text = """
        Host bastion
            HostName gate.example
            User keeper
        Host inner
            HostName 10.9.0.2
            ProxyJump bastion
            ForwardAgent yes
        Host deep
            HostName 10.9.0.3
            Port 2200
            ProxyJump ops@gate.example
        Host lost
            HostName 10.9.0.4
            ProxyJump nowhere
    """.trimIndent()

    private val entries = SshConfigImport.parse(text).entries.associateBy { it.alias }
    private var n = 0
    private fun ids() = { "id${++n}" }

    @Test
    fun aJumpToAShipMooredInTheSameBreathFindsIt() {
        val plan = SshConfigImport.plan(
            listOf(entries.getValue("bastion"), entries.getValue("inner")), emptyList(), "me", 22, ids(),
        )
        val byAlias = plan.moorings.associateBy { it.alias }
        assertEquals(byAlias.getValue("bastion").id, byAlias.getValue("inner").jumpHostId)
        assertEquals("keeper", byAlias.getValue("bastion").username)
        assertEquals("me", byAlias.getValue("inner").username) // no User: the sheet's
        assertTrue(byAlias.getValue("inner").forwardAgent)
        assertTrue(plan.notes.isEmpty())
    }

    @Test
    fun aJumpFindsAMooringAlreadyKeptByNameOrAddress() {
        val kept = listOf(
            SshConfigImport.Moored("m1", "bastion", "gate.example", 22, "keeper"),
            SshConfigImport.Moored("m2", "gate-ops", "gate.example", 22, "ops"),
        )
        val plan = SshConfigImport.plan(
            listOf(entries.getValue("inner"), entries.getValue("deep")), kept, "me", 22, ids(),
        )
        val byAlias = plan.moorings.associateBy { it.alias }
        assertEquals("m1", byAlias.getValue("inner").jumpHostId)   // by name
        assertEquals("m2", byAlias.getValue("deep").jumpHostId)    // by user@address
        assertEquals(2200, byAlias.getValue("deep").port)
    }

    @Test
    fun aJumpWithNoShoreIsNamedAndCrossesStraight() {
        val plan = SshConfigImport.plan(listOf(entries.getValue("lost")), emptyList(), "me", 22, ids())
        assertNull(plan.moorings.single().jumpHostId)
        assertTrue(plan.notes.single().contains("nowhere"))
    }
}
