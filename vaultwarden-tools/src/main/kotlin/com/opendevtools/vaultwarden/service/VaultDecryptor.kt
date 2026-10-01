package com.opendevtools.vaultwarden.service

import com.google.gson.JsonObject
import com.opendevtools.core.json.array
import com.opendevtools.core.json.bool
import com.opendevtools.core.json.int
import com.opendevtools.core.json.intOrNull
import com.opendevtools.core.json.long
import com.opendevtools.core.json.obj
import com.opendevtools.core.json.str
import com.opendevtools.core.json.stringArray
import com.opendevtools.vaultwarden.crypto.BitwardenCrypto
import com.opendevtools.vaultwarden.crypto.EncString
import com.opendevtools.vaultwarden.crypto.SymmetricCryptoKey
import com.opendevtools.vaultwarden.model.CardDetails
import com.opendevtools.vaultwarden.model.IdentityDetails
import com.opendevtools.vaultwarden.model.LoginDetails
import com.opendevtools.vaultwarden.model.PasswordHistoryEntry
import com.opendevtools.vaultwarden.model.Reprompt
import com.opendevtools.vaultwarden.model.SendType
import com.opendevtools.vaultwarden.model.SshKeyDetails
import com.opendevtools.vaultwarden.model.UriMatchType
import com.opendevtools.vaultwarden.model.VaultAttachment
import com.opendevtools.vaultwarden.model.VaultCollection
import com.opendevtools.vaultwarden.model.VaultField
import com.opendevtools.vaultwarden.model.VaultFieldType
import com.opendevtools.vaultwarden.model.VaultFolder
import com.opendevtools.vaultwarden.model.VaultItem
import com.opendevtools.vaultwarden.model.VaultItemType
import com.opendevtools.vaultwarden.model.VaultOrganization
import com.opendevtools.vaultwarden.model.VaultSend
import com.opendevtools.vaultwarden.model.VaultSnapshot
import com.opendevtools.vaultwarden.model.VaultUri

/**
 * Turns a `/api/sync` payload into the plugin model. Items that cannot be decrypted are skipped
 * and counted rather than failing the whole vault, because one unreadable legacy entry should not
 * hide the other nine hundred.
 */
class VaultDecryptor(
    private val userKey: SymmetricCryptoKey,
    private val organizationKeys: Map<String, SymmetricCryptoKey>,
) {

    fun decrypt(sync: JsonObject): VaultSnapshot {
        val folders = sync.array("folders").mapNotNull { raw ->
            val id = raw.str("id") ?: return@mapNotNull null
            VaultFolder(id, decryptOrNull(raw.str("name"), userKey) ?: UNREADABLE)
        }

        val collections = sync.array("collections").mapNotNull { raw ->
            val id = raw.str("id") ?: return@mapNotNull null
            val organizationId = raw.str("organizationId") ?: return@mapNotNull null
            val key = organizationKeys[organizationId] ?: return@mapNotNull null
            VaultCollection(
                id = id,
                organizationId = organizationId,
                name = decryptOrNull(raw.str("name"), key) ?: UNREADABLE,
                readOnly = raw.bool("readOnly"),
            )
        }

        val organizations = sync.obj("profile")?.array("organizations").orEmpty().mapNotNull { raw ->
            val id = raw.str("id") ?: return@mapNotNull null
            VaultOrganization(id, raw.str("name") ?: id, raw.bool("enabled"))
        }

        val active = mutableListOf<VaultItem>()
        val deleted = mutableListOf<VaultItem>()
        var failed = 0
        sync.array("ciphers").forEach { raw ->
            val item = runCatching { toItem(raw) }.getOrNull()
            when {
                item == null -> failed++
                item.isDeleted -> deleted.add(item)
                else -> active.add(item)
            }
        }

        val sends = sync.array("sends").mapNotNull { raw ->
            runCatching { toSend(raw) }.getOrNull()
        }

        return VaultSnapshot(
            folders = folders,
            items = active.sortedBy { it.name.lowercase() },
            deletedItems = deleted.sortedBy { it.name.lowercase() },
            collections = collections.sortedBy { it.name.lowercase() },
            organizations = organizations.sortedBy { it.name.lowercase() },
            sends = sends.sortedBy { it.name.lowercase() },
            failedItems = failed,
        )
    }

    /** Decrypts a single cipher, used after a create or an update to refresh just that item. */
    fun decryptCipher(raw: JsonObject): VaultItem? = runCatching { toItem(raw) }.getOrNull()

    private fun toItem(raw: JsonObject): VaultItem? {
        val id = raw.str("id") ?: return null
        val type = VaultItemType.fromCode(raw.int("type")) ?: return null
        val key = keyFor(raw)

        return VaultItem(
            id = id,
            name = decryptOrNull(raw.str("name"), key) ?: UNREADABLE,
            type = type,
            folderId = raw.str("folderId"),
            organizationId = raw.str("organizationId"),
            collectionIds = raw.stringArray("collectionIds"),
            notes = decryptOrNull(raw.str("notes"), key),
            fields = decryptFields(raw, key),
            favorite = raw.bool("favorite"),
            reprompt = Reprompt.fromCode(raw.int("reprompt")),
            revisionDate = raw.str("revisionDate"),
            creationDate = raw.str("creationDate"),
            deletedDate = raw.str("deletedDate"),
            login = raw.obj("login")?.let { decryptLogin(it, key) },
            card = raw.obj("card")?.let { decryptCard(it, key) },
            identity = raw.obj("identity")?.let { decryptIdentity(it, key) },
            sshKey = raw.obj("sshKey")?.let { decryptSshKey(it, key) },
            attachments = decryptAttachments(raw, key),
            passwordHistory = decryptPasswordHistory(raw, key),
            readOnly = raw.bool("edit").let { editable -> raw.has("edit") && !editable },
        )
    }

    private fun decryptFields(raw: JsonObject, key: SymmetricCryptoKey): List<VaultField> =
        raw.array("fields").mapNotNull { field ->
            val name = decryptOrNull(field.str("name"), key) ?: return@mapNotNull null
            VaultField(
                name = name,
                value = decryptOrNull(field.str("value"), key).orEmpty(),
                type = VaultFieldType.fromCode(field.int("type")),
            )
        }

    private fun decryptLogin(login: JsonObject, key: SymmetricCryptoKey) = LoginDetails(
        username = decryptOrNull(login.str("username"), key),
        password = decryptOrNull(login.str("password"), key),
        totp = decryptOrNull(login.str("totp"), key),
        uris = login.array("uris").mapNotNull { entry ->
            decryptOrNull(entry.str("uri"), key)?.let {
                VaultUri(it, UriMatchType.fromCode(entry.intOrNull("match")))
            }
        },
        passwordRevisionDate = login.str("passwordRevisionDate"),
    )

    private fun decryptCard(card: JsonObject, key: SymmetricCryptoKey) = CardDetails(
        cardholderName = decryptOrNull(card.str("cardholderName"), key),
        brand = decryptOrNull(card.str("brand"), key),
        number = decryptOrNull(card.str("number"), key),
        expMonth = decryptOrNull(card.str("expMonth"), key),
        expYear = decryptOrNull(card.str("expYear"), key),
        code = decryptOrNull(card.str("code"), key),
    )

    private fun decryptIdentity(identity: JsonObject, key: SymmetricCryptoKey) = IdentityDetails(
        title = decryptOrNull(identity.str("title"), key),
        firstName = decryptOrNull(identity.str("firstName"), key),
        middleName = decryptOrNull(identity.str("middleName"), key),
        lastName = decryptOrNull(identity.str("lastName"), key),
        username = decryptOrNull(identity.str("username"), key),
        company = decryptOrNull(identity.str("company"), key),
        ssn = decryptOrNull(identity.str("ssn"), key),
        passportNumber = decryptOrNull(identity.str("passportNumber"), key),
        licenseNumber = decryptOrNull(identity.str("licenseNumber"), key),
        email = decryptOrNull(identity.str("email"), key),
        phone = decryptOrNull(identity.str("phone"), key),
        address1 = decryptOrNull(identity.str("address1"), key),
        address2 = decryptOrNull(identity.str("address2"), key),
        address3 = decryptOrNull(identity.str("address3"), key),
        city = decryptOrNull(identity.str("city"), key),
        state = decryptOrNull(identity.str("state"), key),
        postalCode = decryptOrNull(identity.str("postalCode"), key),
        country = decryptOrNull(identity.str("country"), key),
    )

    private fun decryptSshKey(sshKey: JsonObject, key: SymmetricCryptoKey) = SshKeyDetails(
        privateKey = decryptOrNull(sshKey.str("privateKey"), key),
        publicKey = decryptOrNull(sshKey.str("publicKey"), key),
        fingerprint = decryptOrNull(sshKey.str("keyFingerprint"), key)
            ?: decryptOrNull(sshKey.str("fingerprint"), key),
    )

    private fun decryptAttachments(raw: JsonObject, key: SymmetricCryptoKey): List<VaultAttachment> =
        raw.array("attachments").mapNotNull { attachment ->
            val id = attachment.str("id") ?: return@mapNotNull null
            VaultAttachment(
                id = id,
                fileName = decryptOrNull(attachment.str("fileName"), key) ?: UNREADABLE,
                size = attachment.str("size")?.toLongOrNull() ?: attachment.long("size"),
                sizeName = attachment.str("sizeName"),
                url = attachment.str("url"),
                key = attachment.str("key"),
            )
        }

    private fun decryptPasswordHistory(
        raw: JsonObject,
        key: SymmetricCryptoKey,
    ): List<PasswordHistoryEntry> = raw.array("passwordHistory").mapNotNull { entry ->
        decryptOrNull(entry.str("password"), key)?.let {
            PasswordHistoryEntry(it, entry.str("lastUsedDate"))
        }
    }

    /**
     * A Send is encrypted with its own key, which is itself wrapped with the user key. The
     * plaintext key also goes into the share link's fragment, which is why it is kept here.
     */
    private fun toSend(raw: JsonObject): VaultSend? {
        val id = raw.str("id") ?: return null
        val wrapped = raw.str("key") ?: return null
        val sendKeyMaterial = BitwardenCrypto.decrypt(EncString.parse(wrapped), userKey)
        // Bitwarden derives a Send's key with full HKDF, salt "bitwarden-send" and info "send".
        val sendKey = SymmetricCryptoKey.fromBytes(
            BitwardenCrypto.hkdf(sendKeyMaterial, SEND_KDF_SALT, SEND_KDF_INFO, 64),
        )
        try {
            val text = raw.obj("text")
            val file = raw.obj("file")
            return VaultSend(
                id = id,
                name = decryptOrNull(raw.str("name"), sendKey) ?: UNREADABLE,
                type = SendType.fromCode(raw.int("type")),
                text = decryptOrNull(text?.str("text"), sendKey),
                hideText = text?.bool("hidden") == true,
                fileName = decryptOrNull(file?.str("fileName"), sendKey),
                fileSize = file?.str("size")?.toLongOrNull() ?: 0L,
                notes = decryptOrNull(raw.str("notes"), sendKey),
                maxAccessCount = raw.intOrNull("maxAccessCount"),
                accessCount = raw.int("accessCount"),
                expirationDate = raw.str("expirationDate"),
                deletionDate = raw.str("deletionDate"),
                disabled = raw.bool("disabled"),
                passwordProtected = !raw.str("password").isNullOrBlank(),
                accessKey = base64Url(sendKeyMaterial),
                accessId = raw.str("accessId"),
            )
        } finally {
            sendKeyMaterial.fill(0)
        }
    }

    /** Organization key when the item is shared, plus the per-item key when the server sends one. */
    private fun keyFor(cipher: JsonObject): SymmetricCryptoKey {
        val organizationId = cipher.str("organizationId")
        val baseKey = if (organizationId != null) {
            organizationKeys[organizationId]
                ?: throw IllegalStateException("No key for organization $organizationId")
        } else {
            userKey
        }
        val itemKey = cipher.str("key") ?: return baseKey
        return SymmetricCryptoKey.fromBytes(
            BitwardenCrypto.decrypt(EncString.parse(itemKey), baseKey),
        )
    }

    private fun decryptOrNull(value: String?, key: SymmetricCryptoKey): String? {
        if (value.isNullOrEmpty()) return null
        return runCatching { BitwardenCrypto.decryptToString(EncString.parse(value), key) }.getOrNull()
    }

    private companion object {
        const val UNREADABLE = "(cannot decrypt)"
        const val SEND_KDF_SALT = "bitwarden-send"
        const val SEND_KDF_INFO = "send"

        fun base64Url(bytes: ByteArray): String =
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
