package com.opendevtools.proxmox.api.endpoint

import com.google.gson.JsonObject
import com.opendevtools.core.http.encodePathSegment
import com.opendevtools.proxmox.api.ProxmoxTransport
import com.opendevtools.proxmox.model.BackupRequest
import com.opendevtools.proxmox.model.RestoreRequest
import com.opendevtools.proxmox.model.RrdConsolidation
import com.opendevtools.proxmox.model.RrdTimeframe

/** Everything under `/nodes/<node>` that is not a guest or a storage. */
class NodeEndpoints(private val transport: ProxmoxTransport) {

    fun list(): List<JsonObject> = transport.getObjects("/nodes")

    fun status(node: String): JsonObject? = transport.getObject("/nodes/$node/status")

    fun version(node: String): JsonObject? = transport.getObject("/nodes/$node/version")

    fun rrdData(
        node: String,
        timeframe: RrdTimeframe,
        consolidation: RrdConsolidation = RrdConsolidation.AVERAGE,
    ): List<JsonObject> = transport.getObjects(
        "/nodes/$node/rrddata",
        mapOf("timeframe" to timeframe.api, "cf" to consolidation.api),
    )

    // --- Tasks -----------------------------------------------------------------------------

    /**
     * Task history of one node. [errorsOnly] and [vmid] mirror the filters of the web UI's
     * task pane, and [start] pages backwards through a long history.
     */
    fun tasks(
        node: String,
        limit: Int = 100,
        start: Int = 0,
        errorsOnly: Boolean = false,
        vmid: Int? = null,
        typeFilter: String? = null,
        userFilter: String? = null,
    ): List<JsonObject> = transport.getObjects("/nodes/$node/tasks", buildMap {
        put("limit", limit.toString())
        put("start", start.toString())
        if (errorsOnly) put("errors", "1")
        vmid?.let { put("vmid", it.toString()) }
        typeFilter?.takeIf { it.isNotBlank() }?.let { put("typefilter", it) }
        userFilter?.takeIf { it.isNotBlank() }?.let { put("userfilter", it) }
    })

    fun taskStatus(node: String, upid: String): JsonObject? =
        transport.getObject("/nodes/$node/tasks/${encodePathSegment(upid)}/status")

    fun taskLog(node: String, upid: String, start: Int = 0, limit: Int = 500): List<JsonObject> =
        transport.getObjects(
            "/nodes/$node/tasks/${encodePathSegment(upid)}/log",
            mapOf("start" to start.toString(), "limit" to limit.toString()),
        )

    fun stopTask(node: String, upid: String) {
        transport.delete("/nodes/$node/tasks/${encodePathSegment(upid)}")
    }

    // --- Diagnostics -----------------------------------------------------------------------

    fun syslog(node: String, limit: Int = 500, since: String? = null): List<JsonObject> =
        transport.getObjects("/nodes/$node/syslog", buildMap {
            put("limit", limit.toString())
            since?.let { put("since", it) }
        })

    fun services(node: String): List<JsonObject> = transport.getObjects("/nodes/$node/services")

    /** `action` is one of start, stop, restart, reload. Returns the UPID of the task. */
    fun serviceAction(node: String, service: String, action: String): String? =
        transport.postForUpid("/nodes/$node/services/$service/$action")

    fun disks(node: String): List<JsonObject> = transport.getObjects("/nodes/$node/disks/list")

    fun availableUpdates(node: String): List<JsonObject> = transport.getObjects("/nodes/$node/apt/update")

    // --- Network ---------------------------------------------------------------------------

    fun network(node: String): List<JsonObject> = transport.getObjects("/nodes/$node/network")

    fun bridges(node: String): List<JsonObject> =
        transport.getObjects("/nodes/$node/network", mapOf("type" to "any_bridge"))

    // --- Guest creation and backup ---------------------------------------------------------

    fun createVm(node: String, parameters: Map<String, String>): String? =
        transport.postForUpid("/nodes/$node/qemu", parameters)

    fun createContainer(node: String, parameters: Map<String, String>): String? =
        transport.postForUpid("/nodes/$node/lxc", parameters)

    fun backup(request: BackupRequest): String? =
        transport.postForUpid("/nodes/${request.node}/vzdump", request.toParameters())

    /** Restore creates a guest, so it posts to the guest collection rather than to vzdump. */
    fun restore(request: RestoreRequest): String? =
        transport.postForUpid("/nodes/${request.node}/${request.type.api}", request.toParameters())

    // --- Node-level power and firewall ------------------------------------------------------

    fun startAllGuests(node: String): String? = transport.postForUpid("/nodes/$node/startall")

    fun stopAllGuests(node: String): String? = transport.postForUpid("/nodes/$node/stopall")

    fun replicationStatus(node: String): List<JsonObject> =
        transport.getObjects("/nodes/$node/replication")
}
