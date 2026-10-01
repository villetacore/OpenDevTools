package com.opendevtools.vaultwarden.model

/** The item types the Bitwarden data model defines; the numbers are the API's `type` field. */
enum class VaultItemType(val code: Int, val groupTitle: String, val singular: String) {
    LOGIN(1, "Logins", "Login"),
    SECURE_NOTE(2, "Secure Notes", "Secure note"),
    CARD(3, "Cards", "Card"),
    IDENTITY(4, "Identities", "Identity"),
    SSH_KEY(5, "SSH Keys", "SSH key");

    companion object {
        fun fromCode(code: Int): VaultItemType? = entries.firstOrNull { it.code == code }
    }
}

/** Custom field kinds. `LINKED` points at another field of the same item and is shown read-only. */
enum class VaultFieldType(val code: Int, val title: String) {
    TEXT(0, "Text"),
    HIDDEN(1, "Hidden"),
    BOOLEAN(2, "Boolean"),
    LINKED(3, "Linked");

    companion object {
        fun fromCode(code: Int): VaultFieldType = entries.firstOrNull { it.code == code } ?: TEXT
    }
}

/** How a URI is matched against the page the browser extension is on. */
enum class UriMatchType(val code: Int?, val title: String) {
    DEFAULT(null, "Default"),
    DOMAIN(0, "Base domain"),
    HOST(1, "Host"),
    STARTS_WITH(2, "Starts with"),
    EXACT(3, "Exact"),
    REGEX(4, "Regular expression"),
    NEVER(5, "Never");

    companion object {
        fun fromCode(code: Int?): UriMatchType = entries.firstOrNull { it.code == code } ?: DEFAULT
    }
}

/** Ask for the master password again before revealing or copying this item. */
enum class Reprompt(val code: Int) {
    NONE(0),
    PASSWORD(1);

    companion object {
        fun fromCode(code: Int): Reprompt = if (code == PASSWORD.code) PASSWORD else NONE
    }
}

data class VaultField(val name: String, val value: String, val type: VaultFieldType) {
    val hidden: Boolean get() = type == VaultFieldType.HIDDEN
}

data class VaultUri(val uri: String, val match: UriMatchType = UriMatchType.DEFAULT)

/** One superseded password, newest first, as the server keeps them. */
data class PasswordHistoryEntry(val password: String, val lastUsedDate: String?)

data class VaultAttachment(
    val id: String,
    val fileName: String,
    val size: Long,
    val sizeName: String?,
    val url: String?,
    /** The attachment's own key, wrapped with the item key; absent on very old attachments. */
    val key: String?,
)

/** Payment card details. */
data class CardDetails(
    val cardholderName: String? = null,
    val brand: String? = null,
    val number: String? = null,
    val expMonth: String? = null,
    val expYear: String? = null,
    val code: String? = null,
) {
    val expiry: String?
        get() = when {
            expMonth.isNullOrBlank() && expYear.isNullOrBlank() -> null
            else -> (expMonth.orEmpty().padStart(2, '0')) + "/" + expYear.orEmpty()
        }

    /** `•••• 4242` - enough to recognise the card without exposing the number. */
    val maskedNumber: String?
        get() = number?.takeIf { it.length >= 4 }?.let { "•••• " + it.takeLast(4) }

    val isEmpty: Boolean
        get() = listOf(cardholderName, brand, number, expMonth, expYear, code).all { it.isNullOrBlank() }
}

/** Personal details of an identity item. */
data class IdentityDetails(
    val title: String? = null,
    val firstName: String? = null,
    val middleName: String? = null,
    val lastName: String? = null,
    val username: String? = null,
    val company: String? = null,
    val ssn: String? = null,
    val passportNumber: String? = null,
    val licenseNumber: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val address1: String? = null,
    val address2: String? = null,
    val address3: String? = null,
    val city: String? = null,
    val state: String? = null,
    val postalCode: String? = null,
    val country: String? = null,
) {
    val fullName: String?
        get() = listOfNotNull(title, firstName, middleName, lastName)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .takeIf { it.isNotBlank() }

    val fullAddress: String?
        get() = listOfNotNull(address1, address2, address3, city, state, postalCode, country)
            .filter { it.isNotBlank() }
            .joinToString(", ")
            .takeIf { it.isNotBlank() }

    val isEmpty: Boolean get() = fullName == null && email.isNullOrBlank() && fullAddress == null
}

/** An SSH key item; Bitwarden stores the private key, the public key and the fingerprint. */
data class SshKeyDetails(
    val privateKey: String? = null,
    val publicKey: String? = null,
    val fingerprint: String? = null,
) {
    val isEmpty: Boolean get() = privateKey.isNullOrBlank() && publicKey.isNullOrBlank()
}

/** Login-specific fields. */
data class LoginDetails(
    val username: String? = null,
    val password: String? = null,
    val totp: String? = null,
    val uris: List<VaultUri> = emptyList(),
    val passwordRevisionDate: String? = null,
) {
    val hasTotp: Boolean get() = !totp.isNullOrBlank()
}

/**
 * A decrypted vault item. Everything here is plaintext and lives only in memory; it is dropped
 * when the vault locks.
 */
data class VaultItem(
    val id: String,
    val name: String,
    val type: VaultItemType,
    val folderId: String?,
    val organizationId: String?,
    val collectionIds: List<String> = emptyList(),
    val notes: String? = null,
    val fields: List<VaultField> = emptyList(),
    val favorite: Boolean = false,
    val reprompt: Reprompt = Reprompt.NONE,
    val revisionDate: String? = null,
    val creationDate: String? = null,
    val deletedDate: String? = null,
    val login: LoginDetails? = null,
    val card: CardDetails? = null,
    val identity: IdentityDetails? = null,
    val sshKey: SshKeyDetails? = null,
    val attachments: List<VaultAttachment> = emptyList(),
    val passwordHistory: List<PasswordHistoryEntry> = emptyList(),
    /** True when the server said the item is read-only for this user. */
    val readOnly: Boolean = false,
) {
    val username: String? get() = login?.username ?: identity?.username
    val password: String? get() = login?.password
    val totpSecret: String? get() = login?.totp
    val uris: List<String> get() = login?.uris.orEmpty().map { it.uri }
    val hasTotp: Boolean get() = login?.hasTotp == true
    val isDeleted: Boolean get() = deletedDate != null
    val isShared: Boolean get() = organizationId != null
    val hasAttachments: Boolean get() = attachments.isNotEmpty()

    val key: String get() = "item:$id"

    /** The line shown next to the name in the tree; what identifies the item at a glance. */
    val subtitle: String?
        get() = when (type) {
            VaultItemType.LOGIN -> username
            VaultItemType.CARD -> card?.maskedNumber ?: card?.brand
            VaultItemType.IDENTITY -> identity?.fullName ?: identity?.email
            VaultItemType.SSH_KEY -> sshKey?.fingerprint
            VaultItemType.SECURE_NOTE -> null
        }?.takeIf { it.isNotBlank() }

    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        val needle = query.trim().lowercase()
        return name.lowercase().contains(needle) ||
            username?.lowercase()?.contains(needle) == true ||
            uris.any { it.lowercase().contains(needle) } ||
            notes?.lowercase()?.contains(needle) == true ||
            fields.any {
                it.name.lowercase().contains(needle) ||
                    (!it.hidden && it.value.lowercase().contains(needle))
            }
    }

    /** Every plaintext secret of the item, for the "wipe on lock" bookkeeping and the reports. */
    fun secrets(): List<String> = buildList {
        password?.let { add(it) }
        card?.number?.let { add(it) }
        card?.code?.let { add(it) }
        sshKey?.privateKey?.let { add(it) }
        fields.filter { it.hidden }.forEach { add(it.value) }
    }
}

data class VaultFolder(val id: String?, val name: String) {
    val key: String get() = "folder:" + (id ?: "none")

    companion object {
        /** The pseudo-folder every client shows for items with no folder. */
        val NONE = VaultFolder(null, "No Folder")
    }
}

data class VaultCollection(
    val id: String,
    val organizationId: String,
    val name: String,
    val readOnly: Boolean,
) {
    val key: String get() = "collection:$id"
}

data class VaultOrganization(val id: String, val name: String, val enabled: Boolean) {
    val key: String get() = "organization:$id"
}
