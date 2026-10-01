package com.opendevtools.proxmox.toolwindow.details

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.PopupHandler
import com.intellij.util.ui.ColumnInfo
import com.opendevtools.core.format.Formats
import com.opendevtools.proxmox.model.NodeService
import com.opendevtools.proxmox.model.ProxmoxNode
import com.opendevtools.proxmox.service.ProxmoxService

/** Systemd units Proxmox manages on the selected node, with start/stop/restart in the context menu. */
class NodeServicesTab(project: Project) : TableDetailTab<NodeService>(
    project,
    "Services",
    AllIcons.Nodes.Services,
    "Select a node",
) {

    private var currentNode: ProxmoxNode? = null

    init {
        PopupHandler.installPopupMenu(table, "ProxmoxTools.ServiceMenu", "ProxmoxServicePopup")
    }

    override fun supports(target: DetailTarget): Boolean = target is DetailTarget.Node

    override fun columns(): Array<ColumnInfo<NodeService, *>> = arrayOf(
        column("Service", 24) { it.name },
        column("State", 12) { it.state },
        column("Unit state", 12) { it.unitState },
        column("Description", 48) { it.description },
    )

    override fun loadData(target: DetailTarget) {
        val node = (target as? DetailTarget.Node)?.node ?: run {
            currentNode = null
            setRows(emptyList())
            return
        }
        currentNode = node
        showLoading()
        load(
            compute = { ProxmoxService.getInstance().nodeServices(node.name) },
            onError = { showError(it) },
            onSuccess = { setRows(it, "No services reported") },
        )
    }

    fun currentNode(): ProxmoxNode? = currentNode

    fun selectedService(): NodeService? = selectedRow()
}

/** Physical disks of the node, with SMART health and wearout where the server reports them. */
class NodeDisksTab(project: Project) : TableDetailTab<com.opendevtools.proxmox.model.NodeDisk>(
    project,
    "Disks",
    AllIcons.Nodes.HomeFolder,
    "Select a node",
) {

    override fun supports(target: DetailTarget): Boolean = target is DetailTarget.Node

    override fun columns(): Array<ColumnInfo<com.opendevtools.proxmox.model.NodeDisk, *>> = arrayOf(
        column("Device", 16) { it.device },
        column("Type", 8) { it.type },
        column("Size", 10) { Formats.bytes(it.size) },
        column("Model", 24) { it.model },
        column("Serial", 20) { it.serial },
        column("Used by", 14) { it.used },
        column("Health", 10) { it.health },
        column("Wearout", 8) { it.wearout },
    )

    override fun loadData(target: DetailTarget) {
        val node = (target as? DetailTarget.Node)?.node ?: run {
            setRows(emptyList())
            return
        }
        showLoading()
        load(
            compute = { ProxmoxService.getInstance().nodeDisks(node.name) },
            onError = { showError(it) },
            onSuccess = { setRows(it, "No disks reported") },
        )
    }
}

/** Packages with a pending upgrade, which is the "Updates" page of the web UI. */
class NodeUpdatesTab(project: Project) : TableDetailTab<com.opendevtools.proxmox.model.PackageUpdate>(
    project,
    "Updates",
    AllIcons.Actions.Download,
    "Select a node",
) {

    override fun supports(target: DetailTarget): Boolean = target is DetailTarget.Node

    override fun columns(): Array<ColumnInfo<com.opendevtools.proxmox.model.PackageUpdate, *>> = arrayOf(
        column("Package", 26) { it.name },
        column("Installed", 20) { it.currentVersion },
        column("Available", 20) { it.newVersion },
        column("Description", 44) { it.description },
    )

    override fun loadData(target: DetailTarget) {
        val node = (target as? DetailTarget.Node)?.node ?: run {
            setRows(emptyList())
            return
        }
        showLoading()
        load(
            compute = { ProxmoxService.getInstance().nodeUpdates(node.name) },
            onError = { showError(it) },
            onSuccess = { setRows(it, "Everything is up to date") },
        )
    }
}

/** Storage replication jobs configured on the node and how their last run went. */
class ReplicationTab(project: Project) : TableDetailTab<com.opendevtools.proxmox.model.ReplicationJob>(
    project,
    "Replication",
    AllIcons.Actions.Refresh,
    "Select a node",
) {

    override fun supports(target: DetailTarget): Boolean = target is DetailTarget.Node

    override fun columns(): Array<ColumnInfo<com.opendevtools.proxmox.model.ReplicationJob, *>> = arrayOf(
        column("Job", 14) { it.id },
        column("Guest", 8) { it.guest?.toString() },
        column("Target", 12) { it.target },
        column("Schedule", 12) { it.schedule },
        column("Last sync", 18) { it.lastSync?.let { time -> Formats.timestamp(time) } },
        column("Next sync", 18) { it.nextSync?.let { time -> Formats.timestamp(time) } },
        column("Duration", 10) { it.duration?.let { seconds -> Formats.duration(seconds.toLong()) } },
        column("Status", 30) {
            when {
                it.disabled -> "disabled"
                it.failed -> it.error
                else -> "OK"
            }
        },
    )

    override fun loadData(target: DetailTarget) {
        val node = (target as? DetailTarget.Node)?.node ?: run {
            setRows(emptyList())
            return
        }
        showLoading()
        load(
            compute = { ProxmoxService.getInstance().nodeReplication(node.name) },
            onError = { showError(it) },
            onSuccess = { setRows(it, "No replication jobs") },
        )
    }
}
