package com.opendevtools.proxmox.model

/** What a storage may hold; the same identifiers the API uses in its `content` parameter. */
enum class StorageContentType(val api: String, val title: String) {
    IMAGES("images", "Disk images"),
    ROOTDIR("rootdir", "Container volumes"),
    VZTMPL("vztmpl", "Container templates"),
    ISO("iso", "ISO images"),
    BACKUP("backup", "Backups"),
    SNIPPETS("snippets", "Snippets"),
    IMPORT("import", "Import");

    companion object {
        fun fromApi(value: String?): StorageContentType? = entries.firstOrNull { it.api == value }

        fun parseList(value: String?): List<StorageContentType> =
            value.orEmpty().split(',').mapNotNull { fromApi(it.trim()) }
    }
}

data class ProxmoxStorage(
    val name: String,
    val node: String,
    val status: String,
    val used: Long,
    val total: Long,
    val available: Long = (total - used).coerceAtLeast(0),
    val type: String?,
    val content: List<StorageContentType>,
    val shared: Boolean,
    val enabled: Boolean = true,
) {
    val key: String get() = "storage:$node:$name"
    val isActive: Boolean get() = status == "available" || status == "active"

    fun supports(contentType: StorageContentType): Boolean = contentType in content
}

/** One volume inside a storage: a disk image, an ISO, a template or a backup archive. */
data class StorageVolume(
    val volid: String,
    val storage: String,
    val node: String,
    val format: String?,
    val size: Long,
    val used: Long,
    val contentType: StorageContentType?,
    val vmid: Int?,
    val createdAt: Long,
    val notes: String?,
    val protectedVolume: Boolean = false,
    val verification: String? = null,
) {
    /** `local:backup/vzdump-qemu-100-2026_01_01-00_00_00.vma.zst` renders as the file name. */
    val fileName: String get() = volid.substringAfterLast('/')

    val key: String get() = "volume:$node:$volid"

    val isBackup: Boolean get() = contentType == StorageContentType.BACKUP
}
