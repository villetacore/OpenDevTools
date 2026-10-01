package com.opendevtools.proxmox.toolwindow.details

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.util.ui.ColumnInfo
import com.opendevtools.proxmox.model.GuestConfig
import com.opendevtools.proxmox.model.GuestType
import com.opendevtools.proxmox.service.ProxmoxService

/**
 * Network of the selected object. For a node these are the configured interfaces and bridges;
 * for a guest they are the configured NICs, enriched with the live addresses when the QEMU guest
 * agent answers.
 */
class NetworkTab(project: Project) : TableDetailTab<NetworkTab.Row>(
    project,
    "Network",
    AllIcons.Nodes.Servlet,
    "Select a node or a guest",
) {

    class Row(
        val name: String,
        val kind: String,
        val address: String,
        val details: String,
        val active: Boolean,
    )

    override fun supports(target: DetailTarget): Boolean =
        target is DetailTarget.Node || target is DetailTarget.Guest

    override fun columns(): Array<ColumnInfo<Row, *>> = arrayOf(
        column("Interface", 12) { it.name },
        column("Type", 12) { it.kind },
        column("Address", 24) { it.address },
        column("Details", 40) { it.details },
        column("Active", 7) { if (it.active) "yes" else "no" },
    )

    override fun loadData(target: DetailTarget) {
        showLoading()
        when (target) {
            is DetailTarget.Node -> load(
                compute = { ProxmoxService.getInstance().nodeNetwork(target.node.name) },
                onError = { showError(it) },
                onSuccess = { interfaces ->
                    setRows(
                        interfaces.map { iface ->
                            Row(
                                name = iface.name,
                                kind = iface.type ?: "-",
                                address = iface.cidr ?: iface.address.orEmpty(),
                                details = listOfNotNull(
                                    iface.gateway?.let { "gateway $it" },
                                    iface.bridgePorts?.takeIf { it.isNotBlank() }?.let { "ports $it" },
                                    iface.method?.let { "method $it" },
                                    iface.comments,
                                ).joinToString(", "),
                                active = iface.active,
                            )
                        },
                        "No interfaces reported",
                    )
                },
            )

            is DetailTarget.Guest -> {
                val guest = target.guest
                load(
                    compute = {
                        val service = ProxmoxService.getInstance()
                        val config = runCatching { service.guestConfig(guest) }.getOrNull()
                        val live = service.guestNetworkInterfaces(guest)
                        config to live
                    },
                    onError = { showError(it) },
                    onSuccess = { (config, live) ->
                        setRows(guestRows(config, live, guest.type), "No network interfaces")
                    },
                )
            }

            else -> setRows(emptyList())
        }
    }

    private fun guestRows(
        config: GuestConfig?,
        live: List<com.opendevtools.proxmox.model.GuestNetworkInterface>,
        type: GuestType,
    ): List<Row> {
        val rows = mutableListOf<Row>()
        config?.networks()?.forEach { (key, value) ->
            val parts = GuestConfig.parseDevice(value)
            // QEMU writes `virtio=AA:BB:...`, LXC writes `hwaddr=AA:BB:...`.
            val mac = parts["hwaddr"]
                ?: parts.entries.firstOrNull { it.value.matches(MAC) }?.value
            val matching = live.firstOrNull { it.hardwareAddress.equals(mac, ignoreCase = true) }
            rows.add(
                Row(
                    name = key,
                    kind = parts.keys.firstOrNull { it in MODELS } ?: parts["name"] ?: type.api,
                    address = matching?.addresses?.joinToString(", ")
                        ?: parts["ip"].orEmpty(),
                    details = listOfNotNull(
                        parts["bridge"]?.let { "bridge $it" },
                        parts["tag"]?.let { "vlan $it" },
                        mac?.let { "mac $it" },
                        parts["firewall"]?.takeIf { it == "1" }?.let { "firewall" },
                        parts["gw"]?.let { "gateway $it" },
                    ).joinToString(", "),
                    active = matching != null,
                ),
            )
        }
        // Agent interfaces the configuration does not mention still tell the user something.
        live.filter { iface -> rows.none { it.details.contains(iface.hardwareAddress.orEmpty(), true) } }
            .forEach { iface ->
                rows.add(
                    Row(
                        name = iface.name,
                        kind = "guest agent",
                        address = iface.addresses.joinToString(", "),
                        details = iface.hardwareAddress?.let { "mac $it" }.orEmpty(),
                        active = true,
                    ),
                )
            }
        return rows
    }

    private companion object {
        val MAC = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
        val MODELS = setOf("virtio", "e1000", "rtl8139", "vmxnet3", "e1000e")
    }
}
