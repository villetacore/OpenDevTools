package com.opendevtools.proxmox.api.endpoint

import com.google.gson.JsonObject
import com.opendevtools.core.http.encodePathSegment
import com.opendevtools.proxmox.api.ProxmoxTransport
import com.opendevtools.proxmox.model.RrdConsolidation
import com.opendevtools.proxmox.model.RrdTimeframe
import com.opendevtools.proxmox.model.StorageContentType

/** `/nodes/<node>/storage/...` plus the cluster-level storage definitions. */
class StorageEndpoints(private val transport: ProxmoxTransport) {

    fun list(node: String): List<JsonObject> = transport.getObjects("/nodes/$node/storage")

    /** Only storages that can hold the given content type, which is what the dialogs offer. */
    fun list(node: String, contentType: StorageContentType): List<JsonObject> =
        transport.getObjects("/nodes/$node/storage", mapOf("content" to contentType.api))

    fun status(node: String, storage: String): JsonObject? =
        transport.getObject("/nodes/$node/storage/$storage/status")

    fun content(node: String, storage: String, contentType: StorageContentType? = null): List<JsonObject> =
        transport.getObjects(
            "/nodes/$node/storage/$storage/content",
            contentType?.let { mapOf("content" to it.api) }.orEmpty(),
        )

    fun contentForGuest(node: String, storage: String, vmid: Int): List<JsonObject> =
        transport.getObjects(
            "/nodes/$node/storage/$storage/content",
            mapOf("vmid" to vmid.toString()),
        )

    fun deleteVolume(node: String, storage: String, volid: String): String? =
        transport.deleteForUpid("/nodes/$node/storage/$storage/content/${encodePathSegment(volid)}")

    /** Backup notes and the protection flag are the two attributes a backup archive can carry. */
    fun updateVolumeAttributes(
        node: String,
        storage: String,
        volid: String,
        notes: String?,
        protectVolume: Boolean?,
    ) {
        val parameters = buildMap {
            notes?.let { put("notes", it) }
            protectVolume?.let { put("protected", if (it) "1" else "0") }
        }
        if (parameters.isEmpty()) return
        transport.put(
            "/nodes/$node/storage/$storage/content/${encodePathSegment(volid)}",
            parameters,
        )
    }
}
