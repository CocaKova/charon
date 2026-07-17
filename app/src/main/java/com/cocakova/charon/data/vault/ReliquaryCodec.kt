package com.cocakova.charon.data.vault

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The `.charon` reliquary format (docs/VAULT_FORMAT.md): one passphrase-sealed file
 * carrying the whole fleet. Argon2id stretches the passphrase, AES-256-GCM seals the
 * payload, and the 45-byte header rides as GCM AAD so its KDF parameters are
 * tamper-evident — a disturbed header breaks the seal exactly like a wrong passphrase.
 *
 * Pure JVM: fully unit-tested off-device. The header layout is fixed by the doc:
 * magic(7) version(1) memKiB(4) iterations(4) parallelism(1) salt(16) nonce(12).
 */
object ReliquaryCodec {

    sealed class ReliquaryException(message: String) : Exception(message) {
        /** Not our file at all — the mark is missing or the file is truncated. */
        class NotAReliquary :
            ReliquaryException("that is no reliquary — the ferryman's mark is missing")

        /** A future format version; refuse loudly rather than misread. */
        class UnknownEra(version: Int) :
            ReliquaryException("this reliquary is sealed in a later era (format $version) — update Charon to open it")

        /** Wrong passphrase — or any tampering, which GCM makes indistinguishable. */
        class SealHolds :
            ReliquaryException("the seal holds — wrong passphrase, or the reliquary has been disturbed")

        /** KDF parameters no honest export writes; opening would invite an OOM. */
        class UnreasonableSeal :
            ReliquaryException("this reliquary asks for an unreasonable seal — it was not made by Charon")
    }

    private val MAGIC = "CHARON1".toByteArray(Charsets.US_ASCII)
    private const val VERSION = 1
    private const val HEADER_LEN = 7 + 1 + 4 + 4 + 1 + 16 + 12
    private const val SALT_LEN = 16
    private const val NONCE_LEN = 12
    private const val KEY_LEN = 32
    private const val TAG_BITS = 128

    // Defaults per the format doc; imports honor whatever the header declares
    // (within reason — see MAX_MEM_KIB / MAX_ITERATIONS).
    const val DEFAULT_MEM_KIB = 32_768
    const val DEFAULT_ITERATIONS = 3
    const val DEFAULT_PARALLELISM = 2

    private const val MAX_MEM_KIB = 262_144   // 256 MiB — beyond this it's an attack
    private const val MAX_ITERATIONS = 64

    fun seal(
        plain: ByteArray,
        passphrase: CharArray,
        memKiB: Int = DEFAULT_MEM_KIB,
        iterations: Int = DEFAULT_ITERATIONS,
        parallelism: Int = DEFAULT_PARALLELISM,
    ): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_LEN).also(random::nextBytes)
        val nonce = ByteArray(NONCE_LEN).also(random::nextBytes)

        val header = ByteBuffer.allocate(HEADER_LEN)
            .put(MAGIC)
            .put(VERSION.toByte())
            .putInt(memKiB)
            .putInt(iterations)
            .put(parallelism.toByte())
            .put(salt)
            .put(nonce)
            .array()

        val key = stretch(passphrase, salt, memKiB, iterations, parallelism)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, nonce),
        )
        cipher.updateAAD(header)
        val sealed = cipher.doFinal(plain)
        key.fill(0)
        return header + sealed
    }

    fun open(file: ByteArray, passphrase: CharArray): ByteArray {
        if (file.size <= HEADER_LEN) throw ReliquaryException.NotAReliquary()
        val buf = ByteBuffer.wrap(file, 0, HEADER_LEN)
        val magic = ByteArray(MAGIC.size).also(buf::get)
        if (!magic.contentEquals(MAGIC)) throw ReliquaryException.NotAReliquary()
        val version = buf.get().toInt()
        if (version != VERSION) throw ReliquaryException.UnknownEra(version)
        val memKiB = buf.int
        val iterations = buf.int
        val parallelism = buf.get().toInt()
        if (memKiB !in 1..MAX_MEM_KIB || iterations !in 1..MAX_ITERATIONS || parallelism !in 1..8) {
            throw ReliquaryException.UnreasonableSeal()
        }
        val salt = ByteArray(SALT_LEN).also(buf::get)
        val nonce = ByteArray(NONCE_LEN).also(buf::get)

        val key = stretch(passphrase, salt, memKiB, iterations, parallelism)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, nonce),
        )
        cipher.updateAAD(file, 0, HEADER_LEN)
        return try {
            cipher.doFinal(file, HEADER_LEN, file.size - HEADER_LEN)
        } catch (e: AEADBadTagException) {
            throw ReliquaryException.SealHolds()
        } finally {
            key.fill(0)
        }
    }

    private fun stretch(
        passphrase: CharArray,
        salt: ByteArray,
        memKiB: Int,
        iterations: Int,
        parallelism: Int,
    ): ByteArray {
        val generator = Argon2BytesGenerator()
        generator.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withSalt(salt)
                .withMemoryAsKB(memKiB)
                .withIterations(iterations)
                .withParallelism(parallelism)
                .build(),
        )
        val key = ByteArray(KEY_LEN)
        generator.generateBytes(passphrase, key)
        return key
    }
}
