package com.cocakova.charon.presentation.sftp

import com.cocakova.charon.ssh.RemoteEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * What the reader will and won't open, and what it makes of what it draws
 * aboard: the tap-to-read guess, the cap-and-sniff read, and markdown's block
 * grammar. All pure — no channel, no screen.
 */
class ScrollReaderTest {

    private fun file(name: String) = RemoteEntry(name, "/tmp/$name", isDir = false, size = 10, mtime = 0)

    // ---- what reads as a scroll ------------------------------------------------------

    @Test
    fun textExtensionsReadOnATap() {
        assertTrue(readsAsText(file("README.md")))
        assertTrue(readsAsText(file("notes.TXT")))
        assertTrue(readsAsText(file("nginx.conf")))
        assertTrue(readsAsText(file("docker-compose.yml")))
        assertTrue(readsAsText(file("main.kt")))
        assertTrue(readsAsText(file("charon.service")))
    }

    @Test
    fun bareNamesAndDotfilesReadOnATap() {
        assertTrue(readsAsText(file("README")))
        assertTrue(readsAsText(file("LICENSE")))
        assertTrue(readsAsText(file("Makefile")))
        assertTrue(readsAsText(file("Dockerfile")))
        assertTrue(readsAsText(file(".bashrc")))
        assertTrue(readsAsText(file(".gitignore")))
    }

    @Test
    fun cargoStillGoesThroughTheSheet() {
        assertFalse(readsAsText(file("ls")))            // a binary in /usr/bin
        assertFalse(readsAsText(file("charon.apk")))
        assertFalse(readsAsText(file("photo.jpg")))
        assertFalse(readsAsText(file("archive.tar.gz")))
        assertFalse(readsAsText(RemoteEntry("etc", "/etc", isDir = true, size = 0, mtime = 0)))
    }

    // ---- drawing it aboard -----------------------------------------------------------

    @Test
    fun readsAWholeSmallScroll() {
        val read = readScroll(ByteArrayInputStream("hello\nriver\n".toByteArray())) as ScrollContent.Read
        assertEquals("hello\nriver\n", read.text)
        assertFalse(read.truncated)
        assertEquals(12L, read.bytes)
    }

    @Test
    fun capsALongScrollOnAWholeLine() {
        val line = "x".repeat(99) + "\n"
        val huge = line.repeat(MAX_SCROLL_BYTES / 100 + 500)
        val read = readScroll(ByteArrayInputStream(huge.toByteArray())) as ScrollContent.Read
        assertTrue(read.truncated)
        assertEquals(MAX_SCROLL_BYTES.toLong(), read.bytes)
        // The cut lands on a line boundary: the last line is whole, not the 88
        // characters that happened to fit under the cap.
        assertEquals("x".repeat(99), read.text.substringAfterLast('\n'))
        assertTrue(read.text.length < MAX_SCROLL_BYTES)
    }

    @Test
    fun refusesCargo() {
        val binary = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 0, 0, 1, 2)
        assertTrue(readScroll(ByteArrayInputStream(binary)) is ScrollContent.Refused)
    }

    @Test
    fun keepsUtf8Intact() {
        val read = readScroll(ByteArrayInputStream("the ferryman's obol — ⇣ ✓".toByteArray())) as ScrollContent.Read
        assertEquals("the ferryman's obol — ⇣ ✓", read.text)
    }

    // ---- markdown's block grammar ----------------------------------------------------

    @Test
    fun readsHeadingsBulletsAndParagraphs() {
        val blocks = markdownBlocks(
            """
            # Charon
            The ferry, and its river.

            ## The hold
            - one
            - two
              - deeper
            """.trimIndent(),
        )
        assertEquals(MdBlock.Heading(1, "Charon"), blocks[0])
        assertEquals(MdBlock.Paragraph("The ferry, and its river."), blocks[1])
        assertEquals(MdBlock.Heading(2, "The hold"), blocks[2])
        assertEquals(MdBlock.Bullet(0, "•", "one"), blocks[3])
        assertEquals(MdBlock.Bullet(1, "•", "deeper"), blocks[5])
    }

    @Test
    fun keepsFencedCodeWhole() {
        val blocks = markdownBlocks("before\n\n```sh\nssh -p 22 host\n  indented\n```\n\nafter")
        val pre = blocks.filterIsInstance<MdBlock.Pre>().single()
        assertEquals("sh", pre.lang)
        assertEquals("ssh -p 22 host\n  indented", pre.body)
        assertEquals(MdBlock.Paragraph("after"), blocks.last())
    }

    @Test
    fun readsQuotesRulesTablesAndTasks() {
        val blocks = markdownBlocks(
            "> the toll is paid\n> twice over\n\n---\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n- [x] done\n- [ ] not",
        )
        assertEquals(MdBlock.Quote("the toll is paid\ntwice over"), blocks[0])
        assertEquals(MdBlock.Rule, blocks[1])
        assertTrue((blocks[2] as MdBlock.Pre).body.startsWith("| a | b |"))
        assertEquals(MdBlock.Bullet(0, "☑", "done"), blocks[3])
        assertEquals(MdBlock.Bullet(0, "☐", "not"), blocks[4])
    }

    @Test
    fun indentedLinesUnderABulletStayInIt() {
        val blocks = markdownBlocks("- **The terminal is the product.** Charon's emulator\n  is written from scratch —\n  truecolor.\n\nafter")
        assertEquals(
            MdBlock.Bullet(0, "•", "**The terminal is the product.** Charon's emulator is written from scratch — truecolor."),
            blocks[0],
        )
        assertEquals(MdBlock.Paragraph("after"), blocks[1])
    }

    @Test
    fun paragraphLinesJoinUntilABlankOne() {
        val blocks = markdownBlocks("one\ntwo\n\nthree")
        assertEquals(listOf(MdBlock.Paragraph("one\ntwo"), MdBlock.Paragraph("three")), blocks)
    }

    @Test
    fun setextUnderlinesAreHeadingsNotRules() {
        val blocks = markdownBlocks("Charon\n======\n\nRule below\n\n---\n")
        assertEquals(MdBlock.Heading(1, "Charon"), blocks[0])
        assertEquals(MdBlock.Rule, blocks[2])
    }
}
