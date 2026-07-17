package com.cocakova.charon.data.vault

import kotlinx.serialization.Serializable

/**
 * The reliquary's plaintext payload (docs/VAULT_FORMAT.md): plain JSON inside the
 * sealed file, documented and portable — an honest format, not a lock-in blob.
 * Secrets (host passwords, private keys) appear here in the clear; they exist only
 * inside the AES-GCM envelope and are re-sealed under the importing device's
 * Keystore the moment they land. Every field beyond the identifiers has a default
 * so future exports stay readable by older Charons.
 */
@Serializable
data class ReliquaryDoc(
    val exportedAt: String = "",
    val appVersion: String = "",
    val hosts: List<RHost> = emptyList(),
    val identities: List<RIdentity> = emptyList(),
    val knownHosts: List<RKnownHost> = emptyList(),
    val snippets: List<RSnippet> = emptyList(),
    val portForwards: List<RPortForward> = emptyList(),
)

@Serializable
data class RHost(
    val id: String,
    val name: String = "",
    val host: String,
    val port: Int = 22,
    val username: String = "",
    /** Plaintext inside the sealed payload only; null = no password saved. */
    val password: String? = null,
    val identityId: String? = null,
    val harbor: String = "",
    val colorHex: String? = null,
    val startupCommand: String = "",
    val autoReconnect: Boolean = true,
    val lastConnectedAt: Long = 0,
    val createdAt: Long = 0,
    val lastModified: Long = 0,
)

@Serializable
data class RIdentity(
    val id: String,
    val name: String = "",
    val keyType: String = "",
    val publicLine: String = "",
    val fingerprint: String = "",
    /** The private key text, plaintext inside the sealed payload only. */
    val privateKey: String,
    val passphrase: String? = null,
    val biometricGated: Boolean = false,
    val createdAt: Long = 0,
    val lastModified: Long = 0,
)

@Serializable
data class RKnownHost(
    val host: String,
    val port: Int = 22,
    val keyType: String,
    /** The exact wire blob, base64 — trust compares bytes, not fingerprints. */
    val publicKeyB64: String,
    val fingerprint: String = "",
    val addedAt: Long = 0,
)

@Serializable
data class RSnippet(
    val id: String,
    val name: String = "",
    val command: String,
    val hostId: String? = null,
    val sortOrder: Int = 0,
    val createdAt: Long = 0,
    val lastModified: Long = 0,
)

@Serializable
data class RPortForward(
    val id: String,
    val hostId: String,
    val type: String = "L",
    val bindPort: Int = 0,
    val targetHost: String = "",
    val targetPort: Int = 0,
    val autoStart: Boolean = false,
    val createdAt: Long = 0,
    val lastModified: Long = 0,
)
