package com.cocakova.charon.cargo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lading's two grammars: the command lines that invoke a package manager,
 * and the output formats the managers print while cargo moves.
 */
class CargoLadingTest {

    @Test
    fun matchesAptUnderSudo() {
        assertEquals("apt", CargoLading.match("sudo apt install moonlight-qt"))
        assertEquals("apt-get", CargoLading.match("sudo apt-get -y install nginx"))
        assertEquals("apt", CargoLading.match("apt upgrade"))
    }

    @Test
    fun sudoFlagsAndEnvAssignmentsAreTransparent() {
        assertEquals("apt", CargoLading.match("DEBIAN_FRONTEND=noninteractive sudo -E apt install curl"))
        assertEquals("dnf", CargoLading.match("sudo --preserve-env dnf install htop"))
    }

    @Test
    fun matchesDashVerbManagers() {
        assertEquals("pacman", CargoLading.match("sudo pacman -Syu"))
        assertEquals("pacman", CargoLading.match("pacman -S firefox"))
        assertEquals("dpkg", CargoLading.match("sudo dpkg -i moonlight.deb"))
    }

    @Test
    fun matchesLanguageManagers() {
        assertEquals("pip3", CargoLading.match("pip3 install requests"))
        assertEquals("npm", CargoLading.match("npm i express"))
        assertEquals("cargo", CargoLading.match("cargo install ripgrep"))
    }

    @Test
    fun pathPrefixesAreStripped() {
        assertEquals("apt", CargoLading.match("sudo /usr/bin/apt install jq"))
    }

    @Test
    fun ignoresNonCargoCommands() {
        assertNull(CargoLading.match("ls -la"))
        assertNull(CargoLading.match("sudo systemctl restart nginx"))
        assertNull(CargoLading.match("apt search moonlight"))
        assertNull(CargoLading.match("git push"))
        assertNull(CargoLading.match(""))
    }

    @Test
    fun gleansAptOutput() {
        val g = CargoLading.glean(
            listOf(
                "Get:1 http://archive.ubuntu.com/ubuntu jammy/main amd64 moonlight-qt amd64 5.0.1 [9,204 kB]",
                "Unpacking nginx-core (1.18.0-6ubuntu14) ...",
                "Progress: [ 47%]",
            ),
        )
        assertTrue(g.verbSeen)
        assertEquals("nginx-core", g.item)
        assertEquals(47, g.percent)
    }

    @Test
    fun gleansSettingUpAndTriggers() {
        val g = CargoLading.glean(listOf("Setting up moonlight-qt (5.0.1) ..."))
        assertTrue(g.verbSeen)
        assertEquals("moonlight-qt", g.item)
        assertNull(g.percent)
    }

    @Test
    fun gleansPipOutput() {
        val g = CargoLading.glean(listOf("Collecting requests>=2.28", "     |████████████| 62.6/62.6 kB 100%"))
        assertTrue(g.verbSeen)
        assertEquals("requests", g.item)
        assertEquals(100, g.percent)
    }

    @Test
    fun quietPromptGleansNothing() {
        val g = CargoLading.glean(listOf("jonny@spark:~$", ""))
        assertFalse(g.verbSeen)
        assertNull(g.item)
        assertNull(g.percent)
    }
}
