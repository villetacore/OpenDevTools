package com.opendevtools.proxmox.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.panel
import com.opendevtools.core.ui.listRenderer
import com.opendevtools.proxmox.model.GuestType
import com.opendevtools.proxmox.model.MigrateRequest
import com.opendevtools.proxmox.model.MigrationPreconditions
import com.opendevtools.proxmox.model.ProxmoxGuest
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent

/**
 * Migration dialog. The server's own preconditions decide which target nodes are offered and
 * whether a live migration is possible, so the dialog cannot propose something that will fail.
 */
class MigrateGuestDialog(
    project: Project?,
    private val guest: ProxmoxGuest,
    private val preconditions: MigrationPreconditions?,
    allNodes: List<String>,
    private val storages: List<String>,
) : DialogWrapper(project) {

    private val targets: List<String> = preconditions?.allowedNodes
        ?.takeIf { it.isNotEmpty() }
        ?: allNodes.filter { it != guest.node }

    private val nodeCombo = JComboBox(DefaultComboBoxModel(targets.toTypedArray()))

    private val onlineCheckBox = JBCheckBox(
        if (guest.type == GuestType.QEMU) "Live migration" else "Restart migration",
        guest.isRunning,
    ).apply { isEnabled = guest.isRunning }

    private val localDisksCheckBox = JBCheckBox(
        "Migrate local disks",
        preconditions?.localDisks?.isNotEmpty() == true,
    ).apply { isEnabled = guest.type == GuestType.QEMU }

    private val storageCombo = JComboBox<String?>().apply {
        renderer = listRenderer("Same storage name on the target") { it }
        model = DefaultComboBoxModel<String?>().apply {
            addElement(null)
            storages.forEach { addElement(it) }
        }
    }

    private val forceCheckBox = JBCheckBox("Force (ignore local resource warnings)", false)

    init {
        title = "Migrate ${guest.displayName}"
        setOKButtonText("Migrate")
        init()
    }

    fun request(): MigrateRequest = MigrateRequest(
        guest = guest,
        targetNode = nodeCombo.selectedItem as String,
        online = onlineCheckBox.isSelected && onlineCheckBox.isEnabled,
        withLocalDisks = localDisksCheckBox.isSelected && localDisksCheckBox.isEnabled,
        targetStorage = storageCombo.selectedItem as? String,
        force = forceCheckBox.isSelected,
    )

    override fun createCenterPanel(): JComponent = panel {
        row("Guest:") {
            label("${guest.displayName} (${guest.type.label})")
        }
        row("Source node:") {
            label(guest.node)
        }
        row("Target node:") {
            cell(nodeCombo)
        }
        row {
            cell(onlineCheckBox).comment(
                when {
                    !guest.isRunning -> "The guest is stopped, so the migration is offline anyway"
                    guest.type == GuestType.LXC ->
                        "A container cannot migrate live; it is restarted on the target node"

                    else -> "The virtual machine keeps running during the migration"
                },
            )
        }
        row {
            cell(localDisksCheckBox).comment(
                preconditions?.localDisks
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { "Local disks: " + it.joinToString(", ") }
                    ?: "No local disks detected",
            )
        }
        row("Target storage:") {
            cell(storageCombo)
        }

        preconditions?.localResources?.takeIf { it.isNotEmpty() }?.let { blockers ->
            row {
                cell(forceCheckBox).comment(
                    "The server reports local resources that normally block a migration: " +
                        blockers.joinToString(", "),
                )
            }
        }
        preconditions?.notAllowedNodes?.takeIf { it.isNotEmpty() }?.let { blocked ->
            row {
                comment(
                    "Not available as a target: " +
                        blocked.entries.joinToString("; ") { "${it.key} (${it.value})" },
                )
            }
        }
    }

    override fun doValidate(): ValidationInfo? = when {
        targets.isEmpty() -> ValidationInfo("There is no other node to migrate to", nodeCombo)
        nodeCombo.selectedItem == null -> ValidationInfo("Choose a target node", nodeCombo)
        else -> null
    }
}
