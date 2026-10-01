package com.opendevtools.proxmox.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.opendevtools.core.ui.listRenderer
import com.opendevtools.proxmox.model.CreateContainerRequest
import com.opendevtools.proxmox.model.StorageVolume
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent

/**
 * The "Create Container" wizard. The root password is passed straight to the create call and is
 * never stored anywhere by the plugin; the field is cleared as soon as the request is built.
 */
class CreateContainerDialog(
    project: Project?,
    private val context: CreateContext,
) : DialogWrapper(project) {

    private val vmidSpinner = JBIntSpinner(context.nextVmid, 100, 999_999_999)
    private val hostnameField = JBTextField("ct-${context.nextVmid}")
    private val templateCombo = JComboBox(
        DefaultComboBoxModel(context.containerTemplates.toTypedArray()),
    ).apply {
        renderer = listRenderer("") { it.fileName }
    }
    private val passwordField = JBPasswordField()
    private val sshKeyArea = JBTextArea(3, 40)

    private val coresSpinner = JBIntSpinner(1, 1, 512)
    private val memorySpinner = JBIntSpinner(512, 16, 4_194_304, 256)
    private val swapSpinner = JBIntSpinner(512, 0, 4_194_304, 256)
    private val diskSpinner = JBIntSpinner(8, 1, 65_536)

    private val storageCombo = JComboBox(DefaultComboBoxModel(context.diskStorages.toTypedArray()))
    private val bridgeCombo = JComboBox(DefaultComboBoxModel(context.bridges.toTypedArray()))
    private val ipField = JBTextField("dhcp")
    private val gatewayField = JBTextField()
    // A plain text field, because "no VLAN" has to be expressible and a spinner has no empty state.
    private val vlanField = JBTextField("", 6)

    private val unprivilegedCheckBox = JBCheckBox("Unprivileged container", true)
    private val nestingCheckBox = JBCheckBox("Allow nesting (needed for Docker inside the container)", false)
    private val startCheckBox = JBCheckBox("Start after creation", false)
    private val poolCombo = JComboBox<String?>().apply {
        renderer = listRenderer("No pool") { it }
        model = DefaultComboBoxModel<String?>().apply {
            addElement(null)
            context.pools.forEach { addElement(it) }
        }
    }
    private val descriptionField = JBTextField()

    init {
        title = "Create Container on ${context.node}"
        setOKButtonText("Create")
        init()
    }

    fun request(): CreateContainerRequest {
        val password = String(passwordField.password)
        passwordField.text = ""
        return CreateContainerRequest(
            node = context.node,
            vmid = vmidSpinner.number,
            hostname = hostnameField.text.trim(),
            template = (templateCombo.selectedItem as StorageVolume).volid,
            password = password.takeIf { it.isNotEmpty() },
            sshPublicKey = sshKeyArea.text.trim().takeIf { it.isNotEmpty() },
            cores = coresSpinner.number,
            memoryMib = memorySpinner.number,
            swapMib = swapSpinner.number,
            diskGib = diskSpinner.number,
            diskStorage = storageCombo.selectedItem as String,
            bridge = bridgeCombo.selectedItem as String,
            ipv4 = ipField.text.trim().ifBlank { "dhcp" },
            gateway = gatewayField.text.trim().takeIf { it.isNotEmpty() },
            vlanTag = vlanField.text.trim().toIntOrNull(),
            nesting = nestingCheckBox.isSelected,
            unprivileged = unprivilegedCheckBox.isSelected,
            startAfterCreate = startCheckBox.isSelected,
            pool = poolCombo.selectedItem as? String,
            description = descriptionField.text.trim().takeIf { it.isNotEmpty() },
        )
    }

    override fun createCenterPanel(): JComponent = panel {
        group("General") {
            row("VMID:") { cell(vmidSpinner) }
            row("Hostname:") { cell(hostnameField).columns(30) }
            row("Template:") { cell(templateCombo) }
            row("Pool:") { cell(poolCombo) }
            row("Description:") { cell(descriptionField).columns(30) }
        }
        group("Access") {
            row("Root password:") {
                cell(passwordField).columns(30)
            }
            row("SSH public key:") {
                cell(sshKeyArea).columns(30).comment("Either a password or a key is required")
            }
            row { cell(unprivilegedCheckBox) }
            row { cell(nestingCheckBox) }
        }
        group("Resources") {
            row("Cores:") { cell(coresSpinner) }
            row("Memory (MiB):") { cell(memorySpinner) }
            row("Swap (MiB):") { cell(swapSpinner) }
            row("Disk storage:") { cell(storageCombo) }
            row("Disk size (GiB):") { cell(diskSpinner) }
        }
        group("Network") {
            row("Bridge:") { cell(bridgeCombo) }
            row("IPv4:") {
                cell(ipField).columns(20).comment("`dhcp`, or a CIDR address such as 192.168.1.50/24")
            }
            row("Gateway:") { cell(gatewayField).columns(20) }
            row("VLAN tag:") { cell(vlanField) }
        }
        row { cell(startCheckBox) }
    }

    override fun getPreferredFocusedComponent(): JComponent = hostnameField

    override fun doValidate(): ValidationInfo? {
        if (vmidSpinner.number in context.usedVmids) {
            return ValidationInfo("VMID ${vmidSpinner.number} is already in use", vmidSpinner)
        }
        if (hostnameField.text.isBlank()) return ValidationInfo("Enter a hostname", hostnameField)
        if (context.containerTemplates.isEmpty()) {
            return ValidationInfo("No container template found on this node", templateCombo)
        }
        if (context.diskStorages.isEmpty()) {
            return ValidationInfo("No storage on this node can hold container volumes", storageCombo)
        }
        if (passwordField.password.isEmpty() && sshKeyArea.text.isBlank()) {
            return ValidationInfo("Set a root password or an SSH key", passwordField)
        }
        val ip = ipField.text.trim()
        if (ip.isNotBlank() && ip != "dhcp" && !ip.contains('/')) {
            return ValidationInfo("Use CIDR notation, for example 192.168.1.50/24", ipField)
        }
        val vlan = vlanField.text.trim()
        if (vlan.isNotEmpty() && vlan.toIntOrNull()?.let { it in 1..4094 } != true) {
            return ValidationInfo("VLAN tag must be a number between 1 and 4094", vlanField)
        }
        return null
    }
}
