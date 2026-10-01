package com.opendevtools.proxmox.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.dsl.builder.panel
import com.opendevtools.core.ui.listRenderer
import com.opendevtools.core.format.Formats
import com.opendevtools.proxmox.model.GuestType
import com.opendevtools.proxmox.model.RestoreRequest
import com.opendevtools.proxmox.model.StorageVolume
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent

/**
 * Restores a backup archive. Restoring over an existing VMID wipes that guest, so "Overwrite"
 * is off by default and the dialog says plainly what it will destroy.
 */
class RestoreDialog(
    project: Project?,
    private val archive: StorageVolume,
    private val defaultVmid: Int,
    private val usedVmids: Set<Int>,
    storages: List<String>,
) : DialogWrapper(project) {

    private val vmidSpinner = JBIntSpinner(archive.vmid ?: defaultVmid, 100, 999_999_999)
    private val storageCombo = JComboBox<String?>().apply {
        renderer = listRenderer("As stored in the archive") { it }
        model = DefaultComboBoxModel<String?>().apply {
            addElement(null)
            storages.forEach { addElement(it) }
        }
    }
    private val overwriteCheckBox = JBCheckBox("Overwrite an existing guest with this VMID", false)
    private val uniqueCheckBox = JBCheckBox("Generate new MAC addresses", true)
    private val startCheckBox = JBCheckBox("Start after the restore", false)

    /** vzdump names the archive `vzdump-qemu-100-...` or `vzdump-lxc-100-...`. */
    private val guestType: GuestType =
        if (archive.fileName.contains("-lxc-") || archive.fileName.contains("-openvz-")) {
            GuestType.LXC
        } else {
            GuestType.QEMU
        }

    init {
        title = "Restore Backup"
        setOKButtonText("Restore")
        init()
    }

    fun request(): RestoreRequest = RestoreRequest(
        node = archive.node,
        archive = archive.volid,
        vmid = vmidSpinner.number,
        type = guestType,
        storage = storageCombo.selectedItem as? String,
        force = overwriteCheckBox.isSelected,
        unique = uniqueCheckBox.isSelected,
        startAfterRestore = startCheckBox.isSelected,
    )

    override fun createCenterPanel(): JComponent = panel {
        row("Archive:") {
            label(archive.fileName)
        }
        row("Created:") {
            label(Formats.timestamp(archive.createdAt) + "  (" + Formats.bytes(archive.size) + ")")
        }
        row("Restores as:") {
            label(guestType.label)
        }
        row("Target node:") {
            label(archive.node)
        }
        row("Target VMID:") {
            cell(vmidSpinner)
        }
        row("Target storage:") {
            cell(storageCombo)
        }
        row {
            cell(overwriteCheckBox).comment(
                "Without this, restoring onto an existing VMID fails instead of destroying it",
            )
        }
        row {
            cell(uniqueCheckBox).comment("Avoids an address clash when the original guest still runs")
        }
        row {
            cell(startCheckBox)
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = vmidSpinner

    override fun doValidate(): ValidationInfo? {
        if (vmidSpinner.number in usedVmids && !overwriteCheckBox.isSelected) {
            return ValidationInfo(
                "VMID ${vmidSpinner.number} is in use; tick Overwrite to replace that guest",
                vmidSpinner,
            )
        }
        return null
    }
}
