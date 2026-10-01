package com.opendevtools.proxmox.model

/** A network interface as configured on a node (`/nodes/<n>/network`). */
data class NodeNetworkInterface(
    val name: String,
    val type: String?,
    val method: String?,
    val address: String?,
    val netmask: String?,
    val gateway: String?,
    val cidr: String?,
    val bridgePorts: String?,
    val active: Boolean,
    val autostart: Boolean,
    val comments: String?,
) {
    val key: String get() = "iface:$name"
    val isBridge: Boolean get() = type == "bridge"
}

/** A systemd unit Proxmox manages (`/nodes/<n>/services`). */
data class NodeService(
    val name: String,
    val description: String?,
    val state: String?,
    val unitState: String?,
) {
    val isRunning: Boolean get() = state == "running"
    val key: String get() = "service:$name"
}

/** A physical disk of a node (`/nodes/<n>/disks/list`). */
data class NodeDisk(
    val device: String,
    val model: String?,
    val serial: String?,
    val size: Long,
    val type: String?,
    val used: String?,
    val health: String?,
    val wearout: String?,
)

/** One row of `/nodes/<n>/apt/update`: a package with a pending upgrade. */
data class PackageUpdate(
    val name: String,
    val title: String?,
    val currentVersion: String?,
    val newVersion: String?,
    val description: String?,
)

/** A firewall rule at cluster, node or guest level. */
data class FirewallRule(
    val position: Int,
    val type: String,
    val action: String,
    val enabled: Boolean,
    val source: String?,
    val destination: String?,
    val protocol: String?,
    val sourcePort: String?,
    val destinationPort: String?,
    val interfaceName: String?,
    val macro: String?,
    val comment: String?,
    val logLevel: String?,
) {
    val key: String get() = "rule:$position"

    val summary: String
        get() = buildString {
            append(type.uppercase()).append(' ').append(action)
            macro?.takeIf { it.isNotBlank() }?.let { append(" [").append(it).append(']') }
            protocol?.takeIf { it.isNotBlank() }?.let { append(" proto=").append(it) }
            source?.takeIf { it.isNotBlank() }?.let { append(" src=").append(it) }
            destination?.takeIf { it.isNotBlank() }?.let { append(" dst=").append(it) }
            destinationPort?.takeIf { it.isNotBlank() }?.let { append(" dport=").append(it) }
        }
}

/** Firewall on/off switches and policies for one scope. */
data class FirewallOptions(val entries: Map<String, String>) {
    val enabled: Boolean get() = entries["enable"] == "1"
    val inputPolicy: String? get() = entries["policy_in"]
    val outputPolicy: String? get() = entries["policy_out"]
}

/** A user, group, role or ACL entry from `/access`. */
data class AccessUser(
    val userid: String,
    val enabled: Boolean,
    val expire: Long,
    val firstName: String?,
    val lastName: String?,
    val email: String?,
    val groups: List<String>,
    val comment: String?,
)

data class AccessAcl(
    val path: String,
    val type: String,
    val ugid: String,
    val roleid: String,
    val propagate: Boolean,
)

/** High-availability resource and its current state. */
data class HaResource(
    val sid: String,
    val type: String?,
    val state: String?,
    val group: String?,
    val node: String?,
    val requestState: String?,
    val maxRestart: Int?,
    val maxRelocate: Int?,
) {
    val key: String get() = "ha:$sid"
}

/** A storage replication job and the outcome of its last run. */
data class ReplicationJob(
    val id: String,
    val target: String?,
    val schedule: String?,
    val source: String?,
    val guest: Int?,
    val lastSync: Long?,
    val nextSync: Long?,
    val duration: Double?,
    val error: String?,
    val disabled: Boolean,
) {
    val failed: Boolean get() = !error.isNullOrBlank()
    val key: String get() = "replication:$id"
}
