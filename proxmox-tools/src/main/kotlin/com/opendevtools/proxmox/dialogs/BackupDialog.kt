package com.opendevtools.proxmox.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.opendevtools.core.ui.listRenderer
import com.opendevtools.proxmox.model.BackupCompression
import com.opendevtools.proxmox.model.BackupMode
import com.opendevtools.proxmox.model.BackupNotification
import com.opendevtools.proxmox.model.BackupRequest
import com.opendevtools.proxmox.model.ProxmoxGuest
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent

/** A one-off `vzdump` run for the selected guests, with the options the web UI's Backup Now offers. */
class BackupDialog(
    project: Project?,
    private val guests: List<ProxmoxGuest>,
    private val storages: List<String>,
) : DialogWrapper(project) {

    private val storageCombo = JComboBox(DefaultComboBoxModel(storages.toTypedArray()))

    private val modeCombo = JComboBox(DefaultComboBoxModel(BackupMode.entries.toTypedArray())).apply {
        renderer = listRenderer("") { it.title }
        selectedItem = BackupMode.SNAPSHOT
    }

    private val compressionCombo =
        JComboBox(DefaultComboBoxModel(BackupCompression.entries.toTypedArray())).apply {
            renderer = listRenderer("") { it.title }
            selectedItem = BackupCompression.ZSTD
        }

    private val notesField = JBTextField("{{guestname}}")
    private val protectCheckBox = JBCheckBox("Protect the archive from pruning", false)
    private val removeCheckBox = JBCheckBox("Remove older backups according to the retention settings", false)
    private val mailField = JBTextField()
    private val notificationCombo =
        JComboBox(DefaultComboBoxModel(BackupNotification.entries.toTypedArray())).apply {
            renderer = listRenderer("") { it.title }
            selectedItem = BackupNotification.FAILURE
        }

    init {
        title = if (guests.size == 1) "Back Up ${guests.first().displayName}" else "Back Up ${guests.size} Guests"
        setOKButtonText("Start Backup")
        init()
    }

    fun request(): BackupRequest = BackupRequest(
        // vzdump runs on one node; every selected guest is on the same node by construction.
        node = guests.first().node,
        vmids = guests.map { it.vmid },
        storage = storageCombo.selectedItem as String,
        mode = modeCombo.selectedItem as BackupMode,
        compression = compressionCombo.selectedItem as BackupCompression,
        notes = notesField.text.trim().takeIf { it.isNotEmpty() },
        protectBackup = protectCheckBox.isSelected,
        removeOlder = removeCheckBox.isSelected,
        mailTo = mailField.text.trim().takeIf { it.isNotEmpty() },
        notification = notificationCombo.selectedItem as BackupNotification,
    )

    override fun createCenterPanel(): JComponent = panel {
        row("Guests:") {
            label(guests.joinToString(", ") { it.displayName }.take(120))
        }
        row("Node:") {
            label(guests.first().node)
        }
        row("Storage:") {
            cell(storageCombo)
        }
        row("Mode:") {
            cell(modeCombo).comment((modeCombo.selectedItem as BackupMode).hint)
        }
        row("Compression:") {
            cell(compressionCombo)
        }
        row("Notes template:") {
            cell(notesField)
                .columns(30)
                .comment("Supports {{guestname}}, {{node}}, {{vmid}} and {{cluster}}")
        }
        row {
            cell(protectCheckBox)
        }
        row {
            cell(removeCheckBox)
        }
        row("Notify:") {
            cell(mailField).columns(24)
        }
        row("Send mail:") {
            cell(notificationCombo)
        }
    }

    override fun doValidate(): ValidationInfo? = when {
        storages.isEmpty() ->
            ValidationInfo("No storage on this node accepts backups", storageCombo)

        storageCombo.selectedItem == null -> ValidationInfo("Choose a storage", storageCombo)
        guests.map { it.node }.distinct().size > 1 ->
            ValidationInfo("Select guests from a single node; vzdump runs per node", storageCombo)

        else -> null
    }
}
