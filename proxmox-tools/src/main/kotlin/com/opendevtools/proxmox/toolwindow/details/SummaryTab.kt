package com.opendevtools.proxmox.toolwindow.details

import com.intellij.openapi.project.Project
import com.opendevtools.core.format.Formats
import com.opendevtools.core.ui.Property
import com.opendevtools.core.ui.PropertiesTable
import com.opendevtools.proxmox.model.ClusterSnapshot
import com.opendevtools.proxmox.model.GuestType
import com.opendevtools.proxmox.model.ProxmoxGuest
import com.opendevtools.proxmox.model.ProxmoxNode
import com.opendevtools.proxmox.model.ProxmoxStorage
import com.opendevtools.proxmox.service.ProxmoxService
import java.awt.BorderLayout

/**
 * The property sheet of whatever is selected. For a guest it also folds in the live
 * `status/current` document and the guest agent's addresses, which is where the web UI gets the
 * numbers on its summary page.
 */
class SummaryTab(project: Project) : DetailTab(project, "Summary") {

    private val properties = PropertiesTable("Select a node, a guest or a storage")

    init {
        add(properties, BorderLayout.CENTER)
    }

    override fun supports(target: DetailTarget): Boolean = true

    override fun loadData(target: DetailTarget) {
        when (target) {
            is DetailTarget.None -> properties.clear()
            is DetailTarget.Datacenter -> properties.setProperties(datacenterRows(target.snapshot))
            is DetailTarget.Storage -> properties.setProperties(storageRows(target.storage))
            is DetailTarget.Node -> {
                properties.setProperties(nodeRows(target.node, emptyMap()))
                val node = target.node
                load(
                    compute = { ProxmoxService.getInstance().nodeStatus(node.name) },
                    onError = { properties.setProperties(nodeRows(node, emptyMap())) },
                    onSuccess = { properties.setProperties(nodeRows(node, it)) },
                )
            }

            is DetailTarget.Guest -> {
                val guest = target.guest
                properties.setProperties(guestRows(guest, emptyMap(), emptyList()))
                load(
                    compute = {
                        val service = ProxmoxService.getInstance()
                        service.guestStatus(guest) to service.guestNetworkInterfaces(guest)
                    },
                    onError = { properties.setProperties(guestRows(guest, emptyMap(), emptyList())) },
                    onSuccess = { (status, interfaces) ->
                        properties.setProperties(guestRows(guest, status, interfaces.map { iface ->
                            iface.name + ": " + iface.addresses.joinToString(", ").ifBlank { "-" }
                        }))
                    },
                )
            }
        }
    }

    private fun datacenterRows(snapshot: ClusterSnapshot): List<Property> = buildList {
        add(Property("Proxmox VE", snapshot.version ?: "unknown"))
        snapshot.quorum?.let {
            add(Property("Cluster", it.name))
            add(Property("Quorum", if (it.quorate) "yes" else "no"))
            add(Property("Cluster nodes", it.nodes.toString()))
        }
        add(Property("Nodes", "${snapshot.nodes.size} (${snapshot.nodes.count { it.isOnline }} online)"))
        val guests = snapshot.allGuests
        add(Property("Virtual machines", countLine(guests, GuestType.QEMU)))
        add(Property("Containers", countLine(guests, GuestType.LXC)))
        add(Property("Templates", guests.count { it.template }.toString()))
        add(Property("Pools", snapshot.pools.size.toString()))
        add(Property("Running tasks", snapshot.runningTasks.size.toString()))
        add(Property("Total memory", Formats.ratio(
            snapshot.nodes.sumOf { it.mem },
            snapshot.nodes.sumOf { it.maxMem },
        )))
        add(Property("Loaded", Formats.relative(snapshot.loadedAt)))
    }

    private fun countLine(guests: List<ProxmoxGuest>, type: GuestType): String {
        val of = guests.filter { it.type == type && !it.template }
        return "${of.size} (${of.count { it.isRunning }} running)"
    }

    private fun nodeRows(node: ProxmoxNode, status: Map<String, String>): List<Property> = buildList {
        add(Property("Node", node.name))
        add(Property("Status", node.status))
        add(Property("CPU", "${Formats.percent(node.cpu)} of ${node.maxCpu} cores"))
        add(Property("Memory", Formats.ratio(node.mem, node.maxMem)))
        add(Property("Root disk", Formats.ratio(node.disk, node.maxDisk)))
        add(Property("Uptime", Formats.uptime(node.uptime)))
        add(Property("Guests", "${node.guests.size} (${node.runningGuests} running)"))
        node.level?.let { add(Property("Subscription level", it)) }

        status["pveversion"]?.let { add(Property("PVE version", it)) }
        status["kversion"]?.let { add(Property("Kernel", it)) }
        status["cpuinfo.model"]?.let { add(Property("CPU model", it)) }
        status["cpuinfo.sockets"]?.let { add(Property("Sockets", it)) }
        status["loadavg.0"]?.let { one ->
            val five = status["loadavg.1"].orEmpty()
            val fifteen = status["loadavg.2"].orEmpty()
            add(Property("Load average", "$one $five $fifteen"))
        }
        status["swap.used"]?.toLongOrNull()?.let { used ->
            add(Property("Swap", Formats.ratio(used, status["swap.total"]?.toLongOrNull() ?: 0)))
        }
        node.sslFingerprint?.let { add(Property("SSL fingerprint", it)) }
    }

    private fun guestRows(
        guest: ProxmoxGuest,
        status: Map<String, String>,
        addresses: List<String>,
    ): List<Property> = buildList {
        add(Property("VMID", guest.vmid.toString()))
        add(Property("Name", guest.name))
        add(Property("Type", guest.type.label))
        add(Property("Node", guest.node))
        add(Property("Status", if (guest.template) "template" else guest.status.display))
        guest.lock?.let { add(Property("Lock", it)) }
        add(Property("CPU", "${Formats.percent(guest.cpu)} of ${guest.maxCpu} cores"))
        add(Property("Memory", Formats.ratio(guest.mem, guest.maxMem)))
        add(Property("Disk", Formats.ratio(guest.disk, guest.maxDisk)))
        add(Property("Disk I/O", "read ${Formats.bytes(guest.diskRead)}, written ${Formats.bytes(guest.diskWrite)}"))
        add(Property("Network", "in ${Formats.bytes(guest.netIn)}, out ${Formats.bytes(guest.netOut)}"))
        add(Property("Uptime", Formats.uptime(guest.uptime)))
        guest.pool?.let { add(Property("Pool", it)) }
        if (guest.tags.isNotEmpty()) add(Property("Tags", guest.tags.joinToString(", ")))
        guest.haState?.let { add(Property("HA state", it)) }

        status["pid"]?.let { add(Property("Process id", it)) }
        status["qmpstatus"]?.let { add(Property("QMP status", it)) }
        status["running-machine"]?.let { add(Property("Machine", it)) }
        status["running-qemu"]?.let { add(Property("QEMU version", it)) }
        status["ha.managed"]?.let { add(Property("HA managed", if (it == "1") "yes" else "no")) }

        addresses.forEachIndexed { index, line ->
            add(Property(if (addresses.size == 1) "Address" else "Address ${index + 1}", line))
        }
    }

    private fun storageRows(storage: ProxmoxStorage): List<Property> = listOf(
        Property("Storage", storage.name),
        Property("Node", storage.node),
        Property("Status", storage.status),
        Property("Type", storage.type ?: "-"),
        Property("Content", storage.content.joinToString(", ") { it.title }.ifBlank { "-" }),
        Property("Shared", if (storage.shared) "yes" else "no"),
        Property("Enabled", if (storage.enabled) "yes" else "no"),
        Property("Usage", Formats.ratio(storage.used, storage.total)),
        Property("Available", Formats.bytes(storage.available)),
    )
}
