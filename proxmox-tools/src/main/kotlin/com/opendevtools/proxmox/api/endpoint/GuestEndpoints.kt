package com.opendevtools.proxmox.api.endpoint

import com.google.gson.JsonObject
import com.opendevtools.core.http.encodePathSegment
import com.opendevtools.core.json.asObjectOrNull
import com.opendevtools.proxmox.api.ProxmoxTransport
import com.opendevtools.proxmox.model.CloneRequest
import com.opendevtools.proxmox.model.GuestType
import com.opendevtools.proxmox.model.MigrateRequest
import com.opendevtools.proxmox.model.RrdConsolidation
import com.opendevtools.proxmox.model.RrdTimeframe

/**
 * `/nodes/<node>/{qemu|lxc}/<vmid>/...`. QEMU and LXC expose almost the same surface, so the
 * flavour is a parameter rather than a separate class; the few divergences are noted per method.
 */
class GuestEndpoints(private val transport: ProxmoxTransport) {

    private fun base(node: String, type: GuestType, vmid: Int) = "/nodes/$node/${type.api}/$vmid"

    fun list(node: String, type: GuestType): List<JsonObject> =
        transport.getObjects("/nodes/$node/${type.api}")

    fun status(node: String, type: GuestType, vmid: Int): JsonObject? =
        transport.getObject(base(node, type, vmid) + "/status/current")

    // --- Power -------------------------------------------------------------------------------

    fun powerAction(
        node: String,
        type: GuestType,
        vmid: Int,
        action: String,
        parameters: Map<String, String> = emptyMap(),
    ): String? = transport.postForUpid(base(node, type, vmid) + "/status/$action", parameters)

    // --- Configuration -----------------------------------------------------------------------

    fun config(node: String, type: GuestType, vmid: Int): JsonObject? =
        transport.getObject(base(node, type, vmid) + "/config")

    /** The same document with pending changes applied, so the UI can show what a reboot will do. */
    fun pendingConfig(node: String, type: GuestType, vmid: Int): List<JsonObject> =
        transport.getObjects(base(node, type, vmid) + "/pending")

    /**
     * Applies configuration changes. A running QEMU guest queues most of them, which is why the
     * caller may need to warn about a pending reboot afterwards.
     */
    fun updateConfig(
        node: String,
        type: GuestType,
        vmid: Int,
        parameters: Map<String, String>,
    ): String? = transport.putForUpid(base(node, type, vmid) + "/config", parameters)

    /** Removes configuration keys; Proxmox takes them as a comma-separated `delete` list. */
    fun deleteConfigKeys(node: String, type: GuestType, vmid: Int, keys: List<String>): String? =
        updateConfig(node, type, vmid, mapOf("delete" to keys.joinToString(",")))

    fun resizeDisk(node: String, type: GuestType, vmid: Int, disk: String, size: String): String? =
        transport.putForUpid(
            base(node, type, vmid) + "/resize",
            mapOf("disk" to disk, "size" to size),
        )

    // --- Lifecycle ---------------------------------------------------------------------------

    fun clone(request: CloneRequest): String? = transport.postForUpid(
        base(request.source.node, request.source.type, request.source.vmid) + "/clone",
        request.toParameters(),
    )

    fun migrate(request: MigrateRequest): String? = transport.postForUpid(
        base(request.guest.node, request.guest.type, request.guest.vmid) + "/migrate",
        request.toParameters(),
    )

    /** What the server thinks about migrating this guest right now; QEMU only. */
    fun migrationPreconditions(node: String, vmid: Int, target: String?): JsonObject? =
        transport.getObject(
            "/nodes/$node/qemu/$vmid/migrate",
            target?.let { mapOf("target" to it) }.orEmpty(),
        )

    fun delete(
        node: String,
        type: GuestType,
        vmid: Int,
        purgeFromJobs: Boolean,
        destroyUnreferencedDisks: Boolean,
    ): String? = transport.deleteForUpid(base(node, type, vmid), buildMap {
        if (purgeFromJobs) put("purge", "1")
        if (destroyUnreferencedDisks) put("destroy-unreferenced-disks", "1")
    })

    fun convertToTemplate(node: String, type: GuestType, vmid: Int): String? =
        transport.postForUpid(base(node, type, vmid) + "/template")

    // --- Snapshots ---------------------------------------------------------------------------

    fun snapshots(node: String, type: GuestType, vmid: Int): List<JsonObject> =
        transport.getObjects(base(node, type, vmid) + "/snapshot")

    fun createSnapshot(
        node: String,
        type: GuestType,
        vmid: Int,
        name: String,
        description: String?,
        includeRam: Boolean,
    ): String? = transport.postForUpid(base(node, type, vmid) + "/snapshot", buildMap {
        put("snapname", name)
        description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
        // vmstate only exists for QEMU; sending it to an LXC endpoint is an error.
        if (includeRam && type == GuestType.QEMU) put("vmstate", "1")
    })

    fun deleteSnapshot(node: String, type: GuestType, vmid: Int, name: String, force: Boolean): String? =
        transport.deleteForUpid(
            base(node, type, vmid) + "/snapshot/" + encodePathSegment(name),
            if (force) mapOf("force" to "1") else emptyMap(),
        )

    fun rollbackSnapshot(node: String, type: GuestType, vmid: Int, name: String, start: Boolean): String? =
        transport.postForUpid(
            base(node, type, vmid) + "/snapshot/" + encodePathSegment(name) + "/rollback",
            if (start) mapOf("start" to "1") else emptyMap(),
        )

    fun updateSnapshotDescription(
        node: String,
        type: GuestType,
        vmid: Int,
        name: String,
        description: String,
    ) {
        transport.put(
            base(node, type, vmid) + "/snapshot/" + encodePathSegment(name) + "/config",
            mapOf("description" to description),
        )
    }

    // --- Statistics and firewall ---------------------------------------------------------------

    fun rrdData(
        node: String,
        type: GuestType,
        vmid: Int,
        timeframe: RrdTimeframe,
        consolidation: RrdConsolidation = RrdConsolidation.AVERAGE,
    ): List<JsonObject> = transport.getObjects(
        base(node, type, vmid) + "/rrddata",
        mapOf("timeframe" to timeframe.api, "cf" to consolidation.api),
    )

    fun firewallRules(node: String, type: GuestType, vmid: Int): List<JsonObject> =
        transport.getObjects(base(node, type, vmid) + "/firewall/rules")

    fun firewallOptions(node: String, type: GuestType, vmid: Int): JsonObject? =
        transport.getObject(base(node, type, vmid) + "/firewall/options")

    fun setFirewallEnabled(node: String, type: GuestType, vmid: Int, enabled: Boolean) {
        transport.put(
            base(node, type, vmid) + "/firewall/options",
            mapOf("enable" to if (enabled) "1" else "0"),
        )
    }

    fun setFirewallRuleEnabled(node: String, type: GuestType, vmid: Int, position: Int, enabled: Boolean) {
        transport.put(
            base(node, type, vmid) + "/firewall/rules/$position",
            mapOf("enable" to if (enabled) "1" else "0"),
        )
    }

    fun deleteFirewallRule(node: String, type: GuestType, vmid: Int, position: Int) {
        transport.delete(base(node, type, vmid) + "/firewall/rules/$position")
    }

    // --- QEMU guest agent -------------------------------------------------------------------------

    fun agentNetworkInterfaces(node: String, vmid: Int): JsonObject? =
        transport.getObject("/nodes/$node/qemu/$vmid/agent/network-get-interfaces")
}
