package com.opendevtools.vaultwarden.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

/**
 * The write side of the crypto. Correctness against other implementations is covered by the
 * golden vectors in [BitwardenCryptoTest]; what is checked here is that what this plugin writes,
 * this plugin can read back, in exactly the wire shapes the server stores.
 */
class EncryptRoundTripTest {

    private val key = SymmetricCryptoKey.fromBytes(ByteArray(64) { it.toByte() })

    @Test
    fun `an encrypted string decrypts to the original`() {
        val plain = "correct horse battery staple"
        val enc = BitwardenCrypto.encryptString(plain, key)
        assertEquals(plain, BitwardenCrypto.decryptToString(enc, key))
    }

    @Test
    fun `encryption produces the authenticated type current clients write`() {
        val enc = BitwardenCrypto.encryptString("x", key)
        assertEquals(EncString.AES_CBC256_HMAC_SHA256_B64, enc.type)
        assertTrue(enc.encoded.startsWith("2."))
        // type.iv|ciphertext|mac
        assertEquals(2, enc.encoded.substringAfter('.').count { it == '|' })
    }

    @Test
    fun `the encoded form survives a parse and still decrypts`() {
        val plain = "a value with UTF-8:日本語 and emoji 🔐"
        val encoded = BitwardenCrypto.encryptString(plain, key).encoded
        val reparsed = EncString.parse(encoded)
        assertEquals(plain, BitwardenCrypto.decryptToString(reparsed, key))
    }

    @Test
    fun `a fresh IV makes two encryptions of the same value differ`() {
        val first = BitwardenCrypto.encryptString("same", key).encoded
        val second = BitwardenCrypto.encryptString("same", key).encoded
        assertNotEquals(first, second)
    }

    @Test
    fun `a key without a MAC part is refused rather than downgraded`() {
        val macless = SymmetricCryptoKey(ByteArray(32) { 1 }, null)
        assertThrows(VaultCryptoException::class.java) {
            BitwardenCrypto.encryptString("x", macless)
        }
    }

    @Test
    fun `decryption with the wrong key fails on the MAC, not on the padding`() {
        val enc = BitwardenCrypto.encryptString("secret", key)
        val other = SymmetricCryptoKey.fromBytes(ByteArray(64) { (it + 1).toByte() })
        val failure = assertThrows(VaultCryptoException::class.java) {
            BitwardenCrypto.decrypt(enc, other)
        }
        assertTrue(failure.message!!.contains("MAC"), failure.message)
    }

    @Test
    fun `wrapping a key round-trips through the wire form`() {
        val itemKey = SymmetricCryptoKey.generate()
        val wrapped = BitwardenCrypto.wrapKey(itemKey, key).encoded
        val unwrapped = SymmetricCryptoKey.fromBytes(
            BitwardenCrypto.decrypt(EncString.parse(wrapped), key),
        )
        assertArrayEquals(itemKey.encKey, unwrapped.encKey)
        assertArrayEquals(itemKey.macKey, unwrapped.macKey)
    }

    @Test
    fun `a generated item key has both halves and is not all zeroes`() {
        val generated = SymmetricCryptoKey.generate()
        assertEquals(32, generated.encKey.size)
        assertEquals(32, generated.macKey?.size)
        assertTrue(generated.encKey.any { it != 0.toByte() })
        assertTrue(generated.toBytes().size == 64)
    }

    @Test
    fun `an attachment blob round-trips through the packed binary form`() {
        val content = ByteArray(5000) { (it % 251).toByte() }
        val blob = BitwardenCrypto.encryptRawBlob(content, key)

        // [type][16-byte iv][32-byte mac][ciphertext]
        assertEquals(EncString.AES_CBC256_HMAC_SHA256_B64.toByte(), blob[0])
        assertTrue(blob.size > 1 + 16 + 32 + content.size - 16)

        assertArrayEquals(content, BitwardenCrypto.decryptRawBlob(blob, key))
    }

    @Test
    fun `a truncated attachment blob is reported rather than decrypted`() {
        val blob = BitwardenCrypto.encryptRawBlob("hello".toByteArray(), key)
        assertThrows(VaultCryptoException::class.java) {
            BitwardenCrypto.decryptRawBlob(blob.copyOfRange(0, 20), key)
        }
        assertThrows(VaultCryptoException::class.java) {
            BitwardenCrypto.decryptRawBlob(ByteArray(0), key)
        }
    }

    @Test
    fun `an attachment blob of an unknown type is refused`() {
        val blob = BitwardenCrypto.encryptRawBlob("hello".toByteArray(), key)
        blob[0] = 9
        assertThrows(VaultCryptoException::class.java) {
            BitwardenCrypto.decryptRawBlob(blob, key)
        }
    }

    @Test
    fun `full HKDF differs from a bare expand, which is what Send keys depend on`() {
        val material = "send key material".toByteArray(StandardCharsets.UTF_8)
        val extracted = BitwardenCrypto.hkdf(material, "bitwarden-send", "send", 64)
        val expandedOnly = BitwardenCrypto.hkdfExpand(material, "send", 64)
        assertEquals(64, extracted.size)
        assertNotEquals(
            extracted.joinToString("") { "%02x".format(it) },
            expandedOnly.joinToString("") { "%02x".format(it) },
        )
    }
}
