package com.cocakova.charon.presentation.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which marked passages may leave the terminal — links from a remote are untrusted. */
class LinkPolicyTest {

    @Test
    fun webAndMailOpen() {
        assertTrue(LinkPolicy.opens("https://example.com"))
        assertTrue(LinkPolicy.opens("http://example.com/a?b=c#d"))
        assertTrue(LinkPolicy.opens("HTTPS://Example.com"))
        assertTrue(LinkPolicy.opens("mailto:ferry@example.com"))
    }

    @Test
    fun everythingElseIsCopyOnly() {
        assertFalse(LinkPolicy.opens("file://host/etc/passwd"))
        assertFalse(LinkPolicy.opens("javascript:alert(1)"))
        assertFalse(LinkPolicy.opens("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(LinkPolicy.opens("content://com.example/x"))
        assertFalse(LinkPolicy.opens("ssh://host"))
        assertFalse(LinkPolicy.opens("no scheme at all"))
        assertFalse(LinkPolicy.opens("mailto:"))
    }

    @Test
    fun malformedWebLinksDoNotOpen() {
        assertFalse(LinkPolicy.opens("https:example.com"))
        assertFalse(LinkPolicy.opens("https://"))
        assertFalse(LinkPolicy.opens("https://exa mple.com"))
        assertFalse(LinkPolicy.opens("https://example.com/\u0000x"))
    }

    @Test
    fun hostStripsUserinfoAndPort() {
        assertEquals("example.com", LinkPolicy.host("https://example.com/x"))
        assertEquals("evil.test", LinkPolicy.host("https://bank.example.com@evil.test:8443/login"))
        assertEquals("[::1]", LinkPolicy.host("http://[::1]:8080/"))
        assertNull(LinkPolicy.host("mailto:a@b.test"))
        assertNull(LinkPolicy.host("file:///etc/hosts"))
    }

    @Test
    fun schemeIsWellFormedOrNothing() {
        assertEquals("https", LinkPolicy.scheme("HTTPS://x"))
        assertEquals("git+ssh", LinkPolicy.scheme("git+ssh://x"))
        assertNull(LinkPolicy.scheme(":nope"))
        assertNull(LinkPolicy.scheme("1http://x"))
    }
}
