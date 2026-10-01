package com.opendevtools.vaultwarden.service

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.util.concurrency.AppExecutorUtil
import com.opendevtools.core.json.array
import com.opendevtools.core.json.obj
import com.opendevtools.core.json.str
import com.opendevtools.vaultwarden.api.ApiConfig
import com.opendevtools.vaultwarden.api.VaultwardenApi
import com.opendevtools.vaultwarden.api.VaultwardenException
import com.opendevtools.vaultwarden.crypto.BitwardenCrypto
import com.opendevtools.vaultwarden.crypto.EncString
import com.opendevtools.vaultwarden.crypto.SymmetricCryptoKey
import com.opendevtools.vaultwarden.crypto.Totp
import com.opendevtools.vaultwarden.crypto.TotpCode
import com.opendevtools.vaultwarden.crypto.VaultCryptoException
import com.opendevtools.vaultwarden.model.PasswordHistoryEntry
import com.opendevtools.vaultwarden.model.VaultAttachment
import com.opendevtools.vaultwarden.model.VaultItem
import com.opendevtools.vaultwarden.model.VaultSnapshot
import com.opendevtools.vaultwarden.settings.VaultwardenSettings
import com.opendevtools.vaultwarden.util.VaultwardenNotifier
import java.time.Instant
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Holds the vault keys for the running IDE and performs every read and write against the server.
 *
 * Keys, the access token and the decrypted items live in memory only and are dropped on lock, on
 * auto-lock and when the IDE exits. All methods block and must be called from a background thread.
 */
@Service(Service.Level.APP)
class VaultwardenService {

    @Volatile
    private var accessToken: String? = null

    @Volatile
    private var tokenValidUntil: Long = 0

    @Volatile
    private var userKey: SymmetricCryptoKey? = null

    @Volatile
    private var organizationKeys: Map<String, SymmetricCryptoKey> = emptyMap()

    @Volatile
    private var snapshot: VaultSnapshot? = null

    @Volatile
    private var accountEmail: String? = null

    @Volatile
    private var lastActivity: Long = 0

    private var autoLockTask: ScheduledFuture<*>? = null

    val isUnlocked: Boolean get() = userKey != null

    fun email(): String? = accountEmail

    fun cachedSnapshot(): VaultSnapshot? = snapshot

    fun apiConfig(): ApiConfig = VaultwardenSettings.getInstance().toApiConfig()

    // --- Session ------------------------------------------------------------------------------

    /** Checks server reachability and the API key without touching the master password. */
    fun testConnection(): String {
        val config = apiConfig()
        requireComplete(config)
        val api = VaultwardenApi(config)
        val token = api.requestToken()
        val sync = api.sync(token.accessToken)
        val email = sync.obj("profile")?.str("email") ?: "unknown"
        return "Authenticated as $email\n" +
            "${sync.array("ciphers").size} items, " +
            "${sync.array("folders").size} folders, " +
            "${sync.array("sends").size} sends"
    }

    /** Derives the vault keys from the master password and loads the vault. */
    fun unlock(password: CharArray) {
        val config = apiConfig()
        requireComplete(config)
        val api = VaultwardenApi(config)

        val token = api.requestToken()
        val sync = api.sync(token.accessToken)
        val profile = sync.obj("profile")
            ?: throw VaultwardenException("The sync response contained no account profile")
        val email = profile.str("email")
            ?: throw VaultwardenException("The server did not report the account email")

        val kdf = api.prelogin(email)
        val masterKey = BitwardenCrypto.deriveMasterKey(password, email, kdf)
        try {
            val unlockedUserKey = unwrapUserKey(profile, masterKey)
            val orgs = unwrapOrganizationKeys(profile, unlockedUserKey)

            userKey = unlockedUserKey
            organizationKeys = orgs
            accountEmail = email
            storeToken(token.accessToken, token.expiresInSeconds)
            touch()
            startAutoLock()
            applySnapshot(sync)
        } finally {
            masterKey.fill(0)
        }
    }

    fun lock() {
        val hadSession = userKey != null
        userKey?.clear()
        userKey = null
        organizationKeys.values.forEach { it.clear() }
        organizationKeys = emptyMap()
        snapshot = null
        accessToken = null
        tokenValidUntil = 0
        accountEmail = null
        autoLockTask?.cancel(false)
        autoLockTask = null
        if (hadSession) VaultwardenSettings.fireStateChanged()
    }

    /** Re-fetches the vault with the keys already in memory. */
    fun sync(): VaultSnapshot {
        val api = api()
        val sync = api.sync(validToken(api))
        touch()
        return applySnapshot(sync)
    }

    fun totp(item: VaultItem): TotpCode {
        val secret = item.totpSecret
            ?: throw VaultCryptoException("This item has no one-time password configured")
        touch()
        return Totp.generate(secret)
    }

    // --- Item writes ---------------------------------------------------------------------------

    /**
     * Creates an item. A fresh item key is generated and wrapped with the account key, or with the
     * organization key when the item is shared from the start.
     */
    fun createItem(item: VaultItem, collectionIds: List<String> = emptyList()): VaultItem {
        val api = api()
        val token = validToken(api)
        val wrappingKey = wrappingKeyFor(item.organizationId)

        val itemKey = SymmetricCryptoKey.generate()
        try {
            val wrapped = BitwardenCrypto.wrapKey(itemKey, wrappingKey).encoded
            val cipher = VaultEncryptor(itemKey).toCipher(item, wrapped)

            val response = if (item.organizationId != null) {
                api.createSharedCipher(token, cipher, collectionIds)
            } else {
                api.createCipher(token, cipher)
            }
            touch()
            return refreshItemFrom(response) ?: item
        } finally {
            itemKey.clear()
        }
    }

    /**
     * Updates an item. The existing item key is reused where the server sent one, so attachments -
     * which are encrypted with that key - keep working.
     */
    fun updateItem(item: VaultItem, previousPassword: String? = null): VaultItem {
        val api = api()
        val token = validToken(api)

        val existing = api.cipher(token, item.id)
        val wrappingKey = wrappingKeyFor(item.organizationId ?: existing.str("organizationId"))

        val existingWrapped = existing.str("key")
        val itemKey: SymmetricCryptoKey
        val wrapped: String
        if (existingWrapped != null) {
            // Reusing the existing key keeps the item's attachments readable.
            itemKey = SymmetricCryptoKey.fromBytes(
                BitwardenCrypto.decrypt(EncString.parse(existingWrapped), wrappingKey),
            )
            wrapped = existingWrapped
        } else {
            // A pre-item-key cipher: give it one now, which is what the official clients also do.
            itemKey = SymmetricCryptoKey.generate()
            wrapped = BitwardenCrypto.wrapKey(itemKey, wrappingKey).encoded
        }

        try {
            val withHistory = appendPasswordHistory(item, previousPassword)
            val cipher = VaultEncryptor(itemKey).toCipher(withHistory, wrapped)
            // Keeping the attachment list makes the server preserve them across the update.
            existing.get("attachments")?.let { cipher.add("attachments", it) }

            val response = api.updateCipher(token, item.id, cipher)
            touch()
            return refreshItemFrom(response) ?: withHistory
        } finally {
            // Always derived for this call - never the account or organization key itself.
            itemKey.clear()
        }
    }

    /** Moves an item to the trash, where it stays until it is restored or purged. */
    fun trashItem(item: VaultItem) {
        val api = api()
        api.softDeleteCipher(validToken(api), item.id)
        touch()
    }

    fun restoreItem(item: VaultItem) {
        val api = api()
        api.restoreCipher(validToken(api), item.id)
        touch()
    }

    /** Removes an item permanently; there is no undo on the server side. */
    fun deleteItemPermanently(item: VaultItem) {
        val api = api()
        api.deleteCipher(validToken(api), item.id)
        touch()
    }

    fun moveToFolder(items: List<VaultItem>, folderId: String?) {
        if (items.isEmpty()) return
        val api = api()
        api.moveCiphersToFolder(validToken(api), items.map { it.id }, folderId)
        touch()
    }

    // --- Folders --------------------------------------------------------------------------------

    fun createFolder(name: String) {
        val api = api()
        val key = userKey ?: throw VaultwardenException("The vault is locked")
        api.createFolder(validToken(api), BitwardenCrypto.encryptString(name, key).encoded)
        touch()
    }

    fun renameFolder(id: String, name: String) {
        val api = api()
        val key = userKey ?: throw VaultwardenException("The vault is locked")
        api.updateFolder(validToken(api), id, BitwardenCrypto.encryptString(name, key).encoded)
        touch()
    }

    fun deleteFolder(id: String) {
        val api = api()
        api.deleteFolder(validToken(api), id)
        touch()
    }

    // --- Attachments -----------------------------------------------------------------------------

    /**
     * Downloads and decrypts an attachment. Attachments have their own key wrapped with the item
     * key; very old ones instead reuse the item key directly, which is the fallback here.
     */
    fun downloadAttachment(item: VaultItem, attachment: VaultAttachment): ByteArray {
        val api = api()
        val token = validToken(api)

        val url = attachment.url
            ?: api.attachmentInfo(token, item.id, attachment.id)?.str("url")
            ?: throw VaultwardenException("The server did not provide a download URL")

        val encrypted = api.downloadAttachment(url)
        val itemKey = itemKeyOf(item)
        val attachmentKey = attachment.key
            ?.let { wrapped ->
                OwnedKey.owned(
                    SymmetricCryptoKey.fromBytes(
                        BitwardenCrypto.decrypt(EncString.parse(wrapped), itemKey.key),
                    ),
                )
            }
            // An attachment from before per-attachment keys is encrypted with the item key itself.
            ?: itemKey
        try {
            // The payload is a raw EncString body: type byte, iv, mac, ciphertext - not text.
            return BitwardenCrypto.decryptRawBlob(encrypted, attachmentKey.key)
        } finally {
            if (attachmentKey !== itemKey) attachmentKey.clear()
            itemKey.clear()
        }
    }

    fun uploadAttachment(item: VaultItem, fileName: String, content: ByteArray) {
        val api = api()
        val token = validToken(api)
        val itemKey = itemKeyOf(item)

        val attachmentKey = SymmetricCryptoKey.generate()
        try {
            val wrappedKey = BitwardenCrypto.wrapKey(attachmentKey, itemKey.key).encoded
            val encryptedName = BitwardenCrypto.encryptString(fileName, itemKey.key).encoded
            val encryptedData = BitwardenCrypto.encryptRawBlob(content, attachmentKey)

            val reservation = api.reserveAttachment(
                token,
                item.id,
                encryptedName,
                encryptedData.size.toLong(),
                wrappedKey,
            )
            val attachmentId = reservation.str("attachmentId")
                ?: throw VaultwardenException("The server did not return an attachment id")
            api.uploadAttachmentData(token, item.id, attachmentId, encryptedName, encryptedData)
            touch()
        } finally {
            attachmentKey.clear()
            itemKey.clear()
        }
    }

    /** Removes a Send; every link to it stops resolving straight away. */
    fun deleteSend(send: com.opendevtools.vaultwarden.model.VaultSend) {
        val api = api()
        api.deleteSend(validToken(api), send.id)
        touch()
    }

    fun deleteAttachment(item: VaultItem, attachment: VaultAttachment) {
        val api = api()
        api.deleteAttachment(validToken(api), item.id, attachment.id)
        touch()
    }

    // --- Internals ---------------------------------------------------------------------------------

    private fun api(): VaultwardenApi {
        val config = apiConfig()
        requireComplete(config)
        if (!isUnlocked) throw VaultwardenException("The vault is locked")
        return VaultwardenApi(config)
    }

    /** Re-decrypts a single cipher the server just returned, so the UI shows the stored truth. */
    private fun refreshItemFrom(response: JsonObject): VaultItem? {
        val key = userKey ?: return null
        return VaultDecryptor(key, organizationKeys).decryptCipher(response)
    }

    private fun wrappingKeyFor(organizationId: String?): SymmetricCryptoKey {
        val key = userKey ?: throw VaultwardenException("The vault is locked")
        if (organizationId == null) return key
        return organizationKeys[organizationId]
            ?: throw VaultwardenException("No key for organization $organizationId")
    }

    /**
     * A key together with whether this call owns it. A cipher from before per-item keys is
     * encrypted with the account or organization key itself, and zeroing that would take the whole
     * session down with it - so [clear] only wipes keys that were derived for this call.
     */
    private class OwnedKey private constructor(val key: SymmetricCryptoKey, private val owned: Boolean) {

        fun clear() {
            if (owned) key.clear()
        }

        companion object {
            fun owned(key: SymmetricCryptoKey) = OwnedKey(key, true)
            fun borrowed(key: SymmetricCryptoKey) = OwnedKey(key, false)
        }
    }

    /** The item's own key, needed to read or write its attachments. */
    private fun itemKeyOf(item: VaultItem): OwnedKey {
        val api = api()
        val cipher = api.cipher(validToken(api), item.id)
        val wrapping = wrappingKeyFor(item.organizationId ?: cipher.str("organizationId"))
        val wrapped = cipher.str("key") ?: return OwnedKey.borrowed(wrapping)
        return OwnedKey.owned(
            SymmetricCryptoKey.fromBytes(BitwardenCrypto.decrypt(EncString.parse(wrapped), wrapping)),
        )
    }

    /** Records the superseded password, which is what the History tab shows. */
    private fun appendPasswordHistory(item: VaultItem, previousPassword: String?): VaultItem {
        val previous = previousPassword?.takeIf { it.isNotBlank() } ?: return item
        if (previous == item.password) return item
        val entry = PasswordHistoryEntry(previous, Instant.now().toString())
        return item.copy(
            passwordHistory = (listOf(entry) + item.passwordHistory)
                .take(VaultEncryptor.MAX_PASSWORD_HISTORY),
        )
    }

    private fun applySnapshot(sync: JsonObject): VaultSnapshot {
        val key = userKey ?: throw VaultwardenException("The vault is locked")
        val result = VaultDecryptor(key, organizationKeys).decrypt(sync)
        if (result.failedItems > 0) {
            ApplicationManager.getApplication().invokeLater {
                VaultwardenNotifier.warn(null, "${result.failedItems} item(s) could not be decrypted")
            }
        }
        snapshot = result
        return result
    }

    /** The account key is wrapped either with the stretched master key or, on old accounts, directly. */
    private fun unwrapUserKey(profile: JsonObject, masterKey: ByteArray): SymmetricCryptoKey {
        val raw = profile.str("key")
            ?: throw VaultwardenException("The account profile contained no vault key")
        val enc = EncString.parse(raw)
        val wrappingKey = when (enc.type) {
            EncString.AES_CBC256_B64 -> SymmetricCryptoKey(masterKey.copyOf(), null)
            else -> BitwardenCrypto.stretchMasterKey(masterKey)
        }
        try {
            return SymmetricCryptoKey.fromBytes(BitwardenCrypto.decrypt(enc, wrappingKey))
        } catch (e: VaultCryptoException) {
            throw VaultCryptoException("Wrong master password, or the account key cannot be read", e)
        } finally {
            wrappingKey.clear()
        }
    }

    private fun unwrapOrganizationKeys(
        profile: JsonObject,
        key: SymmetricCryptoKey,
    ): Map<String, SymmetricCryptoKey> {
        val organizations = profile.array("organizations")
        if (organizations.isEmpty()) return emptyMap()

        val privateKeyRaw = profile.str("privateKey") ?: return emptyMap()
        val privateKey = BitwardenCrypto.parsePrivateKey(
            BitwardenCrypto.decrypt(EncString.parse(privateKeyRaw), key),
        )
        val result = mutableMapOf<String, SymmetricCryptoKey>()
        for (organization in organizations) {
            val id = organization.str("id") ?: continue
            val wrapped = organization.str("key") ?: continue
            runCatching {
                SymmetricCryptoKey.fromBytes(
                    BitwardenCrypto.decryptRsa(EncString.parse(wrapped), privateKey),
                )
            }.getOrNull()?.let { result[id] = it }
        }
        return result
    }

    private fun validToken(api: VaultwardenApi): String {
        val current = accessToken
        if (current != null && System.currentTimeMillis() < tokenValidUntil) return current
        val token = api.requestToken()
        storeToken(token.accessToken, token.expiresInSeconds)
        return token.accessToken
    }

    private fun storeToken(token: String, expiresInSeconds: Int) {
        accessToken = token
        // Refresh a minute early, so a long request cannot start on an expiring token.
        tokenValidUntil = System.currentTimeMillis() + (expiresInSeconds - 60).coerceAtLeast(30) * 1000L
    }

    private fun requireComplete(config: ApiConfig) {
        if (!config.isComplete) {
            throw VaultwardenException("Set the server URL and the personal API key in the settings first")
        }
        if (config.clientId.contains('@')) {
            throw VaultwardenException(
                "The client id looks like an email address. A personal API key client id looks like " +
                    "user.<uuid>; the web vault shows it under Settings | Security | Keys | View API Key.",
            )
        }
    }

    /** Any interaction with the vault postpones the auto-lock. */
    fun touch() {
        lastActivity = System.currentTimeMillis()
    }

    private fun startAutoLock() {
        autoLockTask?.cancel(false)
        autoLockTask = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
            { checkAutoLock() },
            AUTO_LOCK_CHECK_SECONDS,
            AUTO_LOCK_CHECK_SECONDS,
            TimeUnit.SECONDS,
        )
    }

    private fun checkAutoLock() {
        val minutes = VaultwardenSettings.getInstance().autoLockMinutes
        if (minutes <= 0 || userKey == null) return
        if (System.currentTimeMillis() - lastActivity < minutes * 60_000L) return
        lock()
        ApplicationManager.getApplication().invokeLater {
            VaultwardenNotifier.info(null, "Vault locked after $minutes minutes of inactivity")
        }
    }

    companion object {
        private const val AUTO_LOCK_CHECK_SECONDS = 30L

        fun getInstance(): VaultwardenService =
            ApplicationManager.getApplication().getService(VaultwardenService::class.java)
    }
}
