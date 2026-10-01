package com.opendevtools.proxmox.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.opendevtools.core.json.obj
import com.opendevtools.core.json.str
import com.opendevtools.proxmox.api.ProxmoxClient
import com.opendevtools.proxmox.api.ProxmoxException
import com.opendevtools.proxmox.model.ClusterSnapshot
import com.opendevtools.proxmox.model.FirewallOptions
import com.opendevtools.proxmox.model.FirewallRule
import com.opendevtools.proxmox.model.GuestConfig
import com.opendevtools.proxmox.model.GuestNetworkInterface
import com.opendevtools.proxmox.model.GuestSnapshot
import com.opendevtools.proxmox.model.GuestType
import com.opendevtools.proxmox.model.NodeDisk
import com.opendevtools.proxmox.model.NodeNetworkInterface
import com.opendevtools.proxmox.model.NodeService
import com.opendevtools.proxmox.model.PackageUpdate
import com.opendevtools.proxmox.model.ProxmoxGuest
import com.opendevtools.proxmox.model.ProxmoxTask
import com.opendevtools.proxmox.model.ReplicationJob
import com.opendevtools.proxmox.model.RrdConsolidation
import com.opendevtools.proxmox.model.RrdSeries
import com.opendevtools.proxmox.model.RrdTimeframe
import com.opendevtools.proxmox.model.StorageContentType
import com.opendevtools.proxmox.model.StorageVolume
import com.opendevtools.proxmox.model.TaskLogLine
import com.opendevtools.proxmox.service.mapper.ClusterMapper
import com.opendevtools.proxmox.service.mapper.DetailMapper
import com.opendevtools.proxmox.settings.ProxmoxConnectionConfig
import com.opendevtools.proxmox.settings.ProxmoxSettings

/**
 * Owns the [ProxmoxClient] and maps API responses onto the plugin model. The tool window talks
 * only to this service, which keeps the JSON layer out of the UI entirely.
 *
 * All methods block and must be called from a background thread.
 */
@Service(Service.Level.APP)
class ProxmoxService {

    @Volatile
    private var cached: ProxmoxClient? = null

    @Volatile
    private var lastSnapshot: ClusterSnapshot? = null

    fun client(): ProxmoxClient {
        val config = ProxmoxSettings.getInstance().toConfig()
        if (!config.isComplete) throw ProxmoxException("Proxmox connection is not configured")
        val current = cached
        if (current != null && current.config == config) return current
        return ProxmoxClient(config).also { cached = it }
    }

    fun clientFor(config: ProxmoxConnectionConfig): ProxmoxClient = ProxmoxClient(config)

    fun cachedSnapshot(): ClusterSnapshot? = lastSnapshot

    fun invalidate() {
        cached = null
        lastSnapshot = null
    }

    // --- Cluster overview ----------------------------------------------------------------------

    /**
     * One `/cluster/resources` call plus a few cheap extras. Every optional call is wrapped,
     * because a restricted API token may be allowed to list guests but not pools or the
     * cluster status, and losing the whole tree over that would be unhelpful.
     */
    fun loadCluster(): ClusterSnapshot {
        val client = client()
        val resources = client.cluster.resources()

        val guestsByNode = resources.mapNotNull { ClusterMapper.toGuest(it) }.groupBy { it.node }
        val storagesByNode = resources
            .filter { it.str("type") == "storage" }
            .mapNotNull { ClusterMapper.toStorage(it) }
            .groupBy { it.node }

        val nodeRows = resources.filter { it.str("type") == "node" }
            .ifEmpty { runCatching { client.nodes.list() }.getOrDefault(emptyList()) }

        val nodes = nodeRows
            .mapNotNull { raw ->
                val name = raw.str("node") ?: raw.str("name") ?: return@mapNotNull null
                ClusterMapper.toNode(
                    raw,
                    guestsByNode[name].orEmpty().sortedBy { it.vmid },
                    storagesByNode[name].orEmpty().sortedBy { it.name },
                )
            }
            .sortedBy { it.name }

        val statusRows = runCatching { client.cluster.status() }.getOrDefault(emptyList())
        val snapshot = ClusterSnapshot(
            nodes = nodes,
            pools = runCatching { client.cluster.pools().mapNotNull { ClusterMapper.toPool(it) } }
                .getOrDefault(emptyList())
                .sortedBy { it.id },
            quorum = ClusterMapper.toQuorum(statusRows),
            version = runCatching { client.version() }.getOrNull(),
            runningTasks = runCatching {
                client.cluster.tasks(limit = 50)
                    .mapNotNull { DetailMapper.toTask(it) }
                    .filter { it.isRunning }
            }.getOrDefault(emptyList()),
        )
        lastSnapshot = snapshot
        return snapshot
    }

    // --- Guest details --------------------------------------------------------------------------

    fun guestConfig(guest: ProxmoxGuest): GuestConfig {
        val client = client()
        val raw = client.guests.config(guest.node, guest.type, guest.vmid)
            ?: throw ProxmoxException("The server returned no configuration for ${guest.displayName}")
        val pending = runCatching { client.guests.pendingConfig(guest.node, guest.type, guest.vmid) }
            .getOrDefault(emptyList())
        return DetailMapper.toGuestConfig(guest.vmid, guest.type, raw, pending)
    }

    fun guestStatus(guest: ProxmoxGuest): Map<String, String> {
        val raw = client().guests.status(guest.node, guest.type, guest.vmid) ?: return emptyMap()
        return raw.entrySet()
            .filterNot { it.value.isJsonNull }
            .sortedBy { it.key }
            .associate { (key, value) ->
                key to runCatching { value.asString }.getOrElse { value.toString() }
            }
    }

    fun snapshots(guest: ProxmoxGuest): List<GuestSnapshot> =
        client().guests.snapshots(guest.node, guest.type, guest.vmid)
            .mapNotNull { DetailMapper.toSnapshot(it) }
            .sortedBy { if (it.isCurrentState) Long.MAX_VALUE else it.createdAt }

    /** Empty when the guest is not a QEMU VM, is not running, or has no agent installed. */
    fun guestNetworkInterfaces(guest: ProxmoxGuest): List<GuestNetworkInterface> {
        if (guest.type != GuestType.QEMU || !guest.isRunning) return emptyList()
        return runCatching {
            DetailMapper.toGuestNetworkInterfaces(
                client().guests.agentNetworkInterfaces(guest.node, guest.vmid),
            )
        }.getOrDefault(emptyList())
    }

    fun guestFirewallRules(guest: ProxmoxGuest): List<FirewallRule> =
        runCatching {
            client().guests.firewallRules(guest.node, guest.type, guest.vmid)
                .map { DetailMapper.toFirewallRule(it) }
                .sortedBy { it.position }
        }.getOrDefault(emptyList())

    fun guestFirewallOptions(guest: ProxmoxGuest): FirewallOptions =
        runCatching {
            DetailMapper.toFirewallOptions(
                client().guests.firewallOptions(guest.node, guest.type, guest.vmid),
            )
        }.getOrDefault(FirewallOptions(emptyMap()))

    fun guestRrd(guest: ProxmoxGuest, timeframe: RrdTimeframe, cf: RrdConsolidation): RrdSeries =
        runCatching {
            DetailMapper.toRrdSeries(
                client().guests.rrdData(guest.node, guest.type, guest.vmid, timeframe, cf),
            )
        }.getOrDefault(RrdSeries.EMPTY)

    /** Backup archives of one guest, gathered from every storage on its node that holds backups. */
    fun guestBackups(guest: ProxmoxGuest): List<StorageVolume> {
        val client = client()
        val storages = runCatching {
            client.storages.list(guest.node, StorageContentType.BACKUP)
        }.getOrDefault(emptyList()).mapNotNull { it.str("storage") }

        return storages.flatMap { storage ->
            runCatching {
                client.storages.contentForGuest(guest.node, storage, guest.vmid)
                    .mapNotNull { DetailMapper.toVolume(it, guest.node, storage) }
            }.getOrDefault(emptyList())
        }.sortedByDescending { it.createdAt }
    }

    // --- Node details ---------------------------------------------------------------------------

    fun nodeStatus(node: String): Map<String, String> {
        val raw = client().nodes.status(node) ?: return emptyMap()
        val flat = LinkedHashMap<String, String>()
        fun flatten(prefix: String, obj: com.google.gson.JsonObject) {
            obj.entrySet().sortedBy { it.key }.forEach { (key, value) ->
                val name = if (prefix.isEmpty()) key else "$prefix.$key"
                when {
                    value.isJsonObject -> flatten(name, value.asJsonObject)
                    value.isJsonNull -> Unit
                    else -> flat[name] = runCatching { value.asString }.getOrElse { value.toString() }
                }
            }
        }
        flatten("", raw)
        return flat
    }

    fun nodeNetwork(node: String): List<NodeNetworkInterface> =
        runCatching {
            client().nodes.network(node)
                .mapNotNull { DetailMapper.toNetworkInterface(it) }
                .sortedBy { it.name }
        }.getOrDefault(emptyList())

    fun nodeServices(node: String): List<NodeService> =
        runCatching {
            client().nodes.services(node).mapNotNull { DetailMapper.toService(it) }.sortedBy { it.name }
        }.getOrDefault(emptyList())

    fun nodeDisks(node: String): List<NodeDisk> =
        runCatching {
            client().nodes.disks(node).mapNotNull { DetailMapper.toDisk(it) }.sortedBy { it.device }
        }.getOrDefault(emptyList())

    fun nodeUpdates(node: String): List<PackageUpdate> =
        runCatching {
            client().nodes.availableUpdates(node)
                .mapNotNull { DetailMapper.toPackageUpdate(it) }
                .sortedBy { it.name }
        }.getOrDefault(emptyList())

    fun nodeSyslog(node: String, limit: Int = 500): List<String> =
        runCatching {
            client().nodes.syslog(node, limit).mapNotNull { it.str("t") }
        }.getOrDefault(emptyList())

    fun nodeReplication(node: String): List<ReplicationJob> =
        runCatching {
            client().nodes.replicationStatus(node).mapNotNull { DetailMapper.toReplicationJob(it) }
        }.getOrDefault(emptyList())

    fun nodeRrd(node: String, timeframe: RrdTimeframe, cf: RrdConsolidation): RrdSeries =
        runCatching {
            DetailMapper.toRrdSeries(client().nodes.rrdData(node, timeframe, cf))
        }.getOrDefault(RrdSeries.EMPTY)

    // --- Storage ---------------------------------------------------------------------------------

    fun storageContent(
        node: String,
        storage: String,
        contentType: StorageContentType? = null,
    ): List<StorageVolume> =
        client().storages.content(node, storage, contentType)
            .mapNotNull { DetailMapper.toVolume(it, node, storage) }
            .sortedWith(compareBy({ it.contentType?.api }, { -it.createdAt }, { it.fileName }))

    /** Storages on [node] that can hold [contentType], for the storage pickers in the dialogs. */
    fun storagesFor(node: String, contentType: StorageContentType): List<String> =
        runCatching {
            client().storages.list(node, contentType).mapNotNull { it.str("storage") }.sorted()
        }.getOrDefault(emptyList())

    fun bridgesOn(node: String): List<String> =
        runCatching {
            client().nodes.bridges(node).mapNotNull { it.str("iface") }.sorted()
        }.getOrDefault(listOf("vmbr0"))

    // --- Datacenter-wide configuration ----------------------------------------------------------

    fun haResources(): List<com.opendevtools.proxmox.model.HaResource> {
        val client = client()
        // `resources` is the configuration and `status/current` the live state; a resource that
        // appears only in the status view is still worth showing.
        val configured = runCatching { client.cluster.haResources() }.getOrDefault(emptyList())
        val current = runCatching { client.cluster.haStatus() }.getOrDefault(emptyList())
        return (configured + current)
            .mapNotNull { DetailMapper.toHaResource(it) }
            .distinctBy { it.sid }
            .sortedBy { it.sid }
    }

    fun backupJobs(): List<com.opendevtools.proxmox.model.BackupJob> =
        client().cluster.backupJobs().mapNotNull { DetailMapper.toBackupJob(it) }.sortedBy { it.id }

    fun accessUsers(): List<com.opendevtools.proxmox.model.AccessUser> =
        client().access.users().mapNotNull { DetailMapper.toUser(it) }.sortedBy { it.userid }

    fun accessAcl(): List<com.opendevtools.proxmox.model.AccessAcl> =
        runCatching {
            client().access.acl()
                .mapNotNull { DetailMapper.toAcl(it) }
                .sortedWith(compareBy({ it.path }, { it.ugid }))
        }.getOrDefault(emptyList())

    fun clusterFirewallRules(): List<FirewallRule> =
        client().cluster.firewallRules().map { DetailMapper.toFirewallRule(it) }.sortedBy { it.position }

    fun clusterFirewallOptions(): FirewallOptions =
        runCatching { DetailMapper.toFirewallOptions(client().cluster.firewallOptions()) }
            .getOrDefault(FirewallOptions(emptyMap()))

    // --- Tasks -------------------------------------------------------------------------------------

    fun tasks(node: String, limit: Int, errorsOnly: Boolean, vmid: Int?): List<ProxmoxTask> =
        client().nodes.tasks(node, limit = limit, errorsOnly = errorsOnly, vmid = vmid)
            .mapNotNull { DetailMapper.toTask(it) }

    fun clusterTasks(limit: Int): List<ProxmoxTask> =
        client().cluster.tasks(limit).mapNotNull { DetailMapper.toTask(it) }

    fun taskLog(node: String, upid: String, limit: Int = 2000): List<TaskLogLine> =
        client().nodes.taskLog(node, upid, limit = limit).map { DetailMapper.toTaskLogLine(it) }

    fun taskStatus(node: String, upid: String): ProxmoxTask? =
        client().nodes.taskStatus(node, upid)?.let { raw ->
            DetailMapper.toTask(raw.apply { if (!has("upid")) addProperty("upid", upid) })
        }

    fun stopTask(node: String, upid: String) = client().nodes.stopTask(node, upid)

    // --- Connection check -----------------------------------------------------------------------

    fun describeConnection(config: ProxmoxConnectionConfig): String {
        val client = clientFor(config)
        val version = client.version() ?: "unknown"
        val nodes = client.nodes.list()
        val online = nodes.count { it.str("status") == "online" }
        val permissions = runCatching { client.access.permissions() }.getOrNull()
        val scope = permissions?.obj("/")?.entrySet()?.size?.let { " Root privileges: $it." }.orEmpty()
        return "Connected to Proxmox VE $version.\n${nodes.size} node(s) visible, $online online.$scope"
    }

    companion object {
        fun getInstance(): ProxmoxService =
            ApplicationManager.getApplication().getService(ProxmoxService::class.java)
    }
}
