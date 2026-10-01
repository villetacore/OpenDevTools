package com.opendevtools.vaultwarden.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The Bitwarden key hierarchy, read side only.
 *
 * master password --KDF--> master key --HKDF--> stretched key --unwraps--> user key
 * user key --unwraps--> RSA private key --unwraps--> organization keys
 * user/org key --unwraps--> optional per-item key --decrypts--> the item fields
 */
object BitwardenCrypto {

    private const val AES_TRANSFORMATION = "AES/CBC/PKCS5Padding"
    private const val HMAC_SHA256 = "HmacSHA256"
    private const val AES_BLOCK_BYTES = 16
    private const val MAC_BYTES = 32

    private val random = java.security.SecureRandom()

    /** Derives the 32-byte master key from the master password and the account email. */
    fun deriveMasterKey(password: CharArray, email: String, kdf: KdfParams): ByteArray {
        val salt = email.trim().lowercase()
        val saltBytes = salt.toByteArray(StandardCharsets.UTF_8)
        return when (kdf.type) {
            KdfParams.PBKDF2_SHA256 -> pbkdf2(password, saltBytes, kdf.iterations)

            KdfParams.ARGON2ID -> argon2id(
                password = password,
                salt = MessageDigest.getInstance("SHA-256").digest(saltBytes),
                iterations = kdf.iterations,
                memoryMib = kdf.memoryMib ?: 64,
                parallelism = kdf.parallelism ?: 4,
            )

            else -> throw VaultCryptoException("Unsupported KDF type " + kdf.type)
        }
    }

    private fun pbkdf2(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun argon2id(
        password: CharArray,
        salt: ByteArray,
        iterations: Int,
        memoryMib: Int,
        parallelism: Int,
    ): ByteArray {
        val passwordBytes = toUtf8(password)
        try {
            val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withIterations(iterations)
                .withMemoryAsKB(memoryMib * 1024)
                .withParallelism(parallelism)
                .build()
            val generator = Argon2BytesGenerator()
            generator.init(parameters)
            val output = ByteArray(32)
            generator.generateBytes(passwordBytes, output)
            return output
        } finally {
            passwordBytes.fill(0)
        }
    }

    /** HKDF-Expand (RFC 5869) with the master key used directly as the pseudo-random key. */
    fun stretchMasterKey(masterKey: ByteArray): SymmetricCryptoKey = SymmetricCryptoKey(
        encKey = hkdfExpand(masterKey, "enc", 32),
        macKey = hkdfExpand(masterKey, "mac", 32),
    )

    /**
     * Full HKDF (RFC 5869): extract with [salt], then expand with [info]. Send keys are derived
     * this way, unlike the stretched master key, which skips the extract step.
     */
    fun hkdf(ikm: ByteArray, salt: String, info: String, length: Int): ByteArray {
        val extractMac = Mac.getInstance(HMAC_SHA256)
        extractMac.init(SecretKeySpec(salt.toByteArray(StandardCharsets.UTF_8), HMAC_SHA256))
        val prk = extractMac.doFinal(ikm)
        try {
            return hkdfExpand(prk, info, length)
        } finally {
            prk.fill(0)
        }
    }

    fun hkdfExpand(prk: ByteArray, info: String, length: Int): ByteArray {
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(prk, HMAC_SHA256))
        val infoBytes = info.toByteArray(StandardCharsets.UTF_8)
        val output = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.reset()
            mac.update(previous)
            mac.update(infoBytes)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val chunk = minOf(previous.size, length - offset)
            System.arraycopy(previous, 0, output, offset, chunk)
            offset += chunk
            counter++
        }
        return output
    }

    /** Decrypts a symmetric EncString, verifying the HMAC when the type carries one. */
    fun decrypt(enc: EncString, key: SymmetricCryptoKey): ByteArray {
        if (EncString.isRsa(enc.type)) {
            throw VaultCryptoException("RSA encrypted string passed to symmetric decryption")
        }
        if (enc.mac != null) {
            val macKey = key.macKey
                ?: throw VaultCryptoException("Encrypted string carries a MAC but the key has none")
            val mac = Mac.getInstance(HMAC_SHA256)
            mac.init(SecretKeySpec(macKey, HMAC_SHA256))
            mac.update(enc.iv)
            mac.update(enc.cipherText)
            if (!MessageDigest.isEqual(mac.doFinal(), enc.mac)) {
                throw VaultCryptoException("MAC mismatch - wrong key or tampered data")
            }
        }
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.encKey, "AES"), IvParameterSpec(enc.iv))
        return try {
            cipher.doFinal(enc.cipherText)
        } catch (e: Exception) {
            throw VaultCryptoException("Could not decrypt the value", e)
        }
    }

    fun decryptToString(enc: EncString, key: SymmetricCryptoKey): String =
        String(decrypt(enc, key), StandardCharsets.UTF_8)

    /**
     * Encrypts with AES-256-CBC and authenticates with HMAC-SHA256 over `iv || ciphertext`,
     * producing an EncString of type 2 - the only symmetric type current Bitwarden clients write.
     *
     * A key without a MAC part would force the unauthenticated legacy type 0; rather than write a
     * weaker record than the rest of the vault, that is refused.
     */
    fun encrypt(plainText: ByteArray, key: SymmetricCryptoKey): EncString {
        val macKey = key.macKey
            ?: throw VaultCryptoException("Cannot encrypt with a key that has no MAC part")

        val iv = ByteArray(AES_BLOCK_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.encKey, "AES"), IvParameterSpec(iv))
        val cipherText = try {
            cipher.doFinal(plainText)
        } catch (e: Exception) {
            throw VaultCryptoException("Could not encrypt the value", e)
        }

        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(macKey, HMAC_SHA256))
        mac.update(iv)
        mac.update(cipherText)

        return EncString(EncString.AES_CBC256_HMAC_SHA256_B64, iv, cipherText, mac.doFinal())
    }

    fun encryptString(value: String, key: SymmetricCryptoKey): EncString =
        encrypt(value.toByteArray(StandardCharsets.UTF_8), key)

    /**
     * Attachments and Send files are not stored as text EncStrings but as a packed buffer:
     * `[type byte][16-byte iv][32-byte mac][ciphertext]` for the authenticated types, and
     * `[type byte][16-byte iv][ciphertext]` for the legacy unauthenticated one.
     */
    fun decryptRawBlob(blob: ByteArray, key: SymmetricCryptoKey): ByteArray {
        if (blob.isEmpty()) throw VaultCryptoException("The attachment payload is empty")
        val type = blob[0].toInt()
        val enc = when (type) {
            EncString.AES_CBC128_HMAC_SHA256_B64, EncString.AES_CBC256_HMAC_SHA256_B64 -> {
                if (blob.size < 1 + AES_BLOCK_BYTES + MAC_BYTES) {
                    throw VaultCryptoException("The attachment payload is truncated")
                }
                EncString(
                    type,
                    blob.copyOfRange(1, 1 + AES_BLOCK_BYTES),
                    blob.copyOfRange(1 + AES_BLOCK_BYTES + MAC_BYTES, blob.size),
                    blob.copyOfRange(1 + AES_BLOCK_BYTES, 1 + AES_BLOCK_BYTES + MAC_BYTES),
                )
            }

            EncString.AES_CBC256_B64 -> {
                if (blob.size < 1 + AES_BLOCK_BYTES) {
                    throw VaultCryptoException("The attachment payload is truncated")
                }
                EncString(
                    type,
                    blob.copyOfRange(1, 1 + AES_BLOCK_BYTES),
                    blob.copyOfRange(1 + AES_BLOCK_BYTES, blob.size),
                    null,
                )
            }

            else -> throw VaultCryptoException("Unsupported attachment encryption type $type")
        }
        return decrypt(enc, key)
    }

    fun encryptRawBlob(content: ByteArray, key: SymmetricCryptoKey): ByteArray {
        val enc = encrypt(content, key)
        val mac = enc.mac ?: throw VaultCryptoException("Encryption produced no MAC")
        return ByteArray(1) { enc.type.toByte() } + enc.iv + mac + enc.cipherText
    }

    /** Wraps one symmetric key with another, which is how a per-item key is stored. */
    fun wrapKey(subject: SymmetricCryptoKey, wrappingKey: SymmetricCryptoKey): EncString {
        val raw = subject.toBytes()
        try {
            return encrypt(raw, wrappingKey)
        } finally {
            raw.fill(0)
        }
    }

    fun decryptRsa(enc: EncString, privateKey: PrivateKey): ByteArray {
        val transformation = when (enc.type) {
            EncString.RSA2048_OAEP_SHA1_B64 -> "RSA/ECB/OAEPWithSHA-1AndMGF1Padding"
            EncString.RSA2048_OAEP_SHA256_B64 -> "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
            else -> throw VaultCryptoException("Not an RSA encrypted string: type " + enc.type)
        }
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        return try {
            cipher.doFinal(enc.cipherText)
        } catch (e: Exception) {
            throw VaultCryptoException("Could not decrypt the organization key", e)
        }
    }

    fun parsePrivateKey(der: ByteArray): PrivateKey = try {
        KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
    } catch (e: Exception) {
        throw VaultCryptoException("Could not read the account private key", e)
    }

    private fun toUtf8(password: CharArray): ByteArray {
        val buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password))
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }
}
