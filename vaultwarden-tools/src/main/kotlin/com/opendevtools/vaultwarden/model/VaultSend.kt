package com.opendevtools.vaultwarden.model

/** Bitwarden Send: a one-off share of a text or a file, with its own key in the link fragment. */
enum class SendType(val code: Int, val title: String) {
    TEXT(0, "Text"),
    FILE(1, "File");

    companion object {
        fun fromCode(code: Int): SendType = if (code == FILE.code) FILE else TEXT
    }
}

data class VaultSend(
    val id: String,
    val name: String,
    val type: SendType,
    val text: String?,
    val hideText: Boolean,
    val fileName: String?,
    val fileSize: Long,
    val notes: String?,
    val maxAccessCount: Int?,
    val accessCount: Int,
    val expirationDate: String?,
    val deletionDate: String?,
    val disabled: Boolean,
    val passwordProtected: Boolean,
    /** Base64url of the Send's own key, which belongs in the URL fragment of the share link. */
    val accessKey: String?,
    val accessId: String?,
) {
    val key: String get() = "send:$id"

    val isExhausted: Boolean get() = maxAccessCount != null && accessCount >= maxAccessCount

    /** The link recipients open. Without the key fragment the content cannot be decrypted. */
    fun shareUrl(serverUrl: String): String? {
        if (accessId.isNullOrBlank() || accessKey.isNullOrBlank()) return null
        return serverUrl.trimEnd('/') + "/#/send/" + accessId + "/" + accessKey
    }
}
