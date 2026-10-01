package com.opendevtools.proxmox.toolwindow.details

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.JBSplitter
import com.intellij.util.ui.ColumnInfo
import com.opendevtools.core.format.Formats
import com.opendevtools.core.ui.Property
import com.opendevtools.core.ui.PropertiesTable
import com.opendevtools.proxmox.ProxmoxIcons
import com.opendevtools.proxmox.model.AccessAcl
import com.opendevtools.proxmox.model.AccessUser
import com.opendevtools.proxmox.model.BackupJob
import com.opendevtools.proxmox.model.FirewallRule
import com.opendevtools.proxmox.model.HaResource
import com.opendevtools.proxmox.service.ProxmoxService
import java.awt.BorderLayout

/**
 * The datacenter-wide views. They are read-only on purpose: HA, scheduled backups, users and the
 * cluster firewall are configuration a person changes deliberately and rarely, and getting them
 * wrong from a cramped side panel is worse than switching to the web UI for that one change.
 */
class HaTab(project: Project) : TableDetailTab<HaResource>(
    project,
    "HA",
    AllIcons.Actions.Rerun,
    "Select the datacenter",
) {

    override fun supports(target: DetailTarget): Boolean = target is DetailTarget.Datacenter

    override fun columns(): Array<ColumnInfo<HaResource, *>> = arrayOf(
        column("Resource", 14) { it.sid },
        column("Type", 8) { it.type },
        column("State", 12) { it.state },
        column("Requested", 12) { it.requestState },
        column("Node", 12) { it.node },
        column("Group", 12) { it.group },
        column("Max restart", 10) { it.maxRestart?.toString() },
        column("Max relocate", 10) { it.maxRelocate?.toString() },
    )

    override fun loadData(target: DetailTarget) {
        if (target !is DetailTarget.Datacenter) {
            setRows(emptyList())
            return
        }
        showLoading()
        load(
            compute = { ProxmoxService.getInstance().haResources() },
            onError = { showError(it) },
            onSuccess = { setRows(it, "High availability is not configured") },
        )
    }
}

/** Scheduled `vzdump` jobs, as configured under Datacenter | Backup in the web UI. */
class BackupJobsTab(project: Project) : TableDetailTab<BackupJob>(
    project,
    "Backup Jobs",
    ProxmoxIcons.Backup,
    "Select the datacenter",
) {

    override fun supports(target: DetailTarget): Boolean = target is DetailTarget.Datacenter

    override fun columns(): Array<ColumnInfo<BackupJob, *>> = arrayOf(
        column("Enabled", 8) { if (it.enabled) "yes" else "no" },
        column("Schedule", 14) { it.schedule },
        column("Next run", 18) { it.nextRun?.let { time -> Formats.timestamp(time) } },
        column("Storage", 12) { it.storage },
        column("Mode", 10) { it.mode },
        column("Selection", 20) { it.selection },
        column("Comment", 24) { it.comment },
    )

    override fun loadData(target: DetailTarget) {
        if (target !is DetailTarget.Datacenter) {
            setRows(emptyList())
            return
        }
        showLoading()
        load(
            compute = { ProxmoxService.getInstance().backupJobs() },
            onError = { showError(it) },
            onSuccess = { setRows(it, "No scheduled backup jobs") },
        )
    }
}

/** Users and the ACL entries that give them their privileges. */
class AccessTab(project: Project) : TableDetailTab<AccessUser>(
    project,
    "Access",
    AllIcons.General.User,
    "Select the datacenter",
) {

    private val acl = PropertiesTable("No permissions visible to this account")

    init {
        remove(tableScrollPane)
        add(
            JBSplitter(true, 0.55f).apply {
                firstComponent = tableScrollPane
                secondComponent = acl
            },
            BorderLayout.CENTER,
        )
    }

    override fun supports(target: DetailTarget): Boolean = target is DetailTarget.Datacenter

    override fun columns(): Array<ColumnInfo<AccessUser, *>> = arrayOf(
        column("User", 20) { it.userid },
        column("Enabled", 8) { if (it.enabled) "yes" else "no" },
        column("Name", 20) { listOfNotNull(it.firstName, it.lastName).joinToString(" ") },
        column("Email", 24) { it.email },
        column("Groups", 20) { it.groups.joinToString(", ") },
        column("Expires", 18) { if (it.expire <= 0) "never" else Formats.timestamp(it.expire) },
        column("Comment", 24) { it.comment },
    )

    override fun loadData(target: DetailTarget) {
        if (target !is DetailTarget.Datacenter) {
            setRows(emptyList())
            acl.clear()
            return
        }
        showLoading()
        load(
            compute = {
                val service = ProxmoxService.getInstance()
                service.accessUsers() to service.accessAcl()
            },
            onError = { showError(it) },
            onSuccess = { (users, entries) ->
                setRows(users, "No users visible to this account")
                acl.setProperties(entries.map { it.toProperty() })
            },
        )
    }

    private fun AccessAcl.toProperty(): Property = Property(
        "$type $ugid",
        "$roleid on $path" + if (propagate) " (propagates)" else "",
    )
}

/** Cluster-level firewall rules and the datacenter firewall switches. */
class ClusterFirewallTab(project: Project) : TableDetailTab<FirewallRule>(
    project,
    "Firewall",
    ProxmoxIcons.Firewall,
    "Select the datacenter",
) {

    private val options = PropertiesTable("No firewall options")

    init {
        remove(tableScrollPane)
        add(
            JBSplitter(true, 0.6f).apply {
                firstComponent = tableScrollPane
                secondComponent = options
            },
            BorderLayout.CENTER,
        )
    }

    override fun supports(target: DetailTarget): Boolean = target is DetailTarget.Datacenter

    override fun columns(): Array<ColumnInfo<FirewallRule, *>> = arrayOf(
        column("#", 4) { it.position.toString() },
        column("On", 4) { if (it.enabled) "yes" else "no" },
        column("Direction", 8) { it.type },
        column("Action", 10) { it.action },
        column("Macro", 12) { it.macro },
        column("Protocol", 8) { it.protocol },
        column("Source", 18) { it.source },
        column("Destination", 18) { it.destination },
        column("Dest. port", 10) { it.destinationPort },
        column("Comment", 24) { it.comment },
    )

    override fun loadData(target: DetailTarget) {
        if (target !is DetailTarget.Datacenter) {
            setRows(emptyList())
            options.clear()
            return
        }
        showLoading()
        load(
            compute = {
                val service = ProxmoxService.getInstance()
                service.clusterFirewallRules() to service.clusterFirewallOptions()
            },
            onError = { showError(it) },
            onSuccess = { (rules, firewallOptions) ->
                setRows(rules, "No cluster firewall rules")
                options.setProperties(
                    listOf(
                        Property("Firewall", if (firewallOptions.enabled) "enabled" else "disabled"),
                        Property("Input policy", firewallOptions.inputPolicy ?: "(default)"),
                        Property("Output policy", firewallOptions.outputPolicy ?: "(default)"),
                    ) + firewallOptions.entries
                        .filterKeys { it !in setOf("enable", "policy_in", "policy_out", "digest") }
                        .map { Property(it.key, it.value) },
                )
            },
        )
    }
}
