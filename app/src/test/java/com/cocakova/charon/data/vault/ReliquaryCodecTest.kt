package com.cocakova.charon.data.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The seal itself: round-trips, and every way a file can lie — wrong passphrase,
 * flipped ciphertext, disturbed header (the AAD), missing magic, a future era.
 * Small KDF parameters keep the suite fast; they ride in the header, so open()
 * honors them without knowing the test wrote them.
 */
class ReliquaryCodecTest {

    private val plain = """{"hosts":[{"id":"a"}]}""".toByteArray()
    private val pass = "obol-for-the-ferryman".toCharArray()

    private fun sealSmall(plain: ByteArray = this.plain, pass: CharArray = this.pass) =
        ReliquaryCodec.seal(plain, pass, memKiB = 1024, iterations = 1, parallelism = 1)

    @Test
    fun `seal and open round-trip`() {
        assertArrayEquals(plain, ReliquaryCodec.open(sealSmall(), pass))
    }

    @Test
    fun `wrong passphrase holds the seal`() {
        val file = sealSmall()
        assertThrows(ReliquaryCodec.ReliquaryException.SealHolds::class.java) {
            ReliquaryCodec.open(file, "not-the-passphrase".toCharArray())
        }
    }

    @Test
    fun `a flipped ciphertext byte holds the seal`() {
        val file = sealSmall()
        file[file.size - 5] = (file[file.size - 5].toInt() xor 0x40).toByte()
        assertThrows(ReliquaryCodec.ReliquaryException.SealHolds::class.java) {
            ReliquaryCodec.open(file, pass)
        }
    }

    @Test
    fun `a disturbed header salt byte holds the seal`() {
        val file = sealSmall()
        // Header offset 17 = first salt byte; the header is AAD, so this must fail
        // even though the ciphertext itself is untouched.
        file[17] = (file[17].toInt() xor 0x01).toByte()
        assertThrows(ReliquaryCodec.ReliquaryException.SealHolds::class.java) {
            ReliquaryCodec.open(file, pass)
        }
    }

    @Test
    fun `garbage is no reliquary`() {
        assertThrows(ReliquaryCodec.ReliquaryException.NotAReliquary::class.java) {
            ReliquaryCodec.open("PLAINLY NOT A CHARON FILE, BUT LONG ENOUGH TO PARSE".toByteArray(), pass)
        }
    }

    @Test
    fun `a truncated file is no reliquary`() {
        assertThrows(ReliquaryCodec.ReliquaryException.NotAReliquary::class.java) {
            ReliquaryCodec.open(sealSmall().copyOf(20), pass)
        }
    }

    @Test
    fun `a future era is refused loudly`() {
        val file = sealSmall()
        file[7] = 2 // the version byte
        assertThrows(ReliquaryCodec.ReliquaryException.UnknownEra::class.java) {
            ReliquaryCodec.open(file, pass)
        }
    }

    @Test
    fun `unreasonable KDF parameters are refused before allocating`() {
        val file = sealSmall()
        // Header offsets 8..11 = memKiB (int BE). Write an absurd 1 GiB ask.
        file[8] = 0; file[9] = 0x10; file[10] = 0; file[11] = 0
        assertThrows(ReliquaryCodec.ReliquaryException.UnreasonableSeal::class.java) {
            ReliquaryCodec.open(file, pass)
        }
    }

    @Test
    fun `empty payload round-trips`() {
        assertArrayEquals(ByteArray(0), ReliquaryCodec.open(sealSmall(ByteArray(0)), pass))
    }
}
