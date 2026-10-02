package com.cocakova.charon.ssh

import net.schmizz.sshj.userauth.UserAuthException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Each shoal said literally, then plainly. */
class ShoalsTest {

    @Test
    fun theLiteralErrorStaysOnTop() {
        val said = Shoals.describe(ConnectException("Connection refused"), "box", 2222)
        assertEquals("Connection refused", said.lines().first())
        assertTrue(said.lines()[1].contains("port 2222"))
    }

    @Test
    fun commonShoalsHavePlainWords() {
        assertTrue(Shoals.hint(UnknownHostException("box.lan"), "box.lan", 22)!!.contains("no shore by the name box.lan"))
        assertTrue(Shoals.hint(SocketTimeoutException("connect timed out"), "box", 22)!!.contains("no answer"))
        assertTrue(Shoals.hint(UserAuthException("Exhausted available authentication methods"), "box", 22)!!.contains("username"))
        assertTrue(Shoals.hint(IOException("wrapped", ConnectException("Connection refused")), "box", 22)!!.contains("listening"))
    }

    @Test
    fun anUnknownShoalIsSaidAsItIs() {
        assertNull(Shoals.hint(IllegalStateException("something odd"), "box", 22))
        assertEquals("something odd", Shoals.describe(IllegalStateException("something odd"), "box", 22))
        assertEquals("IllegalStateException", Shoals.literal(IllegalStateException()))
    }
}
