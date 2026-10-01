package com.opendevtools.proxmox.model

/** `vzdump` snapshot mode; the trade-off between guest downtime and consistency. */
enum class BackupMode(val api: String, val title: String, val hint: String) {
    SNAPSHOT("snapshot", "Snapshot", "No downtime; needs storage snapshot support"),
    SUSPEND("suspend", "Suspend", "Short downtime while the guest is frozen"),
    STOP("stop", "Stop", "Guest is stopped for the whole backup"),
}

enum class BackupCompression(val api: String, val title: String) {
    NONE("0", "None"),
    LZO("lzo", "LZO (fast)"),
    GZIP("gzip", "GZIP (good)"),
    ZSTD("zstd", "ZSTD (fast and good)"),
}

/** Whether the server should mail a report; mirrors the web UI's notification dropdown. */
enum class BackupNotification(val api: String, val title: String) {
    ALWAYS("always", "Always"),
    FAILURE("failure", "On failure only"),
}

/** Parameters of a one-off `vzdump` run started from the tool window. */
data class BackupRequest(
    val node: String,
    val vmids: List<Int>,
    val storage: String,
    val mode: BackupMode,
    val compression: BackupCompression,
    val notes: String?,
    val protectBackup: Boolean,
    val removeOlder: Boolean,
    val mailTo: String?,
    val notification: BackupNotification,
) {
    fun toParameters(): Map<String, String> = buildMap {
        put("vmid", vmids.joinToString(","))
        put("storage", storage)
        put("mode", mode.api)
        put("compress", compression.api)
        put("remove", if (removeOlder) "1" else "0")
        if (protectBackup) put("protected", "1")
        notes?.takeIf { it.isNotBlank() }?.let { put("notes-template", it) }
        mailTo?.takeIf { it.isNotBlank() }?.let {
            put("mailto", it)
            put("mailnotification", notification.api)
        }
    }
}

/** Parameters for restoring a backup archive into a guest. */
data class RestoreRequest(
    val node: String,
    val archive: String,
    val vmid: Int,
    val type: GuestType,
    val storage: String?,
    val force: Boolean,
    val unique: Boolean,
    val startAfterRestore: Boolean,
) {
    fun toParameters(): Map<String, String> = buildMap {
        put("vmid", vmid.toString())
        put("archive", archive)
        put("restore", "1")
        if (force) put("force", "1")
        if (unique) put("unique", "1")
        if (startAfterRestore) put("start", "1")
        storage?.takeIf { it.isNotBlank() }?.let { put("storage", it) }
    }
}

/** An entry of `/cluster/backup`: a scheduled backup job, shown read-only. */
data class BackupJob(
    val id: String,
    val schedule: String?,
    val storage: String?,
    val mode: String?,
    val enabled: Boolean,
    val selection: String?,
    val comment: String?,
    val nextRun: Long?,
)
