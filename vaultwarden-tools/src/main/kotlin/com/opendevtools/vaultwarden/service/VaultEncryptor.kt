package com.opendevtools.vaultwarden.service

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.opendevtools.core.json.JsonWriter
import com.opendevtools.vaultwarden.crypto.BitwardenCrypto
import com.opendevtools.vaultwarden.crypto.SymmetricCryptoKey
import com.opendevtools.vaultwarden.model.PasswordHistoryEntry
import com.opendevtools.vaultwarden.model.VaultItem
import com.opendevtools.vaultwarden.model.VaultItemType

/**
 * Builds the encrypted cipher documents the write endpoints expect.
 *
 * Every item gets its own key, wrapped with the user or organization key: that is what Bitwarden
 * clients do, and it is what makes sharing an item later a re-wrap rather than a re-encrypt.
 */
class VaultEncryptor(private val itemKey: SymmetricCryptoKey) {

    /**
     * The cipher document. [wrappedItemKey] is the item key encrypted with the account or
     * organization key, and must be the one that matches [itemKey].
     */
    fun toCipher(item: VaultItem, wrappedItemKey: String): JsonObject = JsonWriter.build {
        put("type", item.type.code)
        put("name", enc(item.name))
        put("key", wrappedItemKey)
        put("notes", encOrNull(item.notes))
        put("favorite", item.favorite)
        put("reprompt", item.reprompt.code)
        put("folderId", item.folderId)
        put("organizationId", item.organizationId)
        put("fields", fields(item))
        put("passwordHistory", passwordHistory(item.passwordHistory))

        // The server rejects a document that carries a section for the wrong item type.
        when (item.type) {
            VaultItemType.LOGIN -> put("login", login(item))
            VaultItemType.CARD -> put("card", card(item))
            VaultItemType.IDENTITY -> put("identity", identity(item))
            VaultItemType.SSH_KEY -> put("sshKey", sshKey(item))
            VaultItemType.SECURE_NOTE -> put("secureNote", JsonWriter.build { put("type", 0) })
        }
    }

    private fun login(item: VaultItem): JsonObject {
        val login = item.login
        return JsonWriter.build {
            put("username", encOrNull(login?.username))
            put("password", encOrNull(login?.password))
            put("totp", encOrNull(login?.totp))
            put("passwordRevisionDate", login?.passwordRevisionDate)
            put(
                "uris",
                JsonWriter.arrayOfObjects(
                    login?.uris.orEmpty()
                        .filter { it.uri.isNotBlank() }
                        .map { uri ->
                            JsonWriter.build {
                                put("uri", enc(uri.uri))
                                put("match", uri.match.code)
                            }
                        },
                ),
            )
        }
    }

    private fun card(item: VaultItem): JsonObject {
        val card = item.card
        return JsonWriter.build {
            put("cardholderName", encOrNull(card?.cardholderName))
            put("brand", encOrNull(card?.brand))
            put("number", encOrNull(card?.number))
            put("expMonth", encOrNull(card?.expMonth))
            put("expYear", encOrNull(card?.expYear))
            put("code", encOrNull(card?.code))
        }
    }

    private fun identity(item: VaultItem): JsonObject {
        val identity = item.identity
        return JsonWriter.build {
            put("title", encOrNull(identity?.title))
            put("firstName", encOrNull(identity?.firstName))
            put("middleName", encOrNull(identity?.middleName))
            put("lastName", encOrNull(identity?.lastName))
            put("username", encOrNull(identity?.username))
            put("company", encOrNull(identity?.company))
            put("ssn", encOrNull(identity?.ssn))
            put("passportNumber", encOrNull(identity?.passportNumber))
            put("licenseNumber", encOrNull(identity?.licenseNumber))
            put("email", encOrNull(identity?.email))
            put("phone", encOrNull(identity?.phone))
            put("address1", encOrNull(identity?.address1))
            put("address2", encOrNull(identity?.address2))
            put("address3", encOrNull(identity?.address3))
            put("city", encOrNull(identity?.city))
            put("state", encOrNull(identity?.state))
            put("postalCode", encOrNull(identity?.postalCode))
            put("country", encOrNull(identity?.country))
        }
    }

    private fun sshKey(item: VaultItem): JsonObject {
        val sshKey = item.sshKey
        return JsonWriter.build {
            put("privateKey", encOrNull(sshKey?.privateKey))
            put("publicKey", encOrNull(sshKey?.publicKey))
            put("keyFingerprint", encOrNull(sshKey?.fingerprint))
        }
    }

    private fun fields(item: VaultItem): JsonArray = JsonWriter.arrayOfObjects(
        item.fields
            .filter { it.name.isNotBlank() }
            .map { field ->
                JsonWriter.build {
                    put("type", field.type.code)
                    put("name", enc(field.name))
                    put("value", encOrNull(field.value))
                }
            },
    )

    /** Bitwarden keeps at most five superseded passwords; the server trims anything beyond that. */
    private fun passwordHistory(entries: List<PasswordHistoryEntry>): JsonArray =
        JsonWriter.arrayOfObjects(
            entries.take(MAX_PASSWORD_HISTORY).map { entry ->
                JsonWriter.build {
                    put("password", enc(entry.password))
                    put("lastUsedDate", entry.lastUsedDate)
                }
            },
        )

    private fun enc(value: String): String = BitwardenCrypto.encryptString(value, itemKey).encoded

    private fun encOrNull(value: String?): String? =
        value?.takeIf { it.isNotEmpty() }?.let { enc(it) }

    companion object {
        const val MAX_PASSWORD_HISTORY = 5
    }
}
