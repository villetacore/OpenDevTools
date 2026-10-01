package com.opendevtools.proxmox.api

import com.opendevtools.core.json.str
import com.opendevtools.proxmox.api.endpoint.AccessEndpoints
import com.opendevtools.proxmox.api.endpoint.ClusterEndpoints
import com.opendevtools.proxmox.api.endpoint.GuestEndpoints
import com.opendevtools.proxmox.api.endpoint.NodeEndpoints
import com.opendevtools.proxmox.api.endpoint.StorageEndpoints
import com.opendevtools.proxmox.settings.ProxmoxConnectionConfig

/**
 * The single entry point into the Proxmox API. Endpoint groups mirror the shape of the REST tree,
 * so a reader can map a method here onto the API documentation without a lookup table.
 *
 * Never call from the EDT - every method performs network I/O.
 */
class ProxmoxClient(val config: ProxmoxConnectionConfig) {

    private val transport = ProxmoxTransport(config)

    val cluster = ClusterEndpoints(transport)
    val nodes = NodeEndpoints(transport)
    val guests = GuestEndpoints(transport)
    val storages = StorageEndpoints(transport)
    val access = AccessEndpoints(transport)

    /** Server version string, also used by "Test Connection" as a reachability probe. */
    fun version(): String? = cluster.version()?.str("version")
}
