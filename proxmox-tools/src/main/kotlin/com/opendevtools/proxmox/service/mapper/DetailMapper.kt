package com.opendevtools.proxmox.service.mapper

import com.google.gson.JsonObject
import com.opendevtools.core.json.array
import com.opendevtools.core.json.bool
import com.opendevtools.core.json.int
import com.opendevtools.core.json.intOrNull
import com.opendevtools.core.json.long
import com.opendevtools.core.json.longOrNull
import com.opendevtools.core.json.obj
import com.opendevtools.core.json.str
import com.opendevtools.core.json.stringArray
import com.opendevtools.proxmox.model.AccessAcl
import com.opendevtools.proxmox.model.AccessUser
import com.opendevtools.proxmox.model.BackupJob
import com.opendevtools.proxmox.model.FirewallOptions
import com.opendevtools.proxmox.model.FirewallRule
import com.opendevtools.proxmox.model.GuestConfig
import com.opendevtools.proxmox.model.GuestNetworkInterface
import com.opendevtools.proxmox.model.GuestSnapshot
import com.opendevtools.proxmox.model.GuestType
import com.opendevtools.proxmox.model.HaResource
import com.opendevtools.proxmox.model.MigrationPreconditions
import com.opendevtools.proxmox.model.NodeDisk
import com.opendevtools.proxmox.model.NodeNetworkInterface
import com.opendevtools.proxmox.model.NodeService
import com.opendevtools.proxmox.model.PackageUpdate
import com.opendevtools.proxmox.model.ProxmoxTask
import com.opendevtools.proxmox.model.ReplicationJob
import com.opendevtools.proxmox.model.RrdSeries
import com.opendevtools.proxmox.model.StorageContentType
import com.opendevtools.proxmox.model.StorageVolume
import com.opendevtools.proxmox.model.TaskLogLine

/** JSON to model for everything shown in the details tabs. */
object DetailMapper {

    // --- Guest ---------------------------------------------------------------------------------

    fun toGuestConfig(
        vmid: Int,
        type: GuestType,
        raw: JsonObject,
        pending: List<JsonObject> = emptyList(),
    ): GuestConfig {
        val entries = LinkedHashMap<String, String>()
        raw.entrySet()
            .filter { it.key != "digest" }
            .sortedBy { it.key }
            .forEach { (key, value) ->
                if (value.isJsonNull) return@forEach
                entries[key] = runCatching { value.asString }.getOrElse { value.toString() }
            }

        // A "pending" row only matters when it actually carries a queued value.
        val pendingEntries = LinkedHashMap<String, String>()
        pending.forEach { row ->
            val key = row.str("key") ?: return@forEach
            row.str("pending")?.let { pendingEntries[key] = it }
                ?: if (row.bool("delete")) pendingEntries[key] = "(will be removed)" else Unit
        }

        return GuestConfig(vmid, type, entries, raw.str("digest"), pendingEntries)
    }

    fun toSnapshot(raw: JsonObject): GuestSnapshot? {
        val name = raw.str("name") ?: return null
        return GuestSnapshot(
            name = name,
            description = raw.str("description")?.takeIf { it.isNotBlank() },
            parent = raw.str("parent")?.takeIf { it.isNotBlank() },
            createdAt = raw.long("snaptime"),
            withRam = raw.bool("vmstate"),
            running = raw.bool("running"),
        )
    }

    /**
     * The guest agent reports every interface including loopback and every address family;
     * loopback is dropped because it tells the user nothing.
     */
    fun toGuestNetworkInterfaces(raw: JsonObject?): List<GuestNetworkInterface> {
        val result = raw?.obj("result") ?: raw ?: return emptyList()
        return result.array("result").ifEmpty { raw?.array("result").orEmpty() }
            .mapNotNull { entry ->
                val name = entry.str("name") ?: return@mapNotNull null
                if (name == "lo") return@mapNotNull null
                GuestNetworkInterface(
                    name = name,
                    hardwareAddress = entry.str("hardware-address"),
                    addresses = entry.array("ip-addresses").mapNotNull { address ->
                        address.str("ip-address")?.let { ip ->
                            val prefix = address.intOrNull("prefix")
                            if (prefix != null) "$ip/$prefix" else ip
                        }
                    },
                )
            }
    }

    fun toMigrationPreconditions(raw: JsonObject?): MigrationPreconditions? {
        if (raw == null) return null
        val notAllowed = LinkedHashMap<String, String>()
        raw.obj("not_allowed_nodes")?.entrySet()?.forEach { (node, reason) ->
            notAllowed[node] = reason.toString()
        }
        return MigrationPreconditions(
            allowedNodes = raw.stringArray("allowed_nodes"),
            notAllowedNodes = notAllowed,
            localDisks = raw.array("local_disks").mapNotNull { it.str("volid") },
            localResources = raw.stringArray("local_resources"),
            running = raw.bool("running"),
        )
    }

    // --- Tasks ---------------------------------------------------------------------------------

    fun toTask(raw: JsonObject): ProxmoxTask? {
        val upid = raw.str("upid") ?: return null
        val fromUpid = ProxmoxTask.parseUpid(upid)
        return ProxmoxTask(
            upid = upid,
            node = raw.str("node") ?: fromUpid?.node ?: "",
            type = raw.str("type") ?: fromUpid?.type ?: "",
            id = raw.str("id")?.takeIf { it.isNotBlank() } ?: fromUpid?.id,
            user = raw.str("user") ?: fromUpid?.user ?: "",
            startTime = raw.longOrNull("starttime") ?: fromUpid?.startTime ?: 0,
            endTime = raw.longOrNull("endtime"),
            status = raw.str("status"),
            exitStatus = raw.str("exitstatus") ?: raw.str("status")?.takeIf { it != "running" },
        )
    }

    fun toTaskLogLine(raw: JsonObject): TaskLogLine =
        TaskLogLine(raw.int("n"), raw.str("t").orEmpty())

    // --- Storage -------------------------------------------------------------------------------

    fun toVolume(raw: JsonObject, node: String, storage: String): StorageVolume? {
        val volid = raw.str("volid") ?: return null
        return StorageVolume(
            volid = volid,
            storage = raw.str("storage") ?: storage,
            node = node,
            format = raw.str("format"),
            size = raw.long("size"),
            used = raw.long("used"),
            contentType = StorageContentType.fromApi(raw.str("content")),
            vmid = raw.intOrNull("vmid"),
            createdAt = raw.long("ctime"),
            notes = raw.str("notes")?.takeIf { it.isNotBlank() },
            protectedVolume = raw.bool("protected"),
            verification = raw.obj("verification")?.str("state"),
        )
    }

    // --- Node infrastructure --------------------------------------------------------------------

    fun toNetworkInterface(raw: JsonObject): NodeNetworkInterface? {
        val name = raw.str("iface") ?: return null
        return NodeNetworkInterface(
            name = name,
            type = raw.str("type"),
            method = raw.str("method"),
            address = raw.str("address"),
            netmask = raw.str("netmask"),
            gateway = raw.str("gateway"),
            cidr = raw.str("cidr"),
            bridgePorts = raw.str("bridge_ports"),
            active = raw.bool("active"),
            autostart = raw.bool("autostart"),
            comments = raw.str("comments")?.takeIf { it.isNotBlank() },
        )
    }

    fun toService(raw: JsonObject): NodeService? {
        val name = raw.str("name") ?: return null
        return NodeService(
            name = name,
            description = raw.str("desc"),
            state = raw.str("state"),
            unitState = raw.str("unit-state"),
        )
    }

    fun toDisk(raw: JsonObject): NodeDisk? {
        val device = raw.str("devpath") ?: return null
        return NodeDisk(
            device = device,
            model = raw.str("model"),
            serial = raw.str("serial"),
            size = raw.long("size"),
            type = raw.str("type"),
            used = raw.str("used"),
            health = raw.str("health"),
            wearout = raw.str("wearout"),
        )
    }

    fun toPackageUpdate(raw: JsonObject): PackageUpdate? {
        val name = raw.str("Package") ?: raw.str("package") ?: return null
        return PackageUpdate(
            name = name,
            title = raw.str("Title"),
            currentVersion = raw.str("OldVersion"),
            newVersion = raw.str("Version"),
            description = raw.str("Description")?.lineSequence()?.firstOrNull(),
        )
    }

    fun toReplicationJob(raw: JsonObject): ReplicationJob? {
        val id = raw.str("id") ?: return null
        return ReplicationJob(
            id = id,
            target = raw.str("target"),
            schedule = raw.str("schedule"),
            source = raw.str("source"),
            guest = raw.intOrNull("guest"),
            lastSync = raw.longOrNull("last_sync"),
            nextSync = raw.longOrNull("next_sync"),
            duration = raw.get("duration")?.let { runCatching { it.asDouble }.getOrNull() },
            error = raw.str("error")?.takeIf { it.isNotBlank() },
            disabled = raw.bool("disable"),
        )
    }

    fun toHaResource(raw: JsonObject): HaResource? {
        val sid = raw.str("sid") ?: return null
        return HaResource(
            sid = sid,
            type = raw.str("type"),
            state = raw.str("state") ?: raw.str("crm_state"),
            group = raw.str("group"),
            node = raw.str("node"),
            requestState = raw.str("request_state"),
            maxRestart = raw.intOrNull("max_restart"),
            maxRelocate = raw.intOrNull("max_relocate"),
        )
    }

    fun toBackupJob(raw: JsonObject): BackupJob? {
        val id = raw.str("id") ?: return null
        return BackupJob(
            id = id,
            schedule = raw.str("schedule") ?: raw.str("starttime"),
            storage = raw.str("storage"),
            mode = raw.str("mode"),
            enabled = raw.str("enabled") != "0",
            selection = raw.str("vmid") ?: raw.str("selection") ?: raw.str("all")?.let { "all" },
            comment = raw.str("comment")?.takeIf { it.isNotBlank() },
            nextRun = raw.longOrNull("next-run"),
        )
    }

    // --- Firewall ------------------------------------------------------------------------------

    fun toFirewallRule(raw: JsonObject): FirewallRule = FirewallRule(
        position = raw.int("pos"),
        type = raw.str("type") ?: "in",
        action = raw.str("action") ?: "ACCEPT",
        enabled = raw.str("enable") != "0",
        source = raw.str("source"),
        destination = raw.str("dest"),
        protocol = raw.str("proto"),
        sourcePort = raw.str("sport"),
        destinationPort = raw.str("dport"),
        interfaceName = raw.str("iface"),
        macro = raw.str("macro"),
        comment = raw.str("comment")?.takeIf { it.isNotBlank() },
        logLevel = raw.str("log"),
    )

    fun toFirewallOptions(raw: JsonObject?): FirewallOptions {
        if (raw == null) return FirewallOptions(emptyMap())
        val entries = LinkedHashMap<String, String>()
        raw.entrySet().sortedBy { it.key }.forEach { (key, value) ->
            if (!value.isJsonNull) {
                entries[key] = runCatching { value.asString }.getOrElse { value.toString() }
            }
        }
        return FirewallOptions(entries)
    }

    // --- Access --------------------------------------------------------------------------------

    fun toUser(raw: JsonObject): AccessUser? {
        val userid = raw.str("userid") ?: return null
        return AccessUser(
            userid = userid,
            enabled = raw.str("enable") != "0",
            expire = raw.long("expire"),
            firstName = raw.str("firstname"),
            lastName = raw.str("lastname"),
            email = raw.str("email"),
            groups = raw.str("groups").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
            comment = raw.str("comment")?.takeIf { it.isNotBlank() },
        )
    }

    fun toAcl(raw: JsonObject): AccessAcl? {
        val path = raw.str("path") ?: return null
        return AccessAcl(
            path = path,
            type = raw.str("type") ?: "user",
            ugid = raw.str("ugid").orEmpty(),
            roleid = raw.str("roleid").orEmpty(),
            propagate = raw.bool("propagate"),
        )
    }

    // --- RRD -----------------------------------------------------------------------------------

    fun toRrdSeries(rows: List<JsonObject>): RrdSeries {
        if (rows.isEmpty()) return RrdSeries.EMPTY
        val samples = rows.map { row ->
            val sample = LinkedHashMap<String, Double?>()
            row.entrySet().forEach { (key, value) ->
                sample[key] = if (value.isJsonNull) null else runCatching { value.asDouble }.getOrNull()
            }
            sample
        }
        return RrdSeries(samples)
    }
}
