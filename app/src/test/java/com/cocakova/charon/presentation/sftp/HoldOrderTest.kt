package com.cocakova.charon.presentation.sftp

import com.cocakova.charon.ssh.RemoteEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldOrderTest {

    private fun e(name: String, dir: Boolean = false, size: Long = 0, mtime: Long = 0) =
        RemoteEntry(name, "/x/$name", dir, size, mtime)

    private val deck = listOf(
        e("b.txt", size = 10, mtime = 3), e(".env", size = 99, mtime = 9), e("src", dir = true, mtime = 1),
        e("A.md", size = 500, mtime = 2), e(".git", dir = true, mtime = 8),
    )

    @Test
    fun foldersFirstThenTheChosenOrder() {
        assertEquals(listOf("src", "A.md", "b.txt"), HoldOrder.arrange(deck, HoldSort.NAME, false).map { it.name })
        assertEquals(listOf("src", "A.md", "b.txt"), HoldOrder.arrange(deck, HoldSort.SIZE, false).map { it.name })
        assertEquals(listOf("src", "b.txt", "A.md"), HoldOrder.arrange(deck, HoldSort.TIME, false).map { it.name })
    }

    @Test
    fun dotfilesComeUpOnlyWhenAsked() {
        assertEquals(listOf(".git", "src", ".env", "A.md", "b.txt"), HoldOrder.arrange(deck, HoldSort.NAME, true).map { it.name })
        assertEquals(2, HoldOrder.hiddenCount(deck))
    }

    @Test
    fun picturesAreKnownByNameAndKeptSmall() {
        assertTrue(HoldOrder.isPicture(e("shot.PNG", size = 2048)))
        assertFalse(HoldOrder.isPicture(e("shot.png", size = 0)))
        assertFalse(HoldOrder.isPicture(e("huge.jpg", size = HoldOrder.MAX_PICTURE_BYTES + 1)))
        assertFalse(HoldOrder.isPicture(e("notes.txt", size = 10)))
    }
}
