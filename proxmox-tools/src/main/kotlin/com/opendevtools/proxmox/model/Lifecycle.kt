package com.opendevtools.proxmox.model

/** A full clone copies every disk; a linked clone only works from a template. */
enum class CloneMode(val title: String, val hint: String) {
    LINKED("Linked clone", "Shares the template's disks; only possible from a template"),
    FULL("Full clone", "Copies every disk; the clone is independent"),
}

data class CloneRequest(
    val source: ProxmoxGuest,
    val newVmid: Int,
    val name: String,
    val targetNode: String?,
    val pool: String?,
    val description: String?,
    val mode: CloneMode,
    val targetStorage: String?,
    val snapshotName: String?,
) {
    fun toParameters(): Map<String, String> = buildMap {
        put("newid", newVmid.toString())
        name.takeIf { it.isNotBlank() }?.let { put(if (source.type == GuestType.QEMU) "name" else "hostname", it) }
        put("full", if (mode == CloneMode.FULL) "1" else "0")
        targetNode?.takeIf { it != source.node }?.let { put("target", it) }
        pool?.takeIf { it.isNotBlank() }?.let { put("pool", it) }
        description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
        // Proxmox rejects a storage for a linked clone, since nothing is copied.
        if (mode == CloneMode.FULL) targetStorage?.takeIf { it.isNotBlank() }?.let { put("storage", it) }
        snapshotName?.takeIf { it.isNotBlank() }?.let { put("snapname", it) }
    }
}

data class MigrateRequest(
    val guest: ProxmoxGuest,
    val targetNode: String,
    val online: Boolean,
    val withLocalDisks: Boolean,
    val targetStorage: String?,
    val force: Boolean,
) {
    fun toParameters(): Map<String, String> = buildMap {
        put("target", targetNode)
        when (guest.type) {
            GuestType.QEMU -> {
                if (online) put("online", "1")
                if (withLocalDisks) put("with-local-disks", "1")
            }
            // Containers cannot migrate live; the API calls the same idea "restart migration".
            GuestType.LXC -> if (online) put("restart", "1")
        }
        targetStorage?.takeIf { it.isNotBlank() }?.let { put("targetstorage", it) }
        if (force) put("force", "1")
    }
}

/** Reported by `GET .../migrate`: whether a migration is possible and what is in the way. */
data class MigrationPreconditions(
    val allowedNodes: List<String>,
    val notAllowedNodes: Map<String, String>,
    val localDisks: List<String>,
    val localResources: List<String>,
    val running: Boolean,
) {
    val hasBlockers: Boolean get() = localResources.isNotEmpty()
}

/** Parameters of the "Create Virtual Machine" wizard. */
data class CreateVmRequest(
    val node: String,
    val vmid: Int,
    val name: String,
    val cores: Int,
    val sockets: Int,
    val memoryMib: Int,
    val diskGib: Int,
    val diskStorage: String,
    val isoVolume: String?,
    val bridge: String,
    val vlanTag: Int?,
    val osType: String,
    val scsiHw: String,
    val bios: String,
    val machine: String?,
    val startAfterCreate: Boolean,
    val agent: Boolean,
    val pool: String?,
    val description: String?,
) {
    fun toParameters(): Map<String, String> = buildMap {
        put("vmid", vmid.toString())
        put("name", name)
        put("cores", cores.toString())
        put("sockets", sockets.toString())
        put("memory", memoryMib.toString())
        put("ostype", osType)
        put("scsihw", scsiHw)
        put("bios", bios)
        put("scsi0", "$diskStorage:$diskGib")
        put("net0", "virtio,bridge=$bridge" + (vlanTag?.let { ",tag=$it" }.orEmpty()))
        if (agent) put("agent", "1")
        if (startAfterCreate) put("start", "1")
        machine?.takeIf { it.isNotBlank() }?.let { put("machine", it) }
        isoVolume?.takeIf { it.isNotBlank() }?.let { put("ide2", "$it,media=cdrom") }
        pool?.takeIf { it.isNotBlank() }?.let { put("pool", it) }
        description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
    }
}

/** Parameters of the "Create Container" wizard. */
data class CreateContainerRequest(
    val node: String,
    val vmid: Int,
    val hostname: String,
    val template: String,
    val password: String?,
    val sshPublicKey: String?,
    val cores: Int,
    val memoryMib: Int,
    val swapMib: Int,
    val diskGib: Int,
    val diskStorage: String,
    val bridge: String,
    val ipv4: String,
    val gateway: String?,
    val vlanTag: Int?,
    val nesting: Boolean,
    val unprivileged: Boolean,
    val startAfterCreate: Boolean,
    val pool: String?,
    val description: String?,
) {
    fun toParameters(): Map<String, String> = buildMap {
        put("vmid", vmid.toString())
        put("hostname", hostname)
        put("ostemplate", template)
        put("cores", cores.toString())
        put("memory", memoryMib.toString())
        put("swap", swapMib.toString())
        put("rootfs", "$diskStorage:$diskGib")
        put("unprivileged", if (unprivileged) "1" else "0")
        put("features", if (nesting) "nesting=1" else "nesting=0")
        val net = buildString {
            append("name=eth0,bridge=").append(bridge)
            append(",ip=").append(ipv4.ifBlank { "dhcp" })
            gateway?.takeIf { it.isNotBlank() && ipv4 != "dhcp" }?.let { append(",gw=").append(it) }
            vlanTag?.let { append(",tag=").append(it) }
        }
        put("net0", net)
        password?.takeIf { it.isNotBlank() }?.let { put("password", it) }
        sshPublicKey?.takeIf { it.isNotBlank() }?.let { put("ssh-public-keys", it) }
        if (startAfterCreate) put("start", "1")
        pool?.takeIf { it.isNotBlank() }?.let { put("pool", it) }
        description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
    }
}
