package com.opendevtools.vaultwarden.model

/** How the tree groups items; the web vault offers the same set of views. */
enum class VaultGrouping(val title: String) {
    BY_FOLDER("Folders"),
    BY_TYPE("Item types"),
    BY_COLLECTION("Collections"),
    FLAT("All items"),
}

/**
 * One decrypted view of the vault. Deleted items are kept separately so the Trash view can show
 * them without every other view having to filter them out.
 */
data class VaultSnapshot(
    val folders: List<VaultFolder>,
    val items: List<VaultItem>,
    val deletedItems: List<VaultItem> = emptyList(),
    val collections: List<VaultCollection> = emptyList(),
    val organizations: List<VaultOrganization> = emptyList(),
    val sends: List<VaultSend> = emptyList(),
    val failedItems: Int = 0,
    val loadedAt: Long = System.currentTimeMillis(),
) {
    fun item(id: String): VaultItem? =
        items.firstOrNull { it.id == id } ?: deletedItems.firstOrNull { it.id == id }

    fun folder(id: String?): VaultFolder? = folders.firstOrNull { it.id == id }

    fun organization(id: String?): VaultOrganization? =
        id?.let { organizations.firstOrNull { organization -> organization.id == it } }

    /** Folders that actually exist, with the "No Folder" pseudo-folder always first. */
    fun foldersWithNone(): List<VaultFolder> =
        listOf(VaultFolder.NONE) + folders.filter { it.id != null }.sortedBy { it.name.lowercase() }

    companion object {
        val EMPTY = VaultSnapshot(emptyList(), emptyList())
    }
}

/** A finding of the vault health reports. */
data class VaultReportFinding(
    val item: VaultItem,
    val detail: String,
)

/** The offline checks the plugin can run over decrypted items without contacting anything. */
data class VaultReport(
    val weakPasswords: List<VaultReportFinding>,
    val reusedPasswords: List<VaultReportFinding>,
    val unsecuredUris: List<VaultReportFinding>,
    val missingTotp: List<VaultReportFinding>,
    val inactiveTotp: List<VaultReportFinding> = emptyList(),
) {
    val total: Int
        get() = weakPasswords.size + reusedPasswords.size + unsecuredUris.size + missingTotp.size
}
