package com.opendevtools.proxmox.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.opendevtools.proxmox.model.GuestSnapshot
import com.opendevtools.proxmox.model.GuestType
import com.opendevtools.proxmox.model.ProxmoxGuest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.swing.JComponent

/** What the user typed into [SnapshotDialog]. */
class SnapshotSpec(val name: String, val description: String?, val includeRam: Boolean)

/**
 * Asks for a snapshot name, a description and whether the RAM should be captured.
 * The default name is timestamped, because a snapshot without a hint of when it was taken is
 * close to useless once there are more than two of them.
 */
class SnapshotDialog(
    project: Project?,
    private val guest: ProxmoxGuest,
    private val existingNames: Set<String>,
) : DialogWrapper(project) {

    private val nameField = JBTextField(defaultName())
    private val descriptionArea = JBTextArea(3, 40)
    private val ramCheckBox = JBCheckBox("Include the RAM contents", guest.isRunning)

    init {
        title = "Take Snapshot of ${guest.displayName}"
        setOKButtonText("Take Snapshot")
        init()
    }

    fun spec(): SnapshotSpec = SnapshotSpec(
        name = nameField.text.trim(),
        description = descriptionArea.text.trim().takeIf { it.isNotEmpty() },
        includeRam = ramCheckBox.isSelected && ramCheckBox.isEnabled,
    )

    override fun createCenterPanel(): JComponent = panel {
        row("Guest:") {
            label("${guest.displayName} on ${guest.node}").gap(RightGap.SMALL)
        }
        row("Name:") {
            cell(nameField).columns(30)
        }
        row("Description:") {
            cell(descriptionArea).columns(30)
        }
        row {
                cell(ramCheckBox)
                    .enabled(guest.type == GuestType.QEMU && guest.isRunning)
                    .comment(
                        when {
                            guest.type != GuestType.QEMU -> "Containers cannot snapshot their memory"
                            !guest.isRunning -> "Only a running virtual machine has memory to capture"
                            else -> "The snapshot restores to a running machine, but takes longer and more space"
                        },
                    )
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun doValidate(): ValidationInfo? {
        val name = nameField.text.trim()
        GuestSnapshot.validateName(name)?.let { return ValidationInfo(it, nameField) }
        if (name in existingNames) {
            return ValidationInfo("A snapshot with this name already exists", nameField)
        }
        return null
    }

    private fun defaultName(): String =
        "snap_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
}
