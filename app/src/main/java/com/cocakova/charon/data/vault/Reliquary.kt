package com.cocakova.charon.data.vault

import com.cocakova.charon.data.crypto.SecretVault
import com.cocakova.charon.data.db.CharonDb
import com.cocakova.charon.data.db.HostEntity
import com.cocakova.charon.data.db.KnownHostEntity
import com.cocakova.charon.data.db.PortForwardEntity
import com.cocakova.charon.data.db.SnippetEntity
import com.cocakova.charon.data.repository.KeyVault
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The reliquary: the whole fleet in one sealed file — Charon's answer to the sync
 * subscription. Export decrypts every secret out of the Keystore, seals the lot
 * under a passphrase ([ReliquaryCodec]), and hands back bytes for SAF to stow;
 * import is two-phase — [preview] breaks the seal and *plans* (nothing written),
 * [land] applies the plan, re-sealing each secret under this device's Keystore.
 *
 * Merge law: newer-lastModified wins per UUID ([ReliquaryMerge]); known host keys
 * are absent-only — an import never replaces a pinned key.
 */
@OptIn(ExperimentalEncodingApi::class)
class Reliquary(
    private val db: CharonDb,
    private val keyVault: KeyVault,
    private val appVersion: String,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ---- Export --------------------------------------------------------------------

    /** What sailed, and what wouldn't: gated keys whose fingerprint was refused. */
    class ExportReport(
        val bytes: ByteArray,
        val moorings: Int,
        val keys: Int,
        val ledger: Int,
        val snippets: Int,
        val channels: Int,
        val leftBehind: List<String>,
    )

    suspend fun export(passphrase: CharArray): ExportReport {
        val hosts = db.hosts().allOnce()
        val identities = db.identities().allOnce()
        val known = db.knownHosts().allOnce()
        val snippets = db.snippets().allOnce()
        val forwards = db.portForwards().allOnce()

        // Each biometric-gated key costs one fingerprint here; a dismissed prompt
        // (or a broken seal) leaves that key ashore, reported by name — never a
        // silent hole in the backup.
        val leftBehind = mutableListOf<String>()
        val rIdentities = identities.mapNotNull { identity ->
            val material = try {
                keyVault.material(identity)
            } catch (e: IllegalStateException) {
                null
            }
            if (material == null) {
                leftBehind += identity.name
                null
            } else {
                RIdentity(
                    id = identity.id,
                    name = identity.name,
                    keyType = identity.keyType,
                    publicLine = identity.publicLine,
                    fingerprint = identity.fingerprint,
                    privateKey = material.privateKey,
                    passphrase = material.passphrase,
                    biometricGated = identity.biometricGated,
                    createdAt = identity.createdAt,
                    lastModified = identity.lastModified,
                )
            }
        }

        val doc = ReliquaryDoc(
            exportedAt = isoNow(),
            appVersion = appVersion,
            hosts = hosts.map { h ->
                RHost(
                    id = h.id,
                    name = h.name,
                    host = h.host,
                    port = h.port,
                    username = h.username,
                    password = h.passwordSealed
                        ?.let { String(SecretVault.open(it), Charsets.UTF_8) },
                    identityId = h.identityId,
                    harbor = h.harbor,
                    colorHex = h.colorHex,
                    startupCommand = h.startupCommand,
                    autoReconnect = h.autoReconnect,
                    lastConnectedAt = h.lastConnectedAt,
                    createdAt = h.createdAt,
                    lastModified = h.lastModified,
                )
            },
            identities = rIdentities,
            knownHosts = known.map { k ->
                RKnownHost(
                    host = k.host,
                    port = k.port,
                    keyType = k.keyType,
                    publicKeyB64 = Base64.encode(k.publicKey),
                    fingerprint = k.fingerprint,
                    addedAt = k.addedAt,
                )
            },
            snippets = snippets.map { s ->
                RSnippet(s.id, s.name, s.command, s.hostId, s.sortOrder, s.createdAt, s.lastModified)
            },
            portForwards = forwards.map { f ->
                RPortForward(
                    f.id, f.hostId, f.type, f.bindPort, f.targetHost, f.targetPort,
                    f.autoStart, f.createdAt, f.lastModified,
                )
            },
        )

        val bytes = ReliquaryCodec.seal(
            json.encodeToString(doc).toByteArray(Charsets.UTF_8), passphrase,
        )
        return ExportReport(
            bytes = bytes,
            moorings = hosts.size,
            keys = rIdentities.size,
            ledger = known.size,
            snippets = snippets.size,
            channels = forwards.size,
            leftBehind = leftBehind,
        )
    }

    // ---- Import: preview, then land ------------------------------------------------

    /** The plan an import would carry out — counts to show, winners held for [land]. */
    class Preview internal constructor(
        internal val hosts: ReliquaryMerge.Tally<RHost>,
        internal val identities: ReliquaryMerge.Tally<RIdentity>,
        internal val ledger: List<RKnownHost>,
        internal val ledgerKept: Int,
        internal val snippets: ReliquaryMerge.Tally<RSnippet>,
        internal val channels: ReliquaryMerge.Tally<RPortForward>,
        val exportedAt: String,
        val fromVersion: String,
    ) {
        val lines: List<PreviewLine> = listOf(
            PreviewLine("moorings", hosts.fresh, hosts.refreshed, hosts.keptOurs),
            PreviewLine("keys of passage", identities.fresh, identities.refreshed, identities.keptOurs),
            PreviewLine("ferryman's ledger", ledger.size, 0, ledgerKept),
            PreviewLine("snippets", snippets.fresh, snippets.refreshed, snippets.keptOurs),
            PreviewLine("charted channels", channels.fresh, channels.refreshed, channels.keptOurs),
        )
        val anythingToLand: Boolean =
            hosts.land.isNotEmpty() || identities.land.isNotEmpty() || ledger.isNotEmpty() ||
                snippets.land.isNotEmpty() || channels.land.isNotEmpty()
    }

    /** One table's verdict, ready for the sheet to speak plainly. */
    data class PreviewLine(val what: String, val fresh: Int, val refreshed: Int, val keptOurs: Int)

    class LandReport(val landed: Int, val keysAshore: List<String>)

    /** Break the seal and plan the merge. Writes nothing. */
    suspend fun preview(file: ByteArray, passphrase: CharArray): Preview {
        val doc = json.decodeFromString<ReliquaryDoc>(
            String(ReliquaryCodec.open(file, passphrase), Charsets.UTF_8),
        )
        val ourLedger = db.knownHosts().allOnce()
            .mapTo(HashSet()) { Triple(it.host, it.port, it.keyType) }
        val ledgerLand = doc.knownHosts.filter {
            Triple(it.host, it.port, it.keyType) !in ourLedger
        }
        return Preview(
            hosts = ReliquaryMerge.plan(
                db.hosts().allOnce().associate { it.id to it.lastModified },
                doc.hosts, { it.id }, { it.lastModified },
            ),
            identities = ReliquaryMerge.plan(
                db.identities().allOnce().associate { it.id to it.lastModified },
                doc.identities, { it.id }, { it.lastModified },
            ),
            ledger = ledgerLand,
            ledgerKept = doc.knownHosts.size - ledgerLand.size,
            snippets = ReliquaryMerge.plan(
                db.snippets().allOnce().associate { it.id to it.lastModified },
                doc.snippets, { it.id }, { it.lastModified },
            ),
            channels = ReliquaryMerge.plan(
                db.portForwards().allOnce().associate { it.id to it.lastModified },
                doc.portForwards, { it.id }, { it.lastModified },
            ),
            exportedAt = doc.exportedAt,
            fromVersion = doc.appVersion,
        )
    }

    /** Carry the planned records ashore, re-sealing every secret on the way. */
    suspend fun land(preview: Preview): LandReport {
        var landed = 0
        val keysAshore = mutableListOf<String>()

        // Identities first, so a host arriving with its key finds it already moored.
        for (r in preview.identities.land) {
            val ok = keyVault.restore(
                id = r.id, name = r.name, keyType = r.keyType, publicLine = r.publicLine,
                fingerprint = r.fingerprint, privateKey = r.privateKey,
                passphrase = r.passphrase, biometric = r.biometricGated,
                createdAt = r.createdAt, lastModified = r.lastModified,
            )
            if (ok) landed++ else keysAshore += r.name
        }
        val identityIds = db.identities().allOnce().mapTo(HashSet()) { it.id }

        for (r in preview.hosts.land) {
            val existing = db.hosts().byId(r.id)
            db.hosts().upsert(
                HostEntity(
                    id = r.id,
                    name = r.name,
                    host = r.host,
                    port = r.port,
                    username = r.username,
                    passwordSealed = r.password
                        ?.let { SecretVault.seal(it.toByteArray(Charsets.UTF_8)) }
                        ?: existing?.passwordSealed,
                    // A key that stayed ashore must not leave a dangling reference.
                    identityId = r.identityId?.takeIf { it in identityIds },
                    harbor = r.harbor,
                    colorHex = r.colorHex,
                    startupCommand = r.startupCommand,
                    autoReconnect = r.autoReconnect,
                    lastConnectedAt = maxOf(existing?.lastConnectedAt ?: 0L, r.lastConnectedAt),
                    createdAt = if (r.createdAt > 0) r.createdAt else existing?.createdAt
                        ?: System.currentTimeMillis(),
                    lastModified = r.lastModified,
                ),
            )
            landed++
        }

        for (k in preview.ledger) {
            val bytes = try {
                Base64.decode(k.publicKeyB64)
            } catch (e: IllegalArgumentException) {
                continue
            }
            db.knownHosts().upsert(
                KnownHostEntity(
                    host = k.host, port = k.port, keyType = k.keyType,
                    publicKey = bytes, fingerprint = k.fingerprint, addedAt = k.addedAt,
                ),
            )
            landed++
        }

        for (s in preview.snippets.land) {
            db.snippets().upsert(
                SnippetEntity(s.id, s.name, s.command, s.hostId, s.sortOrder, s.createdAt, s.lastModified),
            )
            landed++
        }

        for (f in preview.channels.land) {
            db.portForwards().upsert(
                PortForwardEntity(
                    f.id, f.hostId, f.type, f.bindPort, f.targetHost, f.targetPort,
                    f.autoStart, f.createdAt, f.lastModified,
                ),
            )
            landed++
        }

        return LandReport(landed, keysAshore)
    }

    private fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
}
