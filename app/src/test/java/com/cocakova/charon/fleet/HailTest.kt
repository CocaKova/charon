package com.cocakova.charon.fleet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Hailing a ferry: what the Dock's one-off field and ssh:// links accept. */
class HailTest {

    @Test
    fun plainUserAtHost() {
        assertEquals(HailTarget("me", "spark", 22), Hail.parse("me@spark"))
        assertEquals(HailTarget("me", "10.0.0.5", 2222), Hail.parse("  me@10.0.0.5:2222 "))
        assertEquals(HailTarget(null, "spark.lan", 22), Hail.parse("spark.lan"))
    }

    @Test
    fun sshLinks() {
        assertEquals(HailTarget("me", "host", 2200), Hail.parse("ssh://me@host:2200"))
        assertEquals(HailTarget("me", "host", 22), Hail.parse("SSH://me@host/"))
        // The draft fingerprint parameter is dropped: the ferryman checks keys himself.
        assertEquals(
            HailTarget("me", "host", 22),
            Hail.parse("ssh://me;fingerprint=ssh-ed25519-AAAA@host"),
        )
        assertEquals(HailTarget("a@b", "host", 22), Hail.parse("ssh://a%40b@host"))
    }

    @Test
    fun ipv6() {
        assertEquals(HailTarget("me", "fe80::1", 22), Hail.parse("me@[fe80::1]"))
        assertEquals(HailTarget("me", "fe80::1", 2222), Hail.parse("me@[fe80::1]:2222"))
        assertEquals(HailTarget("me", "::1", 22), Hail.parse("me@::1"))
        assertEquals("me@[::1]:2222", HailTarget("me", "::1", 2222).address)
    }

    @Test
    fun addressSpellsItBack() {
        assertEquals("me@spark", HailTarget("me", "spark").address)
        assertEquals("me@spark:2222", HailTarget("me", "spark", 2222).address)
        assertEquals("spark", HailTarget(null, "spark").address)
    }

    @Test
    fun refusals() {
        assertNull(Hail.parse(""))
        assertNull(Hail.parse("me@"))
        assertNull(Hail.parse("me@host:0"))
        assertNull(Hail.parse("me@host:70000"))
        assertNull(Hail.parse("me@host:ssh"))
        assertNull(Hail.parse("me @host"))
        assertNull(Hail.parse("me@ho st"))
        assertNull(Hail.parse("ssh://me@host/path"))
        assertNull(Hail.parse("me:secret@host"))
        assertNull(Hail.parse("me@[fe80::1"))
        assertNull(Hail.parse("me@[host]"))
        assertNull(Hail.parse("me@host;rm -rf"))
        assertNull(Hail.parse("ssh://a%4@host"))
    }

    @Test
    fun anEmptyUserIsNoUser() {
        assertEquals(HailTarget(null, "host", 22), Hail.parse("@host"))
    }
}
