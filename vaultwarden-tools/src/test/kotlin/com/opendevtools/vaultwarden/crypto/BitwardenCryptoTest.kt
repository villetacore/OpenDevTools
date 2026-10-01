package com.opendevtools.vaultwarden.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Golden vectors produced by independent implementations (Python hashlib / the `cryptography`
 * package, itself backed by OpenSSL). The TOTP vectors are the published RFC 6238 ones.
 *
 * Do not "fix" a failing expectation here by copying what our code returns - the point of these
 * values is that they were computed by something other than this plugin.
 */
class BitwardenCryptoTest {

    private val email = "user@example.com"
    private val password = "correct horse battery staple".toCharArray()

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun `master key from PBKDF2 matches the reference implementation`() {
        val kdf = KdfParams(KdfParams.PBKDF2_SHA256, 100000, null, null)
        assertArrayEquals(hex("a69d536a06ad85726e4173a146710f671fcc245c23226099326470a8eda63eb0"), BitwardenCrypto.deriveMasterKey(password, email, kdf))
    }

    @Test
    fun `master key from Argon2id matches the reference implementation`() {
        val kdf = KdfParams(KdfParams.ARGON2ID, 3, 64, 4)
        assertArrayEquals(hex("26034d0eaf9c2631caec3e6cc7f47603bee30bf3d86f8d79968d05fa3245f702"), BitwardenCrypto.deriveMasterKey(password, email, kdf))
    }

    @Test
    fun `email salt is normalised the way the server expects`() {
        val kdf = KdfParams(KdfParams.PBKDF2_SHA256, 100000, null, null)
        assertArrayEquals(
            hex("a69d536a06ad85726e4173a146710f671fcc245c23226099326470a8eda63eb0"),
            BitwardenCrypto.deriveMasterKey(password, "  USER@Example.COM  ", kdf),
        )
    }

    @Test
    fun `HKDF expand produces the stretched encryption and MAC keys`() {
        val stretched = BitwardenCrypto.stretchMasterKey(hex("a69d536a06ad85726e4173a146710f671fcc245c23226099326470a8eda63eb0"))
        assertArrayEquals(hex("c10ad79ad020c7d5b1d20265fe6d29d6b12f0af254ad805500802f1fecb8553f"), stretched.encKey)
        assertArrayEquals(hex("7455d0cc8fdb1162fb4603d93637e9b56a901fcf6ecd0b89149b76f446849e9d"), stretched.macKey)
    }

    @Test
    fun `the account key unwraps with the stretched master key`() {
        val kdf = KdfParams(KdfParams.PBKDF2_SHA256, 100000, null, null)
        val masterKey = BitwardenCrypto.deriveMasterKey(password, email, kdf)
        val stretched = BitwardenCrypto.stretchMasterKey(masterKey)
        val userKey = BitwardenCrypto.decrypt(EncString.parse("2.AAECAwQFBgcICQoLDA0ODw==|27CBup5QcUFjegonftQ89TIFO8oJvXsnRmjA3pNONggAr5ugKZAiCouYdS7iVTPxXdBwXQAvwL/r8BXzRtweKVquH1k+yNasCqbLuwP0kHc=|5L00Ms7NdlCNSmyHF/7YiNzGUrgKUczaxJbJu6n+PS8="), stretched)
        assertArrayEquals(hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"), userKey)
    }

    @Test
    fun `an item field decrypts with the account key`() {
        val userKey = SymmetricCryptoKey.fromBytes(hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"))
        assertEquals(
            "s3cr3t-p@ssw0rd ✓ пароль",
            BitwardenCrypto.decryptToString(EncString.parse("2.Dw4NDAsKCQgHBgUEAwIBAA==|77P+4BfA25GzYNP+JlBr4Xe62F/3ktb+5C8KrVcUUeYnZu1/hOP2vOJ6BQomvjmx|OAhu2hC45UjsWT1jjiXH4XosqQYKTxgZ26xdfqskvD0="), userKey),
        )
    }

    @Test
    fun `a tampered MAC is rejected`() {
        val userKey = SymmetricCryptoKey.fromBytes(hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"))
        assertThrows(VaultCryptoException::class.java) {
            BitwardenCrypto.decrypt(EncString.parse("2.Dw4NDAsKCQgHBgUEAwIBAA==|77P+4BfA25GzYNP+JlBr4Xe62F/3ktb+5C8KrVcUUeYnZu1/hOP2vOJ6BQomvjmx|AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="), userKey)
        }
    }

    @Test
    fun `an organization key unwraps with the RSA private key`() {
        val der = java.util.Base64.getDecoder().decode("MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDKj75eLSXkOMdnn4GChEMkwmwDYvI3VSF/G/JdILoIOifvxcw7EDgCVSeNO0eD6Q22BWfb8zc7JJlVcGxqVl7MB4NfMqaDmDj6m7sPPLJ2c1QUcGSJwD8OMZHTsQZHXruHptcWo26kqw+uSB9ZUSN1npfeWI6VPIxwaiwFWFn0/O8Gw6EF5iCAp4cxQpmwSjnv2GlXmmhj0hTr0jeHEZ1shGap0/1/18wJKDgrm7v0w9mCarmXbIVZZuwRMq1eyS213zetRQUGfgfFAVbLqaI7fJH6gSaDenQkfn8FR4HfiXW6wGXv9efPYlldzERRr5F7tPbtZhzqQLOq5h7Ga2drAgMBAAECggEAFUe2oyQxF+HTKRK7w19rwl/egxiRA7kg1L613ozCnnyll237P7U7fRs/FXpF0BjaxOWafKP3iHIW3b/6080RFnQvm/+xOJ/0VHep1jb1NLZ2/dK07TfSKAaTIF83nqXYI68mZ41x4UDLW+7dxD9e70ti/wvkps7+ngy5ZTJb+P2tYTCN/Yl5t3w3Gtq5nil03qMZhTR35s8cr7vJparCU35DlUszFX4q4T6TvEIzPVYemGa7tEeuplIHkjbJi/cgvfhdCo492m8zhD7rld72E1LiWa1ljGTaOYQm13broskX9Wo/fHR8KZQk74uzeCxOvtvF5Eze8neIQu9HAkzisQKBgQD/bq+YCvipjr8N95yiqk0Aua9+2YZTVHCTSduJXP6+5nhXIU2E1XejDoqkmq3RU/ixr63axOIG0HcEtyh2ZZc7e3JL7qSpurRT8FppK7cdd4sZF8S9nHm1C9CdYEgO7vGFbdbgLK0vJXqA13Foj0DafI4gt6CeDyuTDZTkdN8OeQKBgQDLAvrRnMPGG4SBcJwrHrZtX/Qvh1ZWTf7OYfSL+hftIHr4+ufzAbUbk/m3AcessxxwRafuz3DESezSVa0ehETYfpOgkKiYjBx418vbQ7cHadCdfUZig8kizjnvC16YPpyrmxlOL7VrW/9TbH1TarDKb9T4fUglFdANhcxNocEcAwKBgGOZ8oiUBqB/VAqLOh3H0FzvCvqosZM+wuL8Esn+ZCtmTRiUmNiqMIt5eSB0XEDawIfw3BcJXKWRlkdVcEaymIUOPuRS+wxgDD8K4wZNkIrB+ZBgT0wsftzU7ZTgXVjNXYpM6VPit+lBcQJagBNogL5xgS5KzCu1+KTPz/ZBiFTpAoGAduwk79uMV6XRBl5oLV5NcV7vVVRLgD0qUc3SCpmToIYRY37fl1vdsu0JMGprhMBbnW1AhcWhAmtnvoMP6buD9OqZIkr7aHlTSHrpYWWWd1JGMausKpOWpLv39g9L95VeA6zUsxeBiAWZWU5JUC9EpyHBuVrygz725KUHXEawL+8CgYEA2+s7FIdSZz/4yMADHIqnSthKGgBu95WzK5dN7AD1i1g/Uy+n+wn5V0coPKAlPbGbYCNgm8zh6Bzy/qBv7o+TSk1OsjWtLMh3dNIjkJ/LLb9QXzKF/NYiN1NWCPjQ/bHmhODCgzU9h3wvJpbudugcwDflmoJOZYNWHU497efWmmw=")
        val privateKey = BitwardenCrypto.parsePrivateKey(der)
        val orgKey = BitwardenCrypto.decryptRsa(EncString.parse("4.CoqMfibWKFqugyddePTTohm1l8ueWlKR2h1WVe7vjDeqJXP9beC38yU3pn9gTSlXbX3e/E8kYMRMRLJs5mHNTGbWJfJSqk/BFT8MMM/KRP+SighWC4fEaTWLQlkeBaZ9jYMgtpVU7sKLbzwXaiKd3FitsWqTzs9urybgV20qxkDht8gsVAkQSHbYpx6+RmWij6mjy1lCLo/2eEzPgxeDFCoZGVW5b4gcC0SeYSzPql6p5agdYpfF4H7U9yMxxYMa4EG3QXXieoKAyeO/+Hp9woRLl6DplgabcyQwSxyLHiruqvjYwwwKkvjRM+AYalOR5Az9UYH5a3ZAmp13ivl1kQ=="), privateKey)
        assertArrayEquals(hex("00070e151c232a31383f464d545b626970777e858c939aa1a8afb6bdc4cbd2d9e0e7eef5fc030a11181f262d343b424950575e656c737a81888f969da4abb2b9"), orgKey)
    }

    @Test
    fun `EncString rejects malformed input`() {
        assertThrows(VaultCryptoException::class.java) { EncString.parse("2.onlyonepart") }
        assertThrows(VaultCryptoException::class.java) { EncString.parse("") }
    }
}

class TotpTest {

    private val rfcSecret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    @Test
    fun `RFC 6238 vector at T equals 59`() {
        val uri = "otpauth://totp/test?secret=" + rfcSecret + "&digits=8"
        assertEquals("94287082", Totp.generate(uri, timeMillis = 59_000L).code)
    }

    @Test
    fun `RFC 6238 vector at T equals 1111111109`() {
        val uri = "otpauth://totp/test?secret=" + rfcSecret + "&digits=8"
        assertEquals("07081804", Totp.generate(uri, timeMillis = 1_111_111_109_000L).code)
    }

    @Test
    fun `a bare base32 secret yields the default six digits`() {
        assertEquals("287082", Totp.generate(rfcSecret, timeMillis = 59_000L).code)
    }

    @Test
    fun `the remaining validity is reported`() {
        assertEquals(1, Totp.generate(rfcSecret, timeMillis = 59_000L).secondsRemaining)
    }

    @Test
    fun `base32 decoding tolerates lower case, spaces and missing padding`() {
        val expected = Totp.base32Decode(rfcSecret)
        assertArrayEquals(expected, Totp.base32Decode(rfcSecret.lowercase()))
        assertArrayEquals(expected, Totp.base32Decode(rfcSecret.chunked(4).joinToString(" ")))
    }
}
