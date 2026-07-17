package com.cocakova.charon.data.repository

import android.security.keystore.KeyPermanentlyInvalidatedException
import com.cocakova.charon.data.crypto.Ed25519Forge
import com.cocakova.charon.data.crypto.KeyEnvelope
import com.cocakova.charon.data.crypto.SecretVault
import com.cocakova.charon.data.db.IdentityDao
import com.cocakova.charon.data.db.IdentityEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.util.UUID
import javax.crypto.Cipher
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Set while a biometric-gated seal wants a fingerprint; MainActivity hosts the
 * BiometricPrompt and completes it. Same bridge shape as PendingTrust: the vault
 * parks, the UI decides, hardware enforces.
 */
class PendingBio(val title: String, val cipher: Cipher) {
    val result = CompletableDeferred<Cipher?>()

    fun succeed(authenticated: Cipher) {
        result.complete(authenticated)
    }

    fun cancel() {
        result.complete(null)
    }
}

/** The user backed out of a biometric prompt — abort quietly, not as an error. */
class BioCancelled : Exception("biometric cancelled")

/**
 * Keys of passage. Private material lives in Room only as a sealed KeyEnvelope;
 * biometric-gated identities seal under the auth-per-use Keystore key, so both
 * creating and using them costs a fingerprint enforced by hardware.
 */
@OptIn(ExperimentalEncodingApi::class)
class KeyVault(private val dao: IdentityDao) {

    val identities: Flow<List<IdentityEntity>> = dao.all()
    val pendingBio = MutableStateFlow<PendingBio?>(null)

    suspend fun byId(id: String): IdentityEntity? = dao.byId(id)

    /** Birth an ed25519 key on this phone. */
    suspend fun forge(name: String, biometric: Boolean): IdentityEntity {
        val comment = Ed25519Forge.sanitizeComment(name)
        val forged = Ed25519Forge.forge(comment)
        return persist(
            name = name.trim().ifBlank { comment },
            keyType = forged.keyType,
            publicLine = forged.publicLine,
            fingerprint = forged.fingerprint,
            envelope = KeyEnvelope.pack(forged.privatePem, null),
            biometric = biometric,
        )
    }

    /**
     * Bring your own key. Validated through sshj — the same parser that will
     * consume it at connect time. Encrypted openssh-key-v1 keys keep their
     * passphrase, sealed alongside the key text; classic encrypted PEM needs
     * converting first (we deliberately don't ship bcpkix for it).
     */
    suspend fun import(
        name: String,
        keyText: String,
        passphrase: String?,
        biometric: Boolean,
    ): IdentityEntity {
        val text = keyText.trim() + "\n"
        val pass = passphrase?.takeIf { it.isNotEmpty() }
        val provider = try {
            val finder = pass?.let { PasswordUtils.createOneOff(it.toCharArray()) }
            SSHClient(DefaultConfig()).loadKeys(text, null, finder)
        } catch (t: Throwable) {
            throw IllegalArgumentException(unreadableKeyMessage(t), t)
        }
        val public = try {
            provider.private // forces decryption — validates the passphrase now, not at connect
            provider.public
        } catch (t: Throwable) {
            throw IllegalArgumentException(unreadableKeyMessage(t), t)
        }

        val blob = Buffer.PlainBuffer().putPublicKey(public).compactData
        val keyType = KeyType.fromKey(public).toString()
        val comment = Ed25519Forge.sanitizeComment(name)
        return persist(
            name = name.trim().ifBlank { comment },
            keyType = keyType,
            publicLine = "$keyType ${Base64.encode(blob)} $comment",
            fingerprint = Ed25519Forge.fingerprint(blob),
            envelope = KeyEnvelope.pack(text, pass),
            biometric = biometric,
        )
    }

    suspend fun delete(id: String) {
        dao.unbindHosts(id)
        dao.delete(id)
    }

    /**
     * Land an identity from a reliquary import, keeping its UUID and stamps so the
     * merge law stays honest. The material is re-sealed under *this* device's
     * Keystore — through the biometric gate when the identity carries one, which
     * costs a fingerprint right here. Returns false when the prompt was refused or
     * the gate can't exist (no biometric enrolled): the key stays ashore, reported,
     * never silently downgraded to an ungated seal.
     */
    suspend fun restore(
        id: String,
        name: String,
        keyType: String,
        publicLine: String,
        fingerprint: String,
        privateKey: String,
        passphrase: String?,
        biometric: Boolean,
        createdAt: Long,
        lastModified: Long,
    ): Boolean {
        val envelope = KeyEnvelope.pack(privateKey, passphrase)
        val sealed = if (!biometric) {
            SecretVault.seal(envelope)
        } else {
            val cipher = try {
                SecretVault.bioEncryptCipher()
            } catch (e: Exception) {
                return false
            }
            val authed = awaitBio("seal “${name}”", cipher) ?: return false
            SecretVault.sealWith(authed, envelope)
        }
        dao.upsert(
            IdentityEntity(
                id = id,
                name = name,
                keyType = keyType,
                publicLine = publicLine,
                fingerprint = fingerprint,
                materialSealed = sealed,
                biometricGated = biometric,
                createdAt = if (createdAt > 0) createdAt else System.currentTimeMillis(),
                lastModified = lastModified,
            ),
        )
        return true
    }

    /**
     * Unseal an identity's private material. Blocks on a fingerprint for gated
     * keys; null means the user dismissed the prompt (abort, no error).
     */
    suspend fun material(identity: IdentityEntity): KeyEnvelope.Material? {
        val plain = if (!identity.biometricGated) {
            SecretVault.open(identity.materialSealed)
        } else {
            val cipher = try {
                SecretVault.bioDecryptCipher(identity.materialSealed)
            } catch (e: KeyPermanentlyInvalidatedException) {
                throw IllegalStateException(SEAL_BROKEN, e)
            }
            val authed = awaitBio("unseal “${identity.name}”", cipher) ?: return null
            SecretVault.openWith(authed, identity.materialSealed)
        }
        return KeyEnvelope.unpack(plain)
    }

    private suspend fun persist(
        name: String,
        keyType: String,
        publicLine: String,
        fingerprint: String,
        envelope: ByteArray,
        biometric: Boolean,
    ): IdentityEntity {
        val sealed = if (!biometric) {
            SecretVault.seal(envelope)
        } else {
            val cipher = try {
                SecretVault.bioEncryptCipher()
            } catch (e: Exception) {
                // Typically: no biometric enrolled, so the gated key can't exist.
                throw IllegalStateException(
                    "no biometric enrolled — enroll a fingerprint or save without the gate", e,
                )
            }
            val authed = awaitBio("seal “${name}”", cipher) ?: throw BioCancelled()
            SecretVault.sealWith(authed, envelope)
        }
        val now = System.currentTimeMillis()
        val entity = IdentityEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            keyType = keyType,
            publicLine = publicLine,
            fingerprint = fingerprint,
            materialSealed = sealed,
            biometricGated = biometric,
            createdAt = now,
            lastModified = now,
        )
        dao.upsert(entity)
        return entity
    }

    private suspend fun awaitBio(title: String, cipher: Cipher): Cipher? {
        val pending = PendingBio(title, cipher)
        pendingBio.value = pending
        return try {
            pending.result.await()
        } finally {
            pendingBio.value = null
        }
    }

    private fun unreadableKeyMessage(t: Throwable): String = when {
        t is NoClassDefFoundError || t.cause is NoClassDefFoundError ->
            "this looks like a legacy encrypted PEM — convert it first: ssh-keygen -p -f key -o"
        else -> "couldn't read that key" +
            (t.message?.let { ": $it" } ?: "") +
            " — is the passphrase right?"
    }

    companion object {
        const val SEAL_BROKEN =
            "the seal is broken — biometrics changed on this phone; release this key and import it again"
    }
}
