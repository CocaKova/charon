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

    @Test
    fun `the far shore's localhost is named for carrying`() {
        assertEquals("localhost" to 5173, LinkPolicy.loopback("http://localhost:5173/"))
        assertEquals("127.0.0.1" to 8000, LinkPolicy.loopback("http://127.0.0.1:8000/docs?x=1"))
        assertEquals("::1" to 3000, LinkPolicy.loopback("http://[::1]:3000"))
        assertEquals("localhost" to 80, LinkPolicy.loopback("http://0.0.0.0"))
        assertEquals("localhost" to 443, LinkPolicy.loopback("https://LOCALHOST/"))
        assertNull(LinkPolicy.loopback("http://example.com:5173/"))
        assertNull(LinkPolicy.loopback("http://localhost:99999/"))
        assertNull(LinkPolicy.loopback("ftp://localhost:21/"))
    }

    @Test
    fun `a carried link points at the phone and keeps its path`() {
        assertEquals("http://localhost:5173/src/main.ts?t=1#a", LinkPolicy.onPhone("http://localhost:5173/src/main.ts?t=1#a", 5173))
        assertEquals("http://localhost:41234", LinkPolicy.onPhone("http://[::1]:3000", 41234))
        assertEquals("https://localhost:8443/", LinkPolicy.onPhone("https://u@127.0.0.1/", 8443))
    }

    @Test
    fun `file links name a path on the far shore`() {
        assertEquals("/home/me/notes.md", LinkPolicy.filePath("file://box/home/me/notes.md"))
        assertEquals("/home/me/a b", LinkPolicy.filePath("file://box/home/me/a%20b"))
        assertEquals("/srv", LinkPolicy.filePath("file:/srv"))
        assertNull(LinkPolicy.filePath("file://box"))
        assertNull(LinkPolicy.filePath("https://x/y"))
        assertNull(LinkPolicy.filePath("file://box/a%0Ab"))
    }
}
