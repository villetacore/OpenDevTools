package com.opendevtools.gpg.cli

import com.opendevtools.gpg.model.SignatureStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The streams below were captured from `gpg 2.5.18 --status-fd`. They are the reason the plugin
 * never reads gpg's prose: on the machine they came from, every human-readable line was in
 * Russian, while these lines are identical on every installation.
 */
class StatusParserTest {

    private val goodSignature = """
        [GNUPG:] NEWSIG test@example.org
        [GNUPG:] KEY_CONSIDERED 90072FB52B9A6B7C4CBDB5CCE19BC1C13EA0EA8F 0
        [GNUPG:] SIG_ID ZW2G+evNRrt+ZLP6ek/f5NafuOg 2026-09-07 1788808794
        [GNUPG:] GOODSIG E19BC1C13EA0EA8F Second Identity <second@example.org>
        [GNUPG:] VALIDSIG 90072FB52B9A6B7C4CBDB5CCE19BC1C13EA0EA8F 2026-09-07 1788808794 0 4 0 1 8 00 90072FB52B9A6B7C4CBDB5CCE19BC1C13EA0EA8F
        [GNUPG:] TRUST_ULTIMATE 0 pgp test@example.org
    """.trimIndent()

    @Test
    fun `a good trusted signature is read whole`() {
        val result = StatusParser.verification(goodSignature)
        val signature = result.signatures.single()

        assertEquals(SignatureStatus.VALID, signature.status)
        assertEquals("E19BC1C13EA0EA8F", signature.keyId)
        assertEquals("90072FB52B9A6B7C4CBDB5CCE19BC1C13EA0EA8F", signature.fingerprint)
        assertEquals(1788808794L, signature.created)
        assertEquals(SignatureStatus.VALID, result.overall)
    }

    /** A user id with spaces is the last field, so it must be rejoined rather than split off. */
    @Test
    fun `the signer name survives its spaces`() {
        assertEquals(
            "Second Identity <second@example.org>",
            StatusParser.verification(goodSignature).signatures.single().signerUid,
        )
    }

    /**
     * "The bytes are intact" and "this really is who they say" are different claims. GnuPG makes
     * both, and a viewer that showed only the first would present a stranger's key as verified.
     */
    @Test
    fun `a good signature from an untrusted key is not reported as fully valid`() {
        val untrusted = goodSignature.replace("TRUST_ULTIMATE", "TRUST_UNDEFINED")
        val signature = StatusParser.verification(untrusted).signatures.single()

        assertEquals(SignatureStatus.VALID_UNTRUSTED, signature.status)
        assertTrue(signature.status.isGood)
    }

    @Test
    fun `a bad signature is reported as bad`() {
        val bad = """
            [GNUPG:] NEWSIG
            [GNUPG:] BADSIG E19BC1C13EA0EA8F Test Person <test@example.org>
        """.trimIndent()

        val result = StatusParser.verification(bad)
        assertEquals(SignatureStatus.BAD, result.overall)
        assertFalse(result.overall.isGood)
    }

    /** Return code 9 means the key is missing, which is not the same as the signature being bad. */
    @Test
    fun `a missing signing key is told apart from a broken signature`() {
        val missing = """
            [GNUPG:] NEWSIG
            [GNUPG:] ERRSIG E19BC1C13EA0EA8F 1 8 00 1788808794 9 90072FB52B9A6B7C4CBDB5CCE19BC1C13EA0EA8F
            [GNUPG:] NO_PUBKEY E19BC1C13EA0EA8F
        """.trimIndent()

        val signatures = StatusParser.verification(missing).signatures

        assertEquals(1, signatures.size, "NO_PUBKEY explains the ERRSIG, it is not a second signature")
        assertEquals(SignatureStatus.NO_PUBLIC_KEY, signatures.single().status)
        assertEquals("E19BC1C13EA0EA8F", signatures.single().keyId)
    }

    @Test
    fun `an expired key and an expired signature are different verdicts`() {
        val expiredKey = "[GNUPG:] NEWSIG\n[GNUPG:] EXPKEYSIG AAAA1111 Old Key <old@example.org>"
        val expiredSig = "[GNUPG:] NEWSIG\n[GNUPG:] EXPSIG AAAA1111 Old Key <old@example.org>"

        assertEquals(SignatureStatus.EXPIRED_KEY, StatusParser.verification(expiredKey).overall)
        assertEquals(SignatureStatus.EXPIRED_SIGNATURE, StatusParser.verification(expiredSig).overall)
    }

    @Test
    fun `several signatures on one file are kept apart`() {
        val two = """
            [GNUPG:] NEWSIG alice@example.org
            [GNUPG:] GOODSIG AAAA1111AAAA1111 Alice <alice@example.org>
            [GNUPG:] TRUST_ULTIMATE 0 pgp
            [GNUPG:] NEWSIG bob@example.org
            [GNUPG:] BADSIG BBBB2222BBBB2222 Bob <bob@example.org>
        """.trimIndent()

        val result = StatusParser.verification(two)

        assertEquals(2, result.signatures.size)
        assertEquals(SignatureStatus.VALID, result.signatures[0].status)
        assertEquals(SignatureStatus.BAD, result.signatures[1].status)
        assertEquals(SignatureStatus.BAD, result.overall, "one bad signature condemns the file")
    }

    @Test
    fun `a file with no signature at all is not called valid`() {
        assertEquals(SignatureStatus.NONE, StatusParser.verification("").overall)
    }

    // --- Decryption -------------------------------------------------------------------------------

    private val decryption = """
        [GNUPG:] ENC_TO F6272A89B83F4EC3 1 0
        [GNUPG:] KEY_CONSIDERED 90072FB52B9A6B7C4CBDB5CCE19BC1C13EA0EA8F 0
        [GNUPG:] DECRYPTION_KEY 3753D54F729A30BA9EC3B76DF6272A89B83F4EC3 90072FB52B9A6B7C4CBDB5CCE19BC1C13EA0EA8F u
        [GNUPG:] BEGIN_DECRYPTION
        [GNUPG:] DECRYPTION_INFO 0 9 2 0
        [GNUPG:] PLAINTEXT 62 1788808794 msg.txt
        [GNUPG:] PLAINTEXT_LENGTH 19
        [GNUPG:] DECRYPTION_OKAY
        [GNUPG:] GOODMDC
        [GNUPG:] END_DECRYPTION
    """.trimIndent()

    @Test
    fun `a successful decryption names the recipient and the key that opened it`() {
        val result = StatusParser.decryption(decryption, "C:/tmp/msg.txt")

        assertTrue(result.success)
        assertEquals(listOf("F6272A89B83F4EC3"), result.recipients)
        assertEquals("3753D54F729A30BA9EC3B76DF6272A89B83F4EC3", result.decryptionKey)
        assertFalse(result.symmetric)
        assertEquals("C:/tmp/msg.txt", result.outputPath)
        assertEquals("", result.failureReason)
    }

    /** A passphrase-encrypted message names no recipient key, which is how it is recognised. */
    @Test
    fun `a passphrase-encrypted message is reported as symmetric`() {
        val symmetric = """
            [GNUPG:] NEED_PASSPHRASE_SYM 9 3 9
            [GNUPG:] BEGIN_DECRYPTION
            [GNUPG:] DECRYPTION_OKAY
            [GNUPG:] END_DECRYPTION
        """.trimIndent()

        val result = StatusParser.decryption(symmetric)

        assertTrue(result.success)
        assertTrue(result.symmetric)
        assertTrue(result.recipients.isEmpty())
    }

    @Test
    fun `a message encrypted to a key we do not hold says exactly that`() {
        val noKey = """
            [GNUPG:] ENC_TO AAAA1111AAAA1111 1 0
            [GNUPG:] NO_SECKEY AAAA1111AAAA1111
            [GNUPG:] DECRYPTION_FAILED
        """.trimIndent()

        val result = StatusParser.decryption(noKey)

        assertFalse(result.success)
        assertEquals(
            "The message is encrypted to a key that is not in this keyring",
            result.failureReason,
        )
    }

    @Test
    fun `a signed and encrypted message reports both`() {
        val result = StatusParser.decryption(decryption + "\n" + goodSignature)

        assertTrue(result.success)
        assertEquals(SignatureStatus.VALID, result.signatures.single().status)
    }

    // --- Import -----------------------------------------------------------------------------------

    @Test
    fun `importing a key that is already present is counted as unchanged`() {
        val output = """
            [GNUPG:] IMPORT_OK 0 B86F3F2BF0F1E652EA31009AF6E6A86442ACD1A9
            [GNUPG:] KEY_CONSIDERED B86F3F2BF0F1E652EA31009AF6E6A86442ACD1A9 0
            [GNUPG:] IMPORT_RES 1 0 0 0 1 0 0 0 0 0 0 0 0 0 0
        """.trimIndent()

        val result = StatusParser.import(output)

        assertEquals(1, result.considered)
        assertEquals(0, result.imported)
        assertEquals(1, result.unchanged)
        assertEquals(listOf("B86F3F2BF0F1E652EA31009AF6E6A86442ACD1A9"), result.fingerprints)
        assertEquals("1 already present", result.describe())
    }

    @Test
    fun `importing a new key counts it as imported`() {
        val output = """
            [GNUPG:] IMPORT_OK 1 90072FB52B9A6B7C4CBDB5CCE19BC1C13EA0EA8F
            [GNUPG:] IMPORT_RES 1 0 1 0 0 0 1 2 0 0 0 0 0 0 0
        """.trimIndent()

        val result = StatusParser.import(output)

        assertEquals(1, result.imported)
        assertEquals(1, result.newSubkeys)
        assertEquals(2, result.newSignatures)
        assertEquals("1 new certificate(s), 1 new subkey(s), 2 new signature(s)", result.describe())
    }

    @Test
    fun `an import that reported nothing still reads as an empty result`() {
        assertEquals("nothing to import", StatusParser.import("").describe())
        assertTrue(StatusParser.import("").fingerprints.isEmpty())
    }

    @Test
    fun `key generation reports the fingerprint it created`() {
        val output = "[GNUPG:] KEY_CREATED B 90072FB52B9A6B7C4CBDB5CCE19BC1C13EA0EA8F"

        assertEquals(
            listOf("90072FB52B9A6B7C4CBDB5CCE19BC1C13EA0EA8F"),
            StatusParser.touchedFingerprints(output),
        )
    }

    // --- Plumbing ---------------------------------------------------------------------------------

    /** Only the prefixed lines are status; the rest of stderr is prose in the user's language. */
    @Test
    fun `human readable output is ignored`() {
        val mixed = """
            gpg: проверка таблицы доверия
            [GNUPG:] NEWSIG
            gpg: Хорошая подпись от "Test Person"
            [GNUPG:] GOODSIG AAAA1111AAAA1111 Test Person <test@example.org>
        """.trimIndent()

        val lines = StatusParser.lines(mixed)

        assertEquals(2, lines.size)
        assertEquals(listOf("NEWSIG", "GOODSIG"), lines.map { it.keyword })
        assertEquals(SignatureStatus.VALID, StatusParser.verification(mixed).overall)
    }

    @Test
    fun `a dismissed pinentry is recognised as a cancellation`() {
        val cancelled = "[GNUPG:] ERROR sign_and_send cancelled\n[GNUPG:] MISSING_PASSPHRASE"

        assertTrue(StatusParser.wasCancelled(StatusParser.lines(cancelled)))
        assertFalse(StatusParser.wasCancelled(StatusParser.lines(goodSignature)))
    }
}
