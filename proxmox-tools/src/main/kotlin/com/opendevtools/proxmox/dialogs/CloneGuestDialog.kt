package com.opendevtools.proxmox.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextField
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.opendevtools.core.ui.listRenderer
import com.opendevtools.proxmox.model.CloneMode
import com.opendevtools.proxmox.model.CloneRequest
import com.opendevtools.proxmox.model.ProxmoxGuest
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent

/** Server-side data the clone dialog needs; loaded before the dialog opens. */
class CloneContext(
    val nextVmid: Int,
    val nodes: List<String>,
    val storages: List<String>,
    val pools: List<String>,
    val snapshots: List<String>,
    val usedVmids: Set<Int>,
)

/**
 * The clone dialog. A linked clone is only offered for a template, which is the same rule the
 * server enforces - offering it elsewhere would only produce a failed task.
 */
class CloneGuestDialog(
    project: Project?,
    private val source: ProxmoxGuest,
    private val context: CloneContext,
) : DialogWrapper(project) {

    private val vmidSpinner = JBIntSpinner(context.nextVmid, MIN_VMID, MAX_VMID)
    private val nameField = JBTextField(source.name + "-clone")
    private val nodeCombo = JComboBox(DefaultComboBoxModel(context.nodes.toTypedArray())).apply {
        selectedItem = source.node
    }
    private val modeCombo = JComboBox(
        DefaultComboBoxModel(
            if (source.template) CloneMode.entries.toTypedArray() else arrayOf(CloneMode.FULL),
        ),
    ).apply {
        renderer = listRenderer("") { it.title }
    }
    private val storageCombo = JComboBox<String?>().apply {
        renderer = listRenderer("Same as source") { it }
        model = DefaultComboBoxModel<String?>().apply {
            addElement(null)
            context.storages.forEach { addElement(it) }
        }
    }
    private val poolCombo = JComboBox<String?>().apply {
        renderer = listRenderer("No pool") { it }
        model = DefaultComboBoxModel<String?>().apply {
            addElement(null)
            context.pools.forEach { addElement(it) }
        }
        selectedItem = source.pool
    }
    private val snapshotCombo = JComboBox<String?>().apply {
        renderer = listRenderer("Current state") { it }
        model = DefaultComboBoxModel<String?>().apply {
            addElement(null)
            context.snapshots.forEach { addElement(it) }
        }
    }
    private val descriptionField = JBTextField()

    init {
        title = "Clone ${source.displayName}"
        setOKButtonText("Clone")
        init()
        modeCombo.addActionListener { updateStorageEnabled() }
        updateStorageEnabled()
    }

    fun request(): CloneRequest = CloneRequest(
        source = source,
        newVmid = vmidSpinner.number,
        name = nameField.text.trim(),
        targetNode = nodeCombo.selectedItem as? String,
        pool = poolCombo.selectedItem as? String,
        description = descriptionField.text.trim().takeIf { it.isNotEmpty() },
        mode = modeCombo.selectedItem as CloneMode,
        targetStorage = storageCombo.selectedItem as? String,
        snapshotName = snapshotCombo.selectedItem as? String,
    )

    override fun createCenterPanel(): JComponent = panel {
        row("Source:") {
            label("${source.displayName} (${source.type.label}) on ${source.node}")
        }
        row("Target VMID:") {
            cell(vmidSpinner)
        }
        row("Name:") {
            cell(nameField).columns(30)
        }
        row("Target node:") {
            cell(nodeCombo)
        }
        row("Mode:") {
            cell(modeCombo).comment(
                if (source.template) {
                    "A linked clone shares the template's disks and is created instantly"
                } else {
                    "Only a template can be cloned as a linked clone"
                },
            )
        }
        row("Target storage:") {
            cell(storageCombo)
        }
        row("Snapshot:") {
            cell(snapshotCombo).comment("Clone from a snapshot instead of the current state")
        }
        row("Pool:") {
            cell(poolCombo)
        }
        row("Description:") {
            cell(descriptionField).columns(30)
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = vmidSpinner

    override fun doValidate(): ValidationInfo? {
        if (vmidSpinner.number in context.usedVmids) {
            return ValidationInfo("VMID ${vmidSpinner.number} is already in use", vmidSpinner)
        }
        if (nameField.text.isBlank()) return ValidationInfo("Enter a name", nameField)
        return null
    }

    private companion object {
        const val MIN_VMID = 100
        const val MAX_VMID = 999_999_999
    }

    /** Proxmox rejects a target storage for a linked clone, because nothing is copied. */
    private fun updateStorageEnabled() {
        val full = modeCombo.selectedItem == CloneMode.FULL
        storageCombo.isEnabled = full
        if (!full) storageCombo.selectedItem = null
    }
}
