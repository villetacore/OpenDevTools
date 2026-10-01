package com.opendevtools.proxmox.api.endpoint

import com.google.gson.JsonObject
import com.opendevtools.core.json.asStringOrNull
import com.opendevtools.proxmox.api.ProxmoxTransport

/** Cluster-wide reads: resources, status, tasks, pools, HA, backup jobs and replication. */
class ClusterEndpoints(private val transport: ProxmoxTransport) {

    fun version(): JsonObject? = transport.getObject("/version")

    /**
     * One call that returns every node, guest, storage and pool. The web UI builds its whole
     * tree from this endpoint, and so does the tool window.
     */
    fun resources(): List<JsonObject> = transport.getObjects("/cluster/resources")

    fun resources(type: String): List<JsonObject> =
        transport.getObjects("/cluster/resources", mapOf("type" to type))

    /** Corosync view; on a stand-alone node this returns just the node itself. */
    fun status(): List<JsonObject> = transport.getObjects("/cluster/status")

    /** The lowest free VMID, which is what the create and clone dialogs pre-fill. */
    fun nextVmid(): Int? = transport.get("/cluster/nextid").asStringOrNull()?.toIntOrNull()

    /** Recent tasks across every node, newest first. */
    fun tasks(limit: Int = 100): List<JsonObject> =
        transport.getObjects("/cluster/tasks", mapOf("limit" to limit.toString()))

    fun pools(): List<JsonObject> = transport.getObjects("/pools")

    fun haResources(): List<JsonObject> = transport.getObjects("/cluster/ha/resources")

    fun haStatus(): List<JsonObject> = transport.getObjects("/cluster/ha/status/current")

    fun backupJobs(): List<JsonObject> = transport.getObjects("/cluster/backup")

    fun firewallRules(): List<JsonObject> = transport.getObjects("/cluster/firewall/rules")

    fun firewallOptions(): JsonObject? = transport.getObject("/cluster/firewall/options")
}
