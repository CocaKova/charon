package com.cocakova.charon.fleet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TailscaleImportTest {

    // Trimmed to the fields we read, but shaped exactly like real
    // `tailscale status --json` output: Self separate, Peer keyed by node key.
    private val status = """
        {
          "Version": "1.86.2",
          "BackendState": "Running",
          "Self": {
            "ID": "1",
            "HostName": "phone",
            "DNSName": "phone.tail1234.ts.net.",
            "OS": "android",
            "TailscaleIPs": ["100.93.255.23", "fd7a::1"],
            "Online": true
          },
          "Peer": {
            "nodekey:aaa": {
              "ID": "2",
              "HostName": "spark",
              "DNSName": "spark.tail1234.ts.net.",
              "OS": "linux",
              "TailscaleIPs": ["100.69.63.18", "fd7a::2"],
              "Online": true
            },
            "nodekey:bbb": {
              "ID": "3",
              "HostName": "desktop",
              "DNSName": "desktop.tail1234.ts.net.",
              "OS": "windows",
              "TailscaleIPs": ["100.101.102.103"],
              "Online": false
            },
            "nodekey:ccc": {
              "ID": "4",
              "HostName": "",
              "DNSName": "nas.tail1234.ts.net.",
              "OS": "linux",
              "TailscaleIPs": ["fd7a::4"],
              "Online": true
            }
          }
        }
    """.trimIndent()

    @Test
    fun `parses peers, never self`() {
        val fleet = TailscaleImport.parse(status)
        assertEquals(3, fleet.size)
        assertTrue(fleet.none { it.name == "phone" || it.host == "100.93.255.23" })
    }

    @Test
    fun `prefers the tailnet ipv4, falls back to magicdns`() {
        val fleet = TailscaleImport.parse(status)
        assertEquals("100.69.63.18", fleet.first { it.name == "spark" }.host)
        // nodekey:ccc has no IPv4 — the DNS name (trailing dot trimmed) steps in,
        // and its blank HostName falls back to the DNS shortname.
        val nas = fleet.first { it.host == "nas.tail1234.ts.net" }
        assertEquals("nas", nas.name)
    }

    @Test
    fun `online ships sail to the top`() {
        val fleet = TailscaleImport.parse(status)
        assertTrue(fleet.first().online)
        assertEquals("desktop", fleet.last().name)
    }

    @Test
    fun `carries os and online through`() {
        val fleet = TailscaleImport.parse(status)
        val desktop = fleet.first { it.name == "desktop" }
        assertEquals("windows", desktop.os)
        assertEquals(false, desktop.online)
    }

    @Test
    fun `refuses text that is not a status`() {
        assertThrows(IllegalArgumentException::class.java) {
            TailscaleImport.parse("tailscale: command not found")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TailscaleImport.parse("""{"BackendState":"Stopped"}""")
        }
    }
}
