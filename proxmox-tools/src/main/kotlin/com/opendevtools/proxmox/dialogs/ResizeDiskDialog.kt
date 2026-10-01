package com.opendevtools.proxmox.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.dsl.builder.panel
import com.opendevtools.proxmox.model.GuestConfig
import com.opendevtools.proxmox.model.ProxmoxGuest
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent

/**
 * Grows a virtual disk. Proxmox only supports growing - shrinking is not exposed at all, and the
 * dialog says so rather than letting the user find out from a failed task.
 */
class ResizeDiskDialog(
    project: Project?,
    private val guest: ProxmoxGuest,
    config: GuestConfig,
) : DialogWrapper(project) {

    private val disks: Map<String, String> = config.disks()
    private val diskCombo = JComboBox(DefaultComboBoxModel(disks.keys.toTypedArray()))
    private val incrementSpinner = JBIntSpinner(8, 1, 65_536)

    init {
        title = "Resize Disk of ${guest.displayName}"
        setOKButtonText("Resize")
        init()
    }

    fun selectedDisk(): String = diskCombo.selectedItem as String

    /** Proxmox takes a `+<n>G` increment; an absolute value would risk an accidental shrink. */
    fun sizeIncrement(): String = "+${incrementSpinner.number}G"

    override fun createCenterPanel(): JComponent = panel {
        row("Guest:") {
            label(guest.displayName)
        }
        row("Disk:") {
            cell(diskCombo).comment(currentSizeHint())
        }
        row("Increase by (GiB):") {
            cell(incrementSpinner)
        }
        row {
            comment(
                "Proxmox can only grow a disk. The file system inside the guest still has to be " +
                    "extended separately.",
            )
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = incrementSpinner

    override fun doValidate(): ValidationInfo? = when {
        disks.isEmpty() -> ValidationInfo("This guest has no resizable disk", diskCombo)
        else -> null
    }

    private fun currentSizeHint(): String {
        val key = diskCombo.selectedItem as? String ?: return ""
        val value = disks[key] ?: return ""
        val size = GuestConfig.parseDevice(value)["size"] ?: return value
        return "Current size: $size"
    }
}
