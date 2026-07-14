package com.cocakova.charon.data.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets never touch disk in the clear: AES-256-GCM under non-exportable
 * AndroidKeyStore keys. Sealed form is `iv(12) || ciphertext+tag`, stored in Room.
 *
 * Two keys live here:
 *  - [ALIAS]: the plain vault key — passwords, non-gated identities.
 *  - [BIO_ALIAS]: authentication-per-use (biometric-gated identities). Every
 *    operation needs a cipher blessed through BiometricPrompt/CryptoObject, so
 *    the gate is enforced by the hardware keystore, not by app logic. Ciphers
 *    for it are minted here ([bioEncryptCipher]/[bioDecryptCipher]) and used via
 *    [sealWith]/[openWith] after the prompt succeeds.
 */
object SecretVault {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "charon-vault"
    private const val BIO_ALIAS = "charon-vault-bio"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(plain)
        return cipher.iv + ct
    }

    fun open(sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = sealed.copyOfRange(0, IV_LEN)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(sealed, IV_LEN, sealed.size - IV_LEN)
    }

    /**
     * Cipher for sealing under the biometric key. Init succeeds without auth
     * (per-use keys defer the check); doFinal only works through a CryptoObject
     * that BiometricPrompt has authenticated.
     */
    fun bioEncryptCipher(): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, bioKey())
        }

    /** Cipher for unsealing [sealed] under the biometric key; same prompt dance. */
    fun bioDecryptCipher(sealed: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            val iv = sealed.copyOfRange(0, IV_LEN)
            init(Cipher.DECRYPT_MODE, bioKey(), GCMParameterSpec(TAG_BITS, iv))
        }

    /** Finish a seal with an authenticated cipher from [bioEncryptCipher]. */
    fun sealWith(cipher: Cipher, plain: ByteArray): ByteArray =
        cipher.iv + cipher.doFinal(plain)

    /** Finish an unseal with an authenticated cipher from [bioDecryptCipher]. */
    fun openWith(cipher: Cipher, sealed: ByteArray): ByteArray =
        cipher.doFinal(sealed, IV_LEN, sealed.size - IV_LEN)

    private fun key(): SecretKey = keyFor(ALIAS) {
        // no extra constraints on the plain vault key
    }

    private fun bioKey(): SecretKey = keyFor(BIO_ALIAS) {
        setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= 30) {
            setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            setUserAuthenticationValidityDurationSeconds(-1)
        }
        // Deliberate default: new biometric enrollments invalidate the key. A
        // broken seal means re-importing the identity — that's the safe failure.
    }

    private inline fun keyFor(
        alias: String,
        crossinline extras: KeyGenParameterSpec.Builder.() -> Unit,
    ): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .apply(extras)
                .build(),
        )
        return generator.generateKey()
    }
}
