package com.opendevtools.gpg.cli

import com.opendevtools.gpg.model.KeyCapability
import com.opendevtools.gpg.model.OwnerTrust
import com.opendevtools.gpg.model.Validity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The listings below are the real output of `gpg 2.5.18 --with-colons`, captured from a throwaway
 * keyring rather than written by hand. Hand-written fixtures would agree with whatever the parser
 * happens to do; these agree with GnuPG.
 */
class ColonParserTest {

    private val publicListing = """
        tru:o:1:1788808740:1:3:1:5
        pub:u:3072:1:E19BC1C13EA0EA8F:1788808739:1851880739::u:::scESC::::::23::0:
        fpr:::::::::90072FB52B9A6B7C4CBDB5CCE19BC1C13EA0EA8F:
        uid:u::::1788808739::2B34D487532F1A116CA25754E9A4A9EC432D8F6E::Test Person (parser fixture) <test@example.org>::::::::::0:
        sub:u:3072:1:F6272A89B83F4EC3:1788808739:1851880739:::::e::::::23:
        fpr:::::::::3753D54F729A30BA9EC3B76DF6272A89B83F4EC3:
        pub:u:255:22:F6E6A86442ACD1A9:1788808740:1820344740::u:::scSC:::::ed25519:::0:
        fpr:::::::::B86F3F2BF0F1E652EA31009AF6E6A86442ACD1A9:
        uid:u::::1788808740::D2A2110491019C4344B7A5F31F4DFFD1D7798ABF::Ed Curve <ed@example.org>::::::::::0:
    """.trimIndent()

    private val secretListing = """
        sec:u:3072:1:E19BC1C13EA0EA8F:1788808739:1851880739::u:::scESC:::+:::23::0:
        fpr:::::::::90072FB52B9A6B7C4CBDB5CCE19BC1C13EA0EA8F:
        grp:::::::::6861BE205E2A4DA8BA1D34F7E50851FB56E8B45C:
        uid:u::::1788808739::2B34D487532F1A116CA25754E9A4A9EC432D8F6E::Test Person (parser fixture) <test@example.org>::::::::::0:
        ssb:u:3072:1:F6272A89B83F4EC3:1788808739:1851880739:::::e:::+:::23:
        fpr:::::::::3753D54F729A30BA9EC3B76DF6272A89B83F4EC3:
        grp:::::::::591B8979FAACA3996BAF5E973085ECE55C0C60C0:
    """.trimIndent()

    @Test
    fun `reads the primary key of a listing`() {
        val key = ColonParser.parseKeys(publicListing).first()

        assertEquals("E19BC1C13EA0EA8F", key.keyId)
        assertEquals("90072FB52B9A6B7C4CBDB5CCE19BC1C13EA0EA8F", key.fingerprint)
        assertEquals(3072, key.bits)
        assertEquals(1, key.algorithm)
        assertEquals(1788808739L, key.created)
        assertEquals(1851880739L, key.expires)
        assertEquals(Validity.ULTIMATE, key.validity)
        assertEquals(OwnerTrust.ULTIMATE, key.ownerTrust)
        assertEquals("RSA 3072", key.algorithmTitle)
    }

    @Test
    fun `the trust record and other non-key lines are skipped`() {
        assertEquals(2, ColonParser.parseKeys(publicListing).size)
        assertEquals(1, ColonParser.parseKeys(secretListing, fromSecretKeyring = true).size)
    }

    /**
     * `scESC` says two different things at once: this key signs and certifies, and somewhere in
     * the certificate there is also an encryption key. Reading the upper-case letters as if they
     * described the primary key would claim an RSA signing key can receive encrypted mail.
     */
    @Test
    fun `capability letters separate the primary key from the whole certificate`() {
        val key = ColonParser.parseKeys(publicListing).first()

        assertEquals(setOf(KeyCapability.SIGN, KeyCapability.CERTIFY), key.capabilities)
        assertEquals(
            setOf(KeyCapability.ENCRYPT, KeyCapability.SIGN, KeyCapability.CERTIFY),
            key.wholeCapabilities,
        )
        assertEquals(setOf(KeyCapability.ENCRYPT), key.subkeys.single().capabilities)
        assertTrue(key.canEncrypt, "the encryption subkey makes the certificate encryptable")
    }

    /** A sign-only certificate must not be offered as an encryption recipient. */
    @Test
    fun `a certificate without an encryption subkey cannot encrypt`() {
        val key = ColonParser.parseKeys(publicListing).last()

        assertEquals("ed25519", key.curve)
        assertEquals("EdDSA (ed25519)", key.algorithmTitle)
        assertTrue(key.subkeys.isEmpty())
        assertFalse(key.canEncrypt)
    }

    @Test
    fun `subkey fingerprints attach to the subkey and not to the primary key`() {
        val key = ColonParser.parseKeys(publicListing).first()

        assertEquals("90072FB52B9A6B7C4CBDB5CCE19BC1C13EA0EA8F", key.fingerprint)
        assertEquals("3753D54F729A30BA9EC3B76DF6272A89B83F4EC3", key.subkeys.single().fingerprint)
    }

    @Test
    fun `user ids are read with their name comment and address`() {
        val uid = ColonParser.parseKeys(publicListing).first().userIds.single()

        assertEquals("Test Person (parser fixture) <test@example.org>", uid.uid)
        assertEquals("Test Person", uid.name)
        assertEquals("parser fixture", uid.comment)
        assertEquals("test@example.org", uid.email)
        assertFalse(uid.isAttribute)
    }

    @Test
    fun `the public listing alone never claims a secret key`() {
        assertTrue(ColonParser.parseKeys(publicListing).none { it.hasSecret })
    }

    @Test
    fun `the secret listing marks the key and its subkey as held`() {
        val key = ColonParser.parseKeys(secretListing, fromSecretKeyring = true).single()

        assertTrue(key.hasSecret)
        assertTrue(key.subkeys.single().hasSecret)
        assertNull(key.subkeys.single().cardSerial)
        assertFalse(key.onCard)
    }

    /**
     * Merging the other way round - secret keys as the spine - would drop every certificate the
     * user does not own, which is most of a real keyring.
     */
    @Test
    fun `merging keeps public-only certificates and adds the secret flag to the rest`() {
        val merged = ColonParser.merge(
            ColonParser.parseKeys(publicListing),
            ColonParser.parseKeys(secretListing, fromSecretKeyring = true),
        )

        assertEquals(2, merged.size)
        val own = merged.first { it.fingerprint == "90072FB52B9A6B7C4CBDB5CCE19BC1C13EA0EA8F" }
        assertTrue(own.hasSecret)
        assertTrue(own.subkeys.single().hasSecret)
        assertTrue(own.canSign)

        val other = merged.first { it.fingerprint == "B86F3F2BF0F1E652EA31009AF6E6A86442ACD1A9" }
        assertFalse(other.hasSecret)
        assertFalse(other.canSign, "a certificate without the private half cannot sign")
    }

    @Test
    fun `merging an empty secret listing changes nothing`() {
        val public = ColonParser.parseKeys(publicListing)
        assertEquals(public, ColonParser.merge(public, emptyList()))
    }

    /**
     * A key on a smartcard has the token's serial number in field 15, not `+`. Reading only `+`
     * as "the secret half exists" would tell a card user that their own key cannot sign.
     */
    @Test
    fun `a smartcard subkey is recognised as held on a card`() {
        val serial = "D2760001240103040006123456780000"
        val onCard = secretListing.replace("e:::+:::23:", "e:::$serial:::23:")
        val subkey = ColonParser.parseKeys(onCard, fromSecretKeyring = true).single().subkeys.single()

        assertTrue(subkey.hasSecret)
        assertEquals(serial, subkey.cardSerial)
    }

    /** A stub is the one case where the secret listing shows a key it cannot actually use. */
    @Test
    fun `a stub subkey is not counted as a secret key`() {
        val stub = secretListing.replace("e:::+:::23:", "e:::#:::23:")
        val subkey = ColonParser.parseKeys(stub, fromSecretKeyring = true).single().subkeys.single()

        assertFalse(subkey.hasSecret)
        assertNull(subkey.cardSerial)
    }

    // --- Signatures -------------------------------------------------------------------------------

    private val signatureListing = """
        pub:u:3072:1:D0395F5B28FF381A:1788808780:1820344780::u:::scSC::::::23::0:
        fpr:::::::::F4E9F76620D03670B227D4D8D0395F5B28FF381A:
        uid:u::::1788808780::313D90556EAEB4F4F1B3EB6BA363C453B5330F9B::Revoked Key <rev@example.org>::::::::::0:
        sig:::1:D0395F5B28FF381A:1788808780::::Revoked Key <rev@example.org>:13x::F4E9F76620D03670B227D4D8D0395F5B28FF381A:::8:
        sig:::1:E19BC1C13EA0EA8F:1788808792::::Second Identity <second@example.org>:10x::90072FB52B9A6B7C4CBDB5CCE19BC1C13EA0EA8F:::8:
    """.trimIndent()

    @Test
    fun `signatures attach to the user id above them`() {
        val uid = ColonParser.parseKeys(signatureListing).single().userIds.single()

        assertEquals(2, uid.signatures.size)
        assertEquals("Generic certification", uid.signatures[1].classTitle)
        assertEquals("13", uid.signatures[0].signatureClass)
        assertTrue(uid.signatures.all { it.exportable })
    }

    /**
     * Every key signs its own user ids, so showing those alongside real certifications would bury
     * the one fact the pane exists to report: who else has vouched for this identity.
     */
    @Test
    fun `a keys own binding signature is told apart from someone elses certification`() {
        val uid = ColonParser.parseKeys(signatureListing).single().userIds.single()

        assertTrue(uid.signatures[0].selfSignature)
        assertFalse(uid.signatures[1].selfSignature)
        assertEquals(1, uid.certifications.size)
        assertEquals("Second Identity <second@example.org>", uid.certifications.single().signerUid)
    }

    @Test
    fun `a local signature is marked as not exportable`() {
        val local = signatureListing.replace(":10x::90072", ":10l::90072")
        val uid = ColonParser.parseKeys(local).single().userIds.single()

        assertFalse(uid.certifications.single().exportable)
        assertEquals("10", uid.certifications.single().signatureClass)
    }

    @Test
    fun `a signature by a key that is not in the keyring says so`() {
        val unknown = signatureListing.replace(
            "Second Identity <second@example.org>:10x",
            ":10x",
        )
        val signature = ColonParser.parseKeys(unknown).single().userIds.single().signatures[1]

        assertEquals("(key E19BC1C13EA0EA8F not in keyring)", signature.signerDisplay)
    }

    // --- Field decoding ---------------------------------------------------------------------------

    /**
     * A user id containing a colon would otherwise be cut at the colon, and the fields after it
     * would all shift by one.
     */
    @Test
    fun `escaped characters in a user id are decoded`() {
        assertEquals("Bob: the builder", ColonParser.unescape("""Bob\x3a the builder"""))
        assertEquals("""back\slash""", ColonParser.unescape("""back\x5cslash"""))
        assertEquals("plain text", ColonParser.unescape("plain text"))
        assertEquals("""trailing\x""", ColonParser.unescape("""trailing\x"""))
    }

    @Test
    fun `dates are read in both the epoch and the ISO form`() {
        assertEquals(1788808739L, ColonParser.timestamp("1788808739"))
        assertEquals(0L, ColonParser.timestamp(""))
        assertEquals(0L, ColonParser.timestamp("0"))
        assertEquals(0L, ColonParser.timestamp(null))
        // 2026-09-07T19:18:59Z
        assertEquals(1788808739L, ColonParser.timestamp("20260907T191859"))
        assertEquals(0L, ColonParser.timestamp("not a date"))
    }

    @Test
    fun `a revoked key is reported as revoked and unusable`() {
        val revoked = publicListing.replace("pub:u:3072", "pub:r:3072")
        val key = ColonParser.parseKeys(revoked).first()

        assertTrue(key.isRevoked)
        assertFalse(key.canEncrypt)
        assertFalse(key.validity.isUsable)
    }

    @Test
    fun `a disabled key is recognised from the capability field`() {
        val disabled = publicListing.replace(":scESC:", ":scESCD:")
        assertTrue(ColonParser.parseKeys(disabled).first().disabled)
        assertFalse(ColonParser.parseKeys(disabled).first().canEncrypt)
    }

    // --- Keyserver search -------------------------------------------------------------------------

    @Test
    fun `keyserver hits carry every user id that follows them`() {
        val listing = """
            info:1:2
            pub:AAAA1111BBBB2222:1:4096:1600000000::
            uid:Alice Example <alice@example.org>:1600000000::
            uid:Alice at Work <alice@work.example>:1600000000::
            pub:CCCC3333DDDD4444:22:256:1500000000::r
            uid:Bob Example <bob@example.org>:1500000000::
        """.trimIndent()

        val hits = ColonParser.parseSearchResults(listing)

        assertEquals(2, hits.size)
        assertEquals(
            listOf("Alice Example <alice@example.org>", "Alice at Work <alice@work.example>"),
            hits[0].userIds,
        )
        assertEquals(4096, hits[0].bits)
        assertFalse(hits[0].revoked)
        assertTrue(hits[1].revoked, "the r flag marks a revoked key on the keyserver")
        assertEquals("Bob Example <bob@example.org>", hits[1].display)
    }
}
