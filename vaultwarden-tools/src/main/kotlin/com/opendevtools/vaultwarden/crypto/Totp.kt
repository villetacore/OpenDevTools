package com.opendevtools.vaultwarden.crypto

import java.net.URI
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Result of generating a one-time password. */
data class TotpCode(val code: String, val secondsRemaining: Int)

/**
 * RFC 6238 TOTP. Accepts a bare base32 secret or a full `otpauth://totp/...` URI,
 * which is what Bitwarden stores in the `totp` field.
 */
object Totp {

    fun generate(secretOrUri: String, timeMillis: Long = System.currentTimeMillis()): TotpCode {
        val raw = secretOrUri.trim()
        var digits = 6
        var period = 30
        var algorithm = "HmacSHA1"
        var secret = raw

        if (raw.startsWith("otpauth://", ignoreCase = true)) {
            val query = runCatching { URI(raw).query }.getOrNull().orEmpty()
            val params = query.split('&')
                .mapNotNull { part ->
                    val index = part.indexOf('=')
                    if (index <= 0) null else part.substring(0, index).lowercase() to part.substring(index + 1)
                }
                .toMap()
            secret = params["secret"] ?: throw VaultCryptoException("The otpauth URI has no secret")
            params["digits"]?.toIntOrNull()?.let { digits = it }
            params["period"]?.toIntOrNull()?.let { period = it }
            params["algorithm"]?.let {
                algorithm = when (it.uppercase()) {
                    "SHA256" -> "HmacSHA256"
                    "SHA512" -> "HmacSHA512"
                    else -> "HmacSHA1"
                }
            }
        }

        val key = base32Decode(secret)
        if (key.isEmpty()) throw VaultCryptoException("The one-time password secret is empty")

        val seconds = timeMillis / 1000
        val counter = seconds / period
        val code = hotp(key, counter, digits, algorithm)
        key.fill(0)
        return TotpCode(code, (period - (seconds % period)).toInt())
    }

    private fun hotp(key: ByteArray, counter: Long, digits: Int, algorithm: String): String {
        val message = ByteArray(8)
        var value = counter
        for (i in 7 downTo 0) {
            message[i] = (value and 0xFF).toByte()
            value = value shr 8
        }
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(key, algorithm))
        val hash = mac.doFinal(message)

        val offset = (hash[hash.size - 1].toInt() and 0x0F)
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)

        var modulus = 1
        repeat(digits) { modulus *= 10 }
        return (binary % modulus).toString().padStart(digits, '0')
    }

    /** RFC 4648 base32, tolerant of lower case, spaces and missing padding. */
    fun base32Decode(value: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val cleaned = value.uppercase().replace(" ", "").trimEnd('=')
        var buffer = 0
        var bitsLeft = 0
        val output = java.io.ByteArrayOutputStream((cleaned.length * 5) / 8 + 1)
        for (character in cleaned) {
            val index = alphabet.indexOf(character)
            if (index < 0) throw VaultCryptoException("Not a valid base32 secret")
            buffer = (buffer shl 5) or index
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                output.write((buffer shr bitsLeft) and 0xFF)
            }
        }
        return output.toByteArray()
    }
}
