package com.opendevtools.proxmox.service.mapper

import com.google.gson.JsonObject
import com.opendevtools.core.json.bool
import com.opendevtools.core.json.dbl
import com.opendevtools.core.json.int
import com.opendevtools.core.json.intOrNull
import com.opendevtools.core.json.long
import com.opendevtools.core.json.str
import com.opendevtools.proxmox.model.ClusterQuorum
import com.opendevtools.proxmox.model.GuestStatus
import com.opendevtools.proxmox.model.GuestType
import com.opendevtools.proxmox.model.ProxmoxGuest
import com.opendevtools.proxmox.model.ProxmoxNode
import com.opendevtools.proxmox.model.ProxmoxPool
import com.opendevtools.proxmox.model.ProxmoxStorage
import com.opendevtools.proxmox.model.StorageContentType

/** Turns `/cluster/resources` rows and their siblings into the plugin model. */
object ClusterMapper {

    fun toGuest(raw: JsonObject): ProxmoxGuest? {
        val type = GuestType.fromApi(raw.str("type")) ?: return null
        val node = raw.str("node") ?: return null
        val vmid = raw.intOrNull("vmid") ?: return null
        return ProxmoxGuest(
            vmid = vmid,
            name = raw.str("name") ?: "guest-$vmid",
            type = type,
            node = node,
            status = GuestStatus.fromApi(raw.str("status")),
            cpu = raw.dbl("cpu"),
            maxCpu = raw.int("maxcpu"),
            mem = raw.long("mem"),
            maxMem = raw.long("maxmem"),
            disk = raw.long("disk"),
            maxDisk = raw.long("maxdisk"),
            diskRead = raw.long("diskread"),
            diskWrite = raw.long("diskwrite"),
            netIn = raw.long("netin"),
            netOut = raw.long("netout"),
            uptime = raw.long("uptime"),
            template = raw.bool("template"),
            lock = raw.str("lock")?.takeIf { it.isNotBlank() },
            tags = parseTags(raw.str("tags")),
            pool = raw.str("pool")?.takeIf { it.isNotBlank() },
            haState = raw.str("hastate")?.takeIf { it.isNotBlank() },
        )
    }

    fun toStorage(raw: JsonObject): ProxmoxStorage? {
        val name = raw.str("storage") ?: return null
        val node = raw.str("node") ?: return null
        val total = raw.long("maxdisk").takeIf { it > 0 } ?: raw.long("total")
        val used = raw.long("disk").takeIf { it > 0 } ?: raw.long("used")
        return ProxmoxStorage(
            name = name,
            node = node,
            status = raw.str("status") ?: "unknown",
            used = used,
            total = total,
            available = raw.long("avail").takeIf { it > 0 } ?: (total - used).coerceAtLeast(0),
            type = raw.str("plugintype") ?: raw.str("type"),
            content = StorageContentType.parseList(raw.str("content")),
            shared = raw.bool("shared"),
            enabled = raw.str("disable") != "1",
        )
    }

    fun toNode(
        raw: JsonObject,
        guests: List<ProxmoxGuest>,
        storages: List<ProxmoxStorage>,
    ): ProxmoxNode? {
        val name = raw.str("node") ?: raw.str("name") ?: return null
        return ProxmoxNode(
            name = name,
            status = raw.str("status") ?: "unknown",
            cpu = raw.dbl("cpu"),
            maxCpu = raw.int("maxcpu"),
            mem = raw.long("mem"),
            maxMem = raw.long("maxmem"),
            disk = raw.long("disk"),
            maxDisk = raw.long("maxdisk"),
            uptime = raw.long("uptime"),
            level = raw.str("level")?.takeIf { it.isNotBlank() },
            sslFingerprint = raw.str("ssl_fingerprint"),
            guests = guests,
            storages = storages,
        )
    }

    fun toPool(raw: JsonObject): ProxmoxPool? {
        val id = raw.str("poolid") ?: return null
        return ProxmoxPool(id = id, comment = raw.str("comment")?.takeIf { it.isNotBlank() })
    }

    /** `/cluster/status` mixes one `cluster` row with one row per node. */
    fun toQuorum(rows: List<JsonObject>): ClusterQuorum? {
        val cluster = rows.firstOrNull { it.str("type") == "cluster" } ?: return null
        return ClusterQuorum(
            name = cluster.str("name") ?: "cluster",
            quorate = cluster.bool("quorate"),
            nodes = cluster.int("nodes"),
            version = cluster.int("version"),
        )
    }

    /** Proxmox stores tags as one semicolon-separated string. */
    fun parseTags(value: String?): List<String> =
        value.orEmpty().split(';', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}
