package com.cocakova.charon.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * Apparitions on the wire: the Kitty and iTerm2 graphics protocols, read headlessly.
 *
 * Nothing here decodes a pixel — that is the renderer's half. What is pinned is the
 * part that has to be right before a bitmap is ever asked for: which bytes arrived,
 * how many cells they claim, where the cursor ends up, and what the sender is told.
 */
class ApparitionTest {

    private val responses = mutableListOf<String>()
    private val term = TerminalEmulator(
        80, 24,
        scrollbackLines = 100,
        onResponse = { responses += it },
    )

    private val esc = "\u001B"
    private val st = "$esc\\"


    private fun kitty(controls: String, payload: String = "") {
        term.write(esc + "_G" + controls + ";" + payload + st)
    }

    private fun imgcat(args: String, payload: String) {
        term.write(esc + "]1337;File=" + args + ":" + payload + st)
    }

    private fun b64(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val sb = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            sb.append(alphabet[b0 shr 2])
            sb.append(alphabet[((b0 and 0x03) shl 4) or (b1 shr 4)])
            sb.append(if (i + 1 < bytes.size) alphabet[((b1 and 0x0F) shl 2) or (b2 shr 6)] else '=')
            sb.append(if (i + 2 < bytes.size) alphabet[b2 and 0x3F] else '=')
            i += 3
        }
        return sb.toString()
    }

    /** A PNG header is all the emulator ever reads; the pixels belong to the phone. */
    private fun png(w: Int, h: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        out.write(byteArrayOf(0, 0, 0, 13))
        out.write("IHDR".toByteArray())
        fun be32(v: Int) = out.write(byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte()))
        be32(w)
        be32(h)
        out.write(byteArrayOf(8, 6, 0, 0, 0)) // depth, colour type, compression, filter, interlace
        be32(0) // CRC placeholder — nothing here verifies it
        return out.toByteArray()
    }

    private fun placements(row: Int): List<ApparitionPlacement> =
        term.screen.line(row).apparitions.orEmpty()

    // -------------------------------------------------------------------- parser

    @Test
    fun apcStringsReachTheSinkWhileSosAndPmStayDiscarded() {
        val seen = mutableListOf<String>()
        val parser = Parser(object : ParserSink {
            override fun print(codePoint: Int) {}
            override fun execute(control: Int) {}
            override fun csiDispatch(params: CsiParams, collected: String, final: Char) {}
            override fun escDispatch(collected: String, final: Char) {}
            override fun oscDispatch(payload: String) {}
            override fun apcDispatch(payload: String) { seen += payload }
            override fun dcsHook(params: CsiParams, collected: String, final: Char) {}
            override fun dcsPut(codePoint: Int) {}
            override fun dcsUnhook() {}
        })
        parser.feed("${esc}_Ghello$st")
        parser.feed("${esc}Xsos-payload$st")
        parser.feed("${esc}^pm-payload$st")
        assertEquals(listOf("Ghello"), seen)
    }

    @Test
    fun anApcStringTerminatedByEightBitStIsStillRead() {
        val seen = mutableListOf<String>()
        val parser = Parser(object : ParserSink {
            override fun print(codePoint: Int) {}
            override fun execute(control: Int) {}
            override fun csiDispatch(params: CsiParams, collected: String, final: Char) {}
            override fun escDispatch(collected: String, final: Char) {}
            override fun oscDispatch(payload: String) {}
            override fun apcDispatch(payload: String) { seen += payload }
            override fun dcsHook(params: CsiParams, collected: String, final: Char) {}
            override fun dcsPut(codePoint: Int) {}
            override fun dcsUnhook() {}
        })
        parser.feed("${esc}_Gi=1;AAAA\u009C")
        assertEquals(listOf("Gi=1;AAAA"), seen)
    }

    @Test
    fun anInterruptedApcStringNeverDispatchesHalfACommand() {
        val seen = mutableListOf<String>()
        val parser = Parser(object : ParserSink {
            override fun print(codePoint: Int) {}
            override fun execute(control: Int) {}
            override fun csiDispatch(params: CsiParams, collected: String, final: Char) {}
            override fun escDispatch(collected: String, final: Char) {}
            override fun oscDispatch(payload: String) {}
            override fun apcDispatch(payload: String) { seen += payload }
            override fun dcsHook(params: CsiParams, collected: String, final: Char) {}
            override fun dcsPut(codePoint: Int) {}
            override fun dcsUnhook() {}
        })
        parser.feed("${esc}_Gi=1;AAA${esc}[0m")
        assertTrue(seen.isEmpty())
    }

    // --------------------------------------------------------------------- kitty

    @Test
    fun aTransmittedAndDisplayedImageClaimsItsNaturalCells() {
        kitty("a=T,f=100,t=d,i=7", b64(png(32, 32)))
        val p = placements(0)
        assertEquals(1, p.size)
        assertEquals(7L, p[0].imageId)
        assertEquals(0, p[0].startCol)
        // 8px cells wide, 16px tall by default.
        assertEquals(4, p[0].cols)
        assertEquals(2, p[0].rows)
    }

    @Test
    fun explicitCellCountsAreHonoured() {
        kitty("a=T,f=100,t=d,i=1,c=10,r=5", b64(png(400, 400)))
        val p = placements(0).single()
        assertEquals(10, p.cols)
        assertEquals(5, p.rows)
    }

    @Test
    fun givingOnlyTheWidthKeepsTheAspectRatio() {
        // 100x100 image in 10 columns: 80px wide, so 80px tall = 5 rows of 16px.
        kitty("a=T,f=100,t=d,i=1,c=10", b64(png(100, 100)))
        val p = placements(0).single()
        assertEquals(10, p.cols)
        assertEquals(5, p.rows)
    }

    @Test
    fun anImageWiderThanTheLineIsScaledDownRatherThanCropped() {
        kitty("a=T,f=100,t=d,i=1", b64(png(4000, 400)))
        val p = placements(0).single()
        assertEquals(80, p.cols) // the whole line, no more
        assertTrue("rows should shrink with the width, was ${p.rows}", p.rows in 1..25)
    }

    @Test
    fun theCursorStepsPastTheImageSoTextLandsBelowIt() {
        kitty("a=T,f=100,t=d,i=1,c=6,r=3", b64(png(48, 48)))
        assertEquals(2, term.cursorY) // rows - 1 linefeeds
        assertEquals(6, term.cursorX)
    }

    @Test
    fun cursorMovementCanBeDeclined() {
        kitty("a=T,f=100,t=d,i=1,c=6,r=3,C=1", b64(png(48, 48)))
        assertEquals(0, term.cursorY)
        assertEquals(0, term.cursorX)
    }

    @Test
    fun aChunkedTransmissionIsReassembledBeforeItLands() {
        val payload = b64(png(64, 64))
        val half = payload.length / 2
        kitty("a=T,f=100,t=d,i=9,m=1", payload.substring(0, half))
        assertTrue("nothing should be placed mid-transmission", placements(0).isEmpty())
        kitty("m=0", payload.substring(half))
        val p = placements(0).single()
        assertEquals(9L, p.imageId)
        assertEquals(8, p.cols)
        assertEquals(4, p.rows)
    }

    @Test
    fun aZlibCompressedPayloadIsInflated() {
        val raw = png(16, 16)
        val deflater = Deflater()
        deflater.setInput(raw)
        deflater.finish()
        val buf = ByteArray(1024)
        val n = deflater.deflate(buf)
        deflater.end()
        kitty("a=T,f=100,t=d,o=z,i=3", b64(buf.copyOf(n)))
        val p = placements(0).single()
        assertEquals(3L, p.imageId)
        assertEquals(2, p.cols)
        assertEquals(1, p.rows)
    }

    @Test
    fun rawRgbPixelsAreAcceptedWithTheirDeclaredSize() {
        val pixels = ByteArray(8 * 16 * 3)
        kitty("a=T,f=24,t=d,i=4,s=8,v=16", b64(pixels))
        val image = term.apparitions.store[4L]
        assertNotNull(image)
        assertEquals(ImageFormat.RGB, image!!.format)
        assertEquals(8, image.pixelWidth)
        assertEquals(16, image.pixelHeight)
    }

    @Test
    fun rawPixelsShortOfTheirDeclaredSizeAreRefused() {
        kitty("a=T,f=32,t=d,i=5,s=64,v=64", b64(ByteArray(16)))
        assertNull(term.apparitions.store[5L])
        assertTrue(responses.any { it.contains("EINVAL") })
    }

    @Test
    fun transmitOnlyStoresWithoutDrawing() {
        kitty("a=t,f=100,t=d,i=11", b64(png(32, 32)))
        assertNotNull(term.apparitions.store[11L])
        assertTrue(placements(0).isEmpty())

        kitty("a=p,i=11,c=3,r=2")
        val p = placements(0).single()
        assertEquals(11L, p.imageId)
        assertEquals(3, p.cols)
    }

    @Test
    fun aQueryIsAnsweredSoSendersKnowWeSpeakTheProtocol() {
        kitty("a=q,i=31,s=1,v=1,t=d,f=24", b64(ByteArray(3)))
        assertEquals(listOf("${esc}_Gi=31;OK$st"), responses)
    }

    @Test
    fun anUnsupportedTransmissionMediumIsRefusedByName() {
        kitty("a=T,f=100,t=f,i=2", b64("/tmp/x".toByteArray()))
        assertTrue(responses.single().contains("EBADF"))
        assertTrue(placements(0).isEmpty())
    }

    @Test
    fun quietOneSuppressesTheOkButNotTheError() {
        kitty("a=T,f=100,t=d,i=1,q=1", b64(png(8, 8)))
        assertTrue(responses.isEmpty())
        kitty("a=T,f=32,t=d,i=2,q=1,s=99,v=99", b64(ByteArray(4)))
        assertTrue(responses.single().contains("EINVAL"))
    }

    @Test
    fun quietTwoSuppressesEverything() {
        kitty("a=T,f=32,t=d,i=2,q=2,s=99,v=99", b64(ByteArray(4)))
        assertTrue(responses.isEmpty())
    }

    @Test
    fun anUnaddressedCommandIsAnsweredWithSilence() {
        kitty("a=T,f=100,t=d", b64(png(8, 8)))
        assertTrue(responses.isEmpty())
        assertEquals(1, placements(0).size)
    }

    @Test
    fun deletingByIdRemovesThePlacementAndCanFreeThePixels() {
        kitty("a=T,f=100,t=d,i=6,c=2,r=1", b64(png(16, 16)))
        assertEquals(1, placements(0).size)
        kitty("a=d,d=i,i=6")
        assertTrue(placements(0).isEmpty())
        assertNotNull("lowercase d keeps the image itself", term.apparitions.store[6L])
        kitty("a=d,d=I,i=6")
        assertNull(term.apparitions.store[6L])
    }

    @Test
    fun deletingAllSweepsEveryPlacement() {
        kitty("a=T,f=100,t=d,i=1,c=2,r=1", b64(png(16, 16)))
        kitty("a=T,f=100,t=d,i=2,c=2,r=1", b64(png(16, 16)))
        assertTrue(placements(0).isNotEmpty() || placements(1).isNotEmpty())
        kitty("a=d,d=a")
        for (r in 0 until 24) assertTrue(placements(r).isEmpty())
    }

    // ---------------------------------------------------------------- anchoring

    @Test
    fun anApparitionRidesItsLineIntoScrollback() {
        kitty("a=T,f=100,t=d,i=1,c=4,r=1,C=1", b64(png(32, 16)))
        assertEquals(1, placements(0).size)

        // Push the anchor line off the top of the screen.
        repeat(30) { term.write("\r\n") }
        for (r in 0 until 24) assertTrue("row $r should be clear", placements(r).isEmpty())

        val history = (0 until term.primary.scrollbackSize)
            .map { term.primary.scrollbackLine(it) }
            .filter { !it.apparitions.isNullOrEmpty() }
        assertEquals("the shade should be in the history, once", 1, history.size)
    }

    @Test
    fun oneLineWillNotHoardShadesForever() {
        // A redraw loop on the same line: unnamed placements must not stack up.
        repeat(30) { kitty("a=T,f=100,t=d,c=4,r=1,C=1", b64(png(32, 16))) }
        assertTrue(placements(0).size <= Line.MAX_PLACEMENTS_PER_LINE)
    }

    @Test
    fun aNamedPlacementIsReplacedRatherThanRepeated() {
        repeat(5) { kitty("a=T,f=100,t=d,i=2,p=1,c=4,r=1,C=1", b64(png(32, 16))) }
        assertEquals(1, placements(0).size)
    }

    @Test
    fun clearingTheScreenClearsWhatWasDrawnOverIt() {
        kitty("a=T,f=100,t=d,i=1,c=4,r=1,C=1", b64(png(32, 16)))
        assertEquals(1, placements(0).size)
        term.write("${esc}[2J")
        assertTrue(placements(0).isEmpty())
    }

    // -------------------------------------------------------------------- imgcat

    @Test
    fun imgcatRendersInlineImagesAtTheirNaturalSize() {
        imgcat("inline=1;size=99", b64(png(40, 32)))
        val p = placements(0).single()
        assertEquals(5, p.cols)
        assertEquals(2, p.rows)
    }

    @Test
    fun imgcatWidthInCellsIsHonoured() {
        imgcat("inline=1;width=20;height=4", b64(png(160, 64)))
        val p = placements(0).single()
        assertEquals(20, p.cols)
        assertEquals(4, p.rows)
    }

    @Test
    fun imgcatWidthInPixelsBecomesCells() {
        imgcat("inline=1;width=80px", b64(png(160, 160)))
        val p = placements(0).single()
        assertEquals(10, p.cols)
    }

    @Test
    fun imgcatPercentIsOfTheGrid() {
        imgcat("inline=1;width=50%", b64(png(320, 320)))
        val p = placements(0).single()
        assertEquals(40, p.cols)
    }

    @Test
    fun aFileTransferThatIsNotInlineIsNotAPicture() {
        imgcat("inline=0;name=eGZpbGU=", b64(png(32, 32)))
        assertTrue(placements(0).isEmpty())
    }

    @Test
    fun aMalformedInlineImageIsIgnoredRatherThanCrashing() {
        term.write("$esc]1337;File=inline=1:!!!not base64!!!$st")
        term.write("$esc]1337;File=inline=1$st")
        term.write("$esc]1337;nonsense$st")
        assertTrue(placements(0).isEmpty())
    }

    // ---------------------------------------------------------------- reporting

    @Test
    fun xtversionNamesTheFerrySoToolsLightUp() {
        term.write("$esc[>q")
        assertEquals("${esc}P>|Charon(1.0)$st", responses.single())
    }

    @Test
    fun cellSizeIsReportable() {
        term.cellWidthPx = 9
        term.cellHeightPx = 20
        term.write("$esc[16t")
        assertEquals("$esc[6;20;9t", responses.single())
    }

    // ------------------------------------------------------------------- pieces

    @Test
    fun imageHeadersAreMeasuredWithoutDecoding() {
        assertEquals(64 to 48, ImageHeader.measure(png(64, 48)))

        val gif = "GIF89a".toByteArray() + byteArrayOf(0x20, 0, 0x10, 0, 0, 0, 0, 0)
        assertEquals(32 to 16, ImageHeader.measure(gif))

        // JPEG: SOI, an APP0 to skip, then SOF0 carrying height then width.
        val jpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xE0.toByte(), 0, 4, 0, 0,
            0xFF.toByte(), 0xC0.toByte(), 0, 17, 8,
            0, 0x40, 0, 0x60, 3,
        )
        assertEquals(0x60 to 0x40, ImageHeader.measure(jpeg))

        assertNull(ImageHeader.measure(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun wireBase64RoundTripsAndRejectsRubbish() {
        val bytes = ByteArray(255) { it.toByte() }
        assertTrue(bytes.contentEquals(WireBase64.decode(b64(bytes))))
        assertEquals(0, WireBase64.decode("")!!.size)
        assertNull(WireBase64.decode("not base64!"))
    }

    @Test
    fun theStoreSinksTheOldestShadeWhenTheBudgetIsSpent() {
        val store = ApparitionStore(maxBytes = 100)
        store.put(Apparition(1, ImageFormat.ENCODED, ByteArray(60), 1, 1))
        store.put(Apparition(2, ImageFormat.ENCODED, ByteArray(60), 1, 1))
        assertNull("the first should have sunk", store[1L])
        assertNotNull(store[2L])
        assertTrue(store.byteSize <= 100)
    }
}
