package com.cocakova.charon.data.crypto

/**
 * Private key text + optional passphrase packed into one byte blob, so a
 * biometric-gated identity costs exactly one CryptoObject operation (one
 * fingerprint) to seal or unseal. Layout:
 * `[u8 version=1][u8 hasPassphrase][u32 passLen][passphrase utf8][key text utf8]`.
 */
object KeyEnvelope {

    class Material(val privateKey: String, val passphrase: String?)

    fun pack(privateKey: String, passphrase: String?): ByteArray {
        val pass = passphrase?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val key = privateKey.toByteArray(Charsets.UTF_8)
        val out = ByteArray(2 + 4 + pass.size + key.size)
        out[0] = 1
        out[1] = if (passphrase != null) 1 else 0
        out[2] = (pass.size ushr 24).toByte()
        out[3] = (pass.size ushr 16).toByte()
        out[4] = (pass.size ushr 8).toByte()
        out[5] = pass.size.toByte()
        pass.copyInto(out, 6)
        key.copyInto(out, 6 + pass.size)
        return out
    }

    fun unpack(blob: ByteArray): Material {
        require(blob.size >= 6 && blob[0].toInt() == 1) { "unknown key envelope" }
        val hasPass = blob[1].toInt() == 1
        val passLen = ((blob[2].toInt() and 0xFF) shl 24) or
            ((blob[3].toInt() and 0xFF) shl 16) or
            ((blob[4].toInt() and 0xFF) shl 8) or
            (blob[5].toInt() and 0xFF)
        val pass = String(blob, 6, passLen, Charsets.UTF_8)
        val key = String(blob, 6 + passLen, blob.size - 6 - passLen, Charsets.UTF_8)
        return Material(key, if (hasPass) pass else null)
    }
}
