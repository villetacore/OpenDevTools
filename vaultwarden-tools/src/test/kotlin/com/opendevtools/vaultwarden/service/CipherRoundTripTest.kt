package com.opendevtools.vaultwarden.service

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.opendevtools.core.json.JsonWriter
import com.opendevtools.vaultwarden.crypto.BitwardenCrypto
import com.opendevtools.vaultwarden.crypto.SymmetricCryptoKey
import com.opendevtools.vaultwarden.model.CardDetails
import com.opendevtools.vaultwarden.model.IdentityDetails
import com.opendevtools.vaultwarden.model.LoginDetails
import com.opendevtools.vaultwarden.model.Reprompt
import com.opendevtools.vaultwarden.model.SshKeyDetails
import com.opendevtools.vaultwarden.model.UriMatchType
import com.opendevtools.vaultwarden.model.VaultField
import com.opendevtools.vaultwarden.model.VaultFieldType
import com.opendevtools.vaultwarden.model.VaultItem
import com.opendevtools.vaultwarden.model.VaultItemType
import com.opendevtools.vaultwarden.model.VaultUri
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Encrypting an item and decrypting it again must give the same item back. Because the two sides
 * are written independently - [VaultEncryptor] against the write API, [VaultDecryptor] against the
 * sync payload - this is what catches a field name that only one of them spells correctly.
 */
class CipherRoundTripTest {

    private val userKey = SymmetricCryptoKey.fromBytes(ByteArray(64) { it.toByte() })

    /** Wraps the encrypted cipher the way `/api/sync` presents it, id and all. */
    private fun roundTrip(item: VaultItem): VaultItem {
        val itemKey = SymmetricCryptoKey.generate()
        val wrapped = BitwardenCrypto.wrapKey(itemKey, userKey).encoded
        val cipher = VaultEncryptor(itemKey).toCipher(item, wrapped)
        cipher.addProperty("id", item.id)
        cipher.addProperty("revisionDate", item.revisionDate)

        val decrypted = VaultDecryptor(userKey, emptyMap()).decryptCipher(cipher)
        return requireNotNull(decrypted) { "the cipher could not be decrypted at all" }
    }

    private fun baseItem(type: VaultItemType) = VaultItem(
        id = "11111111-2222-3333-4444-555555555555",
        name = "Example",
        type = type,
        folderId = "ffffffff-0000-0000-0000-000000000000",
        organizationId = null,
        notes = "a note\nwith two lines",
        favorite = true,
        reprompt = Reprompt.PASSWORD,
        revisionDate = "2026-01-02T03:04:05Z",
    )

    @Test
    fun `a login survives the round trip with every field intact`() {
        val original = baseItem(VaultItemType.LOGIN).copy(
            login = LoginDetails(
                username = "alex",
                password = "s3cr3t p@ss",
                totp = "otpauth://totp/Example:alex?secret=JBSWY3DPEHPK3PXP&issuer=Example",
                uris = listOf(
                    VaultUri("https://example.com", UriMatchType.DOMAIN),
                    VaultUri("https://login.example.com", UriMatchType.EXACT),
                ),
            ),
        )

        val result = roundTrip(original)

        assertEquals(original.name, result.name)
        assertEquals(original.notes, result.notes)
        assertEquals(original.folderId, result.folderId)
        assertEquals(original.favorite, result.favorite)
        assertEquals(original.reprompt, result.reprompt)
        assertEquals(original.login?.username, result.login?.username)
        assertEquals(original.login?.password, result.login?.password)
        assertEquals(original.login?.totp, result.login?.totp)
        assertEquals(
            original.login?.uris?.map { it.uri to it.match },
            result.login?.uris?.map { it.uri to it.match },
        )
        assertTrue(result.hasTotp)
    }

    @Test
    fun `a card survives the round trip`() {
        val original = baseItem(VaultItemType.CARD).copy(
            card = CardDetails(
                cardholderName = "A Person",
                brand = "Visa",
                number = "4111111111111111",
                expMonth = "07",
                expYear = "2030",
                code = "123",
            ),
        )

        val result = roundTrip(original)

        assertEquals(original.card, result.card)
        assertEquals("07/2030", result.card?.expiry)
        assertEquals("•••• 1111", result.card?.maskedNumber)
    }

    @Test
    fun `an identity survives the round trip`() {
        val original = baseItem(VaultItemType.IDENTITY).copy(
            identity = IdentityDetails(
                title = "Dr",
                firstName = "Ada",
                lastName = "Lovelace",
                username = "ada",
                company = "Analytical Engines",
                email = "ada@example.com",
                phone = "+1 555 0100",
                ssn = "000-00-0000",
                address1 = "1 Example Street",
                city = "London",
                postalCode = "E1 6AN",
                country = "GB",
            ),
        )

        val result = roundTrip(original)

        assertEquals(original.identity, result.identity)
        assertEquals("Dr Ada Lovelace", result.identity?.fullName)
    }

    @Test
    fun `an SSH key survives the round trip`() {
        val original = baseItem(VaultItemType.SSH_KEY).copy(
            sshKey = SshKeyDetails(
                privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----",
                publicKey = "ssh-ed25519 AAAAC3Nz example",
                fingerprint = "SHA256:abcdef",
            ),
        )

        assertEquals(original.sshKey, roundTrip(original).sshKey)
    }

    @Test
    fun `custom fields keep their names, values and kinds`() {
        val original = baseItem(VaultItemType.SECURE_NOTE).copy(
            fields = listOf(
                VaultField("API endpoint", "https://api.example.com", VaultFieldType.TEXT),
                VaultField("API token", "tok_abcdef", VaultFieldType.HIDDEN),
                VaultField("Production", "true", VaultFieldType.BOOLEAN),
            ),
        )

        val result = roundTrip(original)

        assertEquals(original.fields, result.fields)
        assertTrue(result.fields.single { it.name == "API token" }.hidden)
    }

    @Test
    fun `a field with an empty value comes back as empty rather than missing`() {
        val original = baseItem(VaultItemType.SECURE_NOTE).copy(
            fields = listOf(VaultField("Empty", "", VaultFieldType.TEXT)),
        )

        val result = roundTrip(original)

        assertEquals(1, result.fields.size)
        assertEquals("Empty", result.fields.first().name)
        assertEquals("", result.fields.first().value)
    }

    @Test
    fun `an unnamed custom field is dropped instead of being written back`() {
        val original = baseItem(VaultItemType.SECURE_NOTE).copy(
            fields = listOf(VaultField("  ", "orphan", VaultFieldType.TEXT)),
        )
        assertTrue(roundTrip(original).fields.isEmpty())
    }

    @Test
    fun `only the section of the item's own type is written`() {
        val itemKey = SymmetricCryptoKey.generate()
        val wrapped = BitwardenCrypto.wrapKey(itemKey, userKey).encoded
        val cipher = VaultEncryptor(itemKey).toCipher(
            baseItem(VaultItemType.SECURE_NOTE),
            wrapped,
        )

        assertTrue(cipher.has("secureNote"))
        // A card section on a note is exactly the kind of document the server rejects.
        assertTrue(cipher.get("login") == null || cipher.get("login").isJsonNull)
        assertTrue(cipher.get("card") == null || cipher.get("card").isJsonNull)
        assertTrue(cipher.get("identity") == null || cipher.get("identity").isJsonNull)
    }

    @Test
    fun `the password history is written newest first and capped`() {
        val history = (1..8).map {
            com.opendevtools.vaultwarden.model.PasswordHistoryEntry("old$it", "2026-01-0${it % 9}T00:00:00Z")
        }
        val original = baseItem(VaultItemType.LOGIN).copy(
            login = LoginDetails(password = "current"),
            passwordHistory = history,
        )

        val result = roundTrip(original)

        assertEquals(VaultEncryptor.MAX_PASSWORD_HISTORY, result.passwordHistory.size)
        assertEquals("old1", result.passwordHistory.first().password)
    }

    @Test
    fun `a deleted cipher is reported as such`() {
        val itemKey = SymmetricCryptoKey.generate()
        val wrapped = BitwardenCrypto.wrapKey(itemKey, userKey).encoded
        val cipher = VaultEncryptor(itemKey).toCipher(baseItem(VaultItemType.LOGIN), wrapped)
        cipher.addProperty("id", "deleted-id")
        cipher.addProperty("deletedDate", "2026-02-02T00:00:00Z")

        val result = requireNotNull(VaultDecryptor(userKey, emptyMap()).decryptCipher(cipher))
        assertTrue(result.isDeleted)
    }

    @Test
    fun `a sync payload splits active items, trash and folders`() {
        val itemKey = SymmetricCryptoKey.generate()
        val wrapped = BitwardenCrypto.wrapKey(itemKey, userKey).encoded
        val encryptor = VaultEncryptor(itemKey)

        val active = encryptor.toCipher(baseItem(VaultItemType.LOGIN), wrapped).apply {
            addProperty("id", "active")
        }
        val trashed = encryptor.toCipher(baseItem(VaultItemType.LOGIN), wrapped).apply {
            addProperty("id", "trashed")
            addProperty("deletedDate", "2026-02-02T00:00:00Z")
        }
        // An item shared with an organization whose key this session does not hold cannot be
        // decrypted at all, which is the case the failure counter exists for.
        val broken = JsonObject().apply {
            addProperty("id", "broken")
            addProperty("type", 1)
            addProperty("organizationId", "an-organization-we-have-no-key-for")
            addProperty("name", BitwardenCrypto.encryptString("Shared", userKey).encoded)
        }

        val sync = JsonWriter.build {
            put(
                "folders",
                JsonArray().apply {
                    add(
                        JsonWriter.build {
                            put("id", "folder-1")
                            put("name", BitwardenCrypto.encryptString("Work", userKey).encoded)
                        },
                    )
                },
            )
            put("ciphers", JsonArray().apply { add(active); add(trashed); add(broken) })
        }

        val snapshot = VaultDecryptor(userKey, emptyMap()).decrypt(sync)

        assertEquals(1, snapshot.items.size)
        assertEquals(1, snapshot.deletedItems.size)
        assertEquals(1, snapshot.failedItems)
        assertEquals("Work", snapshot.folders.single().name)
        // The pseudo-folder is added by the view, not by the decryptor.
        assertNull(snapshot.folders.firstOrNull { it.id == null })
        assertEquals("No Folder", snapshot.foldersWithNone().first().name)
    }

    @Test
    fun `one unreadable field does not take the whole item down with it`() {
        val itemKey = SymmetricCryptoKey.generate()
        val wrapped = BitwardenCrypto.wrapKey(itemKey, userKey).encoded
        val cipher = VaultEncryptor(itemKey).toCipher(baseItem(VaultItemType.LOGIN), wrapped).apply {
            addProperty("id", "partly-broken")
            // A value encrypted with a key nobody has: the item must still list, marked as such.
            addProperty(
                "name",
                BitwardenCrypto.encryptString("x", SymmetricCryptoKey.generate()).encoded,
            )
        }

        val result = requireNotNull(VaultDecryptor(userKey, emptyMap()).decryptCipher(cipher))
        assertEquals("(cannot decrypt)", result.name)
        assertEquals(baseItem(VaultItemType.LOGIN).notes, result.notes)
    }
}
