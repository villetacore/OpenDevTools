package com.opendevtools.vaultwarden.crypto

import java.util.Base64

/**
 * A Bitwarden "EncString": `<type>.<iv>|<ciphertext>|<mac>` for symmetric types and
 * `<type>.<ciphertext>` for the RSA ones. Strings without a type prefix are legacy type 0.
 *
 * The class is also the write side: [encoded] renders the exact wire format the server expects,
 * which is what makes creating and editing items possible.
 */
class EncString internal constructor(
    val type: Int,
    val iv: ByteArray,
    val cipherText: ByteArray,
    val mac: ByteArray?,
) {

    /** The wire format. Every value written back to the server goes through this. */
    val encoded: String
        get() = when {
            isRsa(type) -> "$type." + encode(cipherText)
            mac != null -> "$type." + encode(iv) + "|" + encode(cipherText) + "|" + encode(mac)
            else -> "$type." + encode(iv) + "|" + encode(cipherText)
        }

    override fun toString(): String = encoded

    companion object {
        const val AES_CBC256_B64 = 0
        const val AES_CBC128_HMAC_SHA256_B64 = 1
        const val AES_CBC256_HMAC_SHA256_B64 = 2
        const val RSA2048_OAEP_SHA256_B64 = 3
        const val RSA2048_OAEP_SHA1_B64 = 4

        private val decoder: Base64.Decoder = Base64.getDecoder()
        private val encoder: Base64.Encoder = Base64.getEncoder()

        fun parse(value: String): EncString {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) throw VaultCryptoException("Empty encrypted string")

            val dot = trimmed.indexOf('.')
            val explicitType = if (dot > 0) trimmed.substring(0, dot).toIntOrNull() else null
            val type = explicitType ?: AES_CBC256_B64
            val payload = if (explicitType != null) trimmed.substring(dot + 1) else trimmed
            val parts = payload.split('|')

            return when (type) {
                AES_CBC256_B64 -> {
                    require(parts.size == 2, type, parts.size)
                    EncString(type, decode(parts[0]), decode(parts[1]), null)
                }

                AES_CBC128_HMAC_SHA256_B64, AES_CBC256_HMAC_SHA256_B64 -> {
                    require(parts.size == 3, type, parts.size)
                    EncString(type, decode(parts[0]), decode(parts[1]), decode(parts[2]))
                }

                RSA2048_OAEP_SHA256_B64, RSA2048_OAEP_SHA1_B64 -> {
                    require(parts.isNotEmpty(), type, parts.size)
                    EncString(type, ByteArray(0), decode(parts[0]), null)
                }

                else -> throw VaultCryptoException("Unsupported encrypted string type " + type)
            }
        }

        fun isRsa(type: Int): Boolean =
            type == RSA2048_OAEP_SHA256_B64 || type == RSA2048_OAEP_SHA1_B64

        private fun require(condition: Boolean, type: Int, actual: Int) {
            if (!condition) {
                throw VaultCryptoException(
                    "Malformed encrypted string: type " + type + " with " + actual + " parts",
                )
            }
        }

        private fun decode(value: String): ByteArray = try {
            decoder.decode(value)
        } catch (e: IllegalArgumentException) {
            throw VaultCryptoException("Encrypted string is not valid base64", e)
        }

        private fun encode(value: ByteArray): String = encoder.encodeToString(value)
    }
}
