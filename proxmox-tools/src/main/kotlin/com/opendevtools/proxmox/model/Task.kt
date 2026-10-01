package com.opendevtools.proxmox.model

/**
 * A Proxmox task. The UPID encodes node, PID, start time, type, id and user; the server
 * accepts it as an opaque handle, and the parsed pieces are only used for display.
 */
data class ProxmoxTask(
    val upid: String,
    val node: String,
    val type: String,
    val id: String?,
    val user: String,
    val startTime: Long,
    val endTime: Long?,
    val status: String?,
    val exitStatus: String?,
) {
    val isRunning: Boolean get() = status == "running" || (endTime == null && exitStatus == null)

    val succeeded: Boolean get() = exitStatus == "OK" || exitStatus?.startsWith("WARNINGS") == true

    val failed: Boolean get() = !isRunning && exitStatus != null && !succeeded

    val key: String get() = "task:$upid"

    val durationSeconds: Long
        get() = ((endTime ?: (System.currentTimeMillis() / 1000)) - startTime).coerceAtLeast(0)

    /** `qmstart`, `vzdump`, `qmigrate`, ... rendered the way the web UI labels them. */
    val description: String
        get() = (TYPE_LABELS[type] ?: type) + (id?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty())

    companion object {
        private val TYPE_LABELS = mapOf(
            "qmstart" to "Start VM",
            "qmstop" to "Stop VM",
            "qmshutdown" to "Shutdown VM",
            "qmreboot" to "Reboot VM",
            "qmreset" to "Reset VM",
            "qmsuspend" to "Suspend VM",
            "qmresume" to "Resume VM",
            "qmclone" to "Clone VM",
            "qmigrate" to "Migrate VM",
            "qmdestroy" to "Destroy VM",
            "qmcreate" to "Create VM",
            "qmconfig" to "Configure VM",
            "qmsnapshot" to "Snapshot VM",
            "qmdelsnapshot" to "Delete VM snapshot",
            "qmrollback" to "Rollback VM",
            "qmtemplate" to "Convert VM to template",
            "vzstart" to "Start CT",
            "vzstop" to "Stop CT",
            "vzshutdown" to "Shutdown CT",
            "vzreboot" to "Reboot CT",
            "vzclone" to "Clone CT",
            "vzmigrate" to "Migrate CT",
            "vzdestroy" to "Destroy CT",
            "vzcreate" to "Create CT",
            "vzsnapshot" to "Snapshot CT",
            "vzdelsnapshot" to "Delete CT snapshot",
            "vzrollback" to "Rollback CT",
            "vztemplate" to "Convert CT to template",
            "vzdump" to "Backup",
            "vzrestore" to "Restore CT",
            "qmrestore" to "Restore VM",
            "imgcopy" to "Copy disk image",
            "imgdel" to "Delete disk image",
            "resize" to "Resize disk",
            "download" to "Download file",
            "srvstart" to "Start service",
            "srvstop" to "Stop service",
            "srvrestart" to "Restart service",
            "aptupdate" to "Update package index",
            "startall" to "Start all guests",
            "stopall" to "Stop all guests",
            "migrateall" to "Migrate all guests",
        )

        /**
         * UPID format:
         * `UPID:<node>:<pid>:<pstart>:<starttime>:<type>:<id>:<user>:`
         * All numeric parts are hexadecimal.
         */
        fun parseUpid(upid: String): ProxmoxTask? {
            val parts = upid.split(':')
            if (parts.size < 8 || parts[0] != "UPID") return null
            val startTime = parts[4].toLongOrNull(16) ?: return null
            return ProxmoxTask(
                upid = upid,
                node = parts[1],
                type = parts[5],
                id = parts[6].takeIf { it.isNotBlank() },
                user = parts[7],
                startTime = startTime,
                endTime = null,
                status = "running",
                exitStatus = null,
            )
        }
    }
}

/** One line of a task log, as returned by `tasks/<upid>/log`. */
data class TaskLogLine(val number: Int, val text: String)
