package com.opendevtools.proxmox.toolwindow.details

import com.intellij.openapi.project.Project
import com.intellij.ui.JBSplitter
import com.intellij.ui.PopupHandler
import com.intellij.util.ui.ColumnInfo
import com.opendevtools.core.ui.Property
import com.opendevtools.core.ui.PropertiesTable
import com.opendevtools.proxmox.ProxmoxIcons
import com.opendevtools.proxmox.model.FirewallRule
import com.opendevtools.proxmox.model.ProxmoxGuest
import com.opendevtools.proxmox.service.ProxmoxService
import java.awt.BorderLayout

/** Firewall rules and options of the selected guest, with the option sheet below the rules. */
class FirewallTab(project: Project) : TableDetailTab<FirewallRule>(
    project,
    "Firewall",
    ProxmoxIcons.Firewall,
    "Select a virtual machine or a container",
) {

    private val options = PropertiesTable("No firewall options")
    private var currentGuest: ProxmoxGuest? = null

    init {
        remove(tableScrollPane)
        add(
            JBSplitter(true, 0.6f).apply {
                firstComponent = tableScrollPane
                secondComponent = options
            },
            BorderLayout.CENTER,
        )
        PopupHandler.installPopupMenu(table, "ProxmoxTools.FirewallMenu", "ProxmoxFirewallPopup")
    }

    override fun supports(target: DetailTarget): Boolean = target is DetailTarget.Guest

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
        val guest = (target as? DetailTarget.Guest)?.guest ?: run {
            currentGuest = null
            setRows(emptyList())
            options.clear()
            return
        }
        currentGuest = guest
        showLoading()
        load(
            compute = {
                val service = ProxmoxService.getInstance()
                service.guestFirewallRules(guest) to service.guestFirewallOptions(guest)
            },
            onError = { showError(it) },
            onSuccess = { (rules, firewallOptions) ->
                setRows(rules, "No firewall rules")
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

    fun currentGuest(): ProxmoxGuest? = currentGuest

    fun selectedRule(): FirewallRule? = selectedRow()
}
