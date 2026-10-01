package com.opendevtools.vaultwarden.crypto

import java.security.SecureRandom

/**
 * A Bitwarden symmetric key: 32 bytes of AES key, optionally followed by 32 bytes of HMAC key.
 * Call [clear] as soon as the key is no longer needed.
 */
class SymmetricCryptoKey(val encKey: ByteArray, val macKey: ByteArray?) {

    /** The 32- or 64-byte form the server stores wrapped inside a cipher's `key` field. */
    fun toBytes(): ByteArray = macKey?.let { encKey + it } ?: encKey.copyOf()

    fun clear() {
        encKey.fill(0)
        macKey?.fill(0)
    }

    companion object {
        private val random = SecureRandom()

        fun fromBytes(raw: ByteArray): SymmetricCryptoKey = when (raw.size) {
            32 -> SymmetricCryptoKey(raw.copyOf(), null)
            64 -> SymmetricCryptoKey(raw.copyOfRange(0, 32), raw.copyOfRange(32, 64))
            else -> throw VaultCryptoException("Unexpected symmetric key length: " + raw.size)
        }

        /**
         * A fresh 64-byte item key. Bitwarden gives every cipher its own key so that sharing an
         * item with an organization only needs the item key re-wrapped, not the item re-encrypted.
         */
        fun generate(): SymmetricCryptoKey {
            val raw = ByteArray(64)
            random.nextBytes(raw)
            return fromBytes(raw).also { raw.fill(0) }
        }
    }
}

/** Key derivation parameters as reported by the server's prelogin endpoint. */
data class KdfParams(
    val type: Int,
    val iterations: Int,
    val memoryMib: Int?,
    val parallelism: Int?,
) {
    companion object {
        const val PBKDF2_SHA256 = 0
        const val ARGON2ID = 1
    }
}
