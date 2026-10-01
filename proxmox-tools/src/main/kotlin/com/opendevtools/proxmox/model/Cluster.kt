package com.opendevtools.proxmox.model

/** A cluster member, with the guests and storages that live on it. */
data class ProxmoxNode(
    val name: String,
    val status: String,
    val cpu: Double,
    val maxCpu: Int,
    val mem: Long,
    val maxMem: Long,
    val disk: Long,
    val maxDisk: Long,
    val uptime: Long,
    val level: String? = null,
    val sslFingerprint: String? = null,
    val guests: List<ProxmoxGuest> = emptyList(),
    val storages: List<ProxmoxStorage> = emptyList(),
) {
    val isOnline: Boolean get() = status == "online"
    val key: String get() = "node:$name"
    val runningGuests: Int get() = guests.count { it.isRunning }
}

/** Quorum information from `/cluster/status`; absent on a stand-alone node. */
data class ClusterQuorum(
    val name: String,
    val quorate: Boolean,
    val nodes: Int,
    val version: Int,
)

/** A resource pool, which is how the web UI groups guests independently of nodes. */
data class ProxmoxPool(
    val id: String,
    val comment: String?,
    val members: List<String> = emptyList(),
)

/** Everything one refresh of the tool window needs, so the UI never issues follow-up calls. */
data class ClusterSnapshot(
    val nodes: List<ProxmoxNode>,
    val pools: List<ProxmoxPool> = emptyList(),
    val quorum: ClusterQuorum? = null,
    val version: String? = null,
    val runningTasks: List<ProxmoxTask> = emptyList(),
    val loadedAt: Long = System.currentTimeMillis(),
) {
    val allGuests: List<ProxmoxGuest> get() = nodes.flatMap { it.guests }

    val allTags: List<String>
        get() = allGuests.flatMap { it.tags }.distinct().sorted()

    fun guest(type: GuestType, vmid: Int): ProxmoxGuest? =
        allGuests.firstOrNull { it.type == type && it.vmid == vmid }

    fun node(name: String): ProxmoxNode? = nodes.firstOrNull { it.name == name }

    companion object {
        val EMPTY = ClusterSnapshot(emptyList())
    }
}

/** How the tree groups guests; the web UI offers the same three views. */
enum class TreeGrouping(val title: String) {
    BY_NODE("Server View"),
    BY_POOL("Pool View"),
    BY_TAG("Tag View"),
    FLAT("Flat List"),
}
