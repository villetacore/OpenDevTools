package com.opendevtools.proxmox.model

/** The two guest flavours Proxmox manages; almost every endpoint path contains one of them. */
enum class GuestType(
    val api: String,
    val consoleType: String,
    val groupTitle: String,
    val label: String,
) {
    QEMU("qemu", "kvm", "Virtual Machines", "QEMU/KVM virtual machine"),
    LXC("lxc", "lxc", "Containers", "LXC container");

    companion object {
        fun fromApi(value: String?): GuestType? = entries.firstOrNull { it.api == value }
    }
}

/** Guest run states as reported by `status/current`. */
enum class GuestStatus(val api: String, val display: String) {
    RUNNING("running", "running"),
    STOPPED("stopped", "stopped"),
    PAUSED("paused", "paused"),
    SUSPENDED("suspended", "suspended"),
    UNKNOWN("unknown", "unknown");

    companion object {
        fun fromApi(value: String?): GuestStatus =
            entries.firstOrNull { it.api == value } ?: UNKNOWN
    }
}

/**
 * A power transition. [requiresConfirmation] marks the ones that can interrupt a workload,
 * [supports] encodes which guest flavours accept the action at all.
 */
enum class GuestAction(
    val api: String,
    val title: String,
    val requiresConfirmation: Boolean,
    private val supports: Set<GuestType> = setOf(GuestType.QEMU, GuestType.LXC),
) {
    START("start", "Start", false),
    SHUTDOWN("shutdown", "Shut Down", true),
    STOP("stop", "Stop (Kill)", true),
    REBOOT("reboot", "Reboot", true),
    RESET("reset", "Reset", true, setOf(GuestType.QEMU)),
    SUSPEND("suspend", "Suspend", false, setOf(GuestType.QEMU)),
    RESUME("resume", "Resume", false);

    fun isApplicable(guest: ProxmoxGuest): Boolean {
        if (guest.type !in supports) return false
        if (guest.template) return false
        if (guest.lock != null) return false
        return when (this) {
            START -> !guest.isRunning
            RESUME -> guest.status == GuestStatus.PAUSED || guest.status == GuestStatus.SUSPENDED
            SUSPEND -> guest.isRunning
            else -> guest.isRunning
        }
    }
}

/** A row of `/cluster/resources`, enriched with whatever `status/current` adds on demand. */
data class ProxmoxGuest(
    val vmid: Int,
    val name: String,
    val type: GuestType,
    val node: String,
    val status: GuestStatus,
    val cpu: Double,
    val maxCpu: Int,
    val mem: Long,
    val maxMem: Long,
    val disk: Long,
    val maxDisk: Long,
    val diskRead: Long = 0,
    val diskWrite: Long = 0,
    val netIn: Long = 0,
    val netOut: Long = 0,
    val uptime: Long,
    val template: Boolean,
    val lock: String?,
    val tags: List<String>,
    val pool: String?,
    val haState: String? = null,
    val agentEnabled: Boolean = false,
) {
    val isRunning: Boolean get() = status == GuestStatus.RUNNING
    val displayName: String get() = "$vmid  $name"
    val id: String get() = "${type.api}/$vmid"

    /** Stable identity across refreshes; a guest keeps it even after a migration. */
    val key: String get() = "guest:${type.api}:$vmid"

    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        val needle = query.trim().lowercase()
        return name.lowercase().contains(needle) ||
            vmid.toString().contains(needle) ||
            node.lowercase().contains(needle) ||
            tags.any { it.lowercase().contains(needle) } ||
            pool?.lowercase()?.contains(needle) == true
    }
}

/** The `qemu/<id>/config` or `lxc/<id>/config` document, kept as ordered key/value pairs. */
data class GuestConfig(
    val vmid: Int,
    val type: GuestType,
    val entries: Map<String, String>,
    val digest: String?,
    val pending: Map<String, String> = emptyMap(),
) {
    val description: String? get() = entries["description"]
    val bootDisk: String? get() = entries["bootdisk"]
    val osType: String? get() = entries["ostype"]

    /** Disk-like entries: `scsi0`, `virtio1`, `ide2`, `rootfs`, `mp0`, ... */
    fun disks(): Map<String, String> = entries.filterKeys { DISK_KEY.matches(it) }

    /** Network entries: `net0`, `net1`, ... */
    fun networks(): Map<String, String> = entries.filterKeys { NET_KEY.matches(it) }

    fun unusedDisks(): Map<String, String> = entries.filterKeys { it.startsWith("unused") }

    fun hasPendingChanges(): Boolean = pending.isNotEmpty()

    companion object {
        private val DISK_KEY = Regex("^(scsi|virtio|sata|ide|efidisk|tpmstate|rootfs|mp)\\d*$")
        private val NET_KEY = Regex("^net\\d+$")

        /**
         * Proxmox packs a whole device into one string: `local-lvm:vm-100-disk-0,size=32G,ssd=1`.
         * The first segment has no key when it is the volume itself.
         */
        fun parseDevice(value: String): LinkedHashMap<String, String> {
            val result = LinkedHashMap<String, String>()
            value.split(',').forEachIndexed { index, part ->
                val trimmed = part.trim()
                if (trimmed.isEmpty()) return@forEachIndexed
                val eq = trimmed.indexOf('=')
                if (eq <= 0) {
                    result[if (index == 0) "volume" else trimmed] = trimmed
                } else {
                    result[trimmed.substring(0, eq)] = trimmed.substring(eq + 1)
                }
            }
            return result
        }
    }
}

/** One interface as reported by the QEMU guest agent, used to show a running guest's addresses. */
data class GuestNetworkInterface(
    val name: String,
    val hardwareAddress: String?,
    val addresses: List<String>,
)
