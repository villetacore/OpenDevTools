package com.opendevtools.proxmox.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.opendevtools.core.ui.listRenderer
import com.opendevtools.proxmox.model.CreateVmRequest
import com.opendevtools.proxmox.model.StorageVolume
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent

/** Everything the create dialogs need from the server, fetched once before they open. */
class CreateContext(
    val node: String,
    val nextVmid: Int,
    val usedVmids: Set<Int>,
    val diskStorages: List<String>,
    val isoImages: List<StorageVolume>,
    val containerTemplates: List<StorageVolume>,
    val bridges: List<String>,
    val pools: List<String>,
)

/**
 * The "Create Virtual Machine" wizard, reduced to the choices that actually matter for a first
 * boot. Anything beyond that is a configuration change on the created guest, which the plugin
 * and the web UI can both do afterwards.
 */
class CreateVmDialog(
    project: Project?,
    private val context: CreateContext,
) : DialogWrapper(project) {

    private val vmidSpinner = JBIntSpinner(context.nextVmid, 100, 999_999_999)
    private val nameField = JBTextField("vm-${context.nextVmid}")
    private val coresSpinner = JBIntSpinner(2, 1, 512)
    private val socketsSpinner = JBIntSpinner(1, 1, 16)
    private val memorySpinner = JBIntSpinner(2048, 16, 4_194_304, 512)
    private val diskSpinner = JBIntSpinner(32, 1, 65_536)

    private val storageCombo = JComboBox(DefaultComboBoxModel(context.diskStorages.toTypedArray()))
    private val isoCombo = JComboBox<StorageVolume?>().apply {
        renderer = listRenderer("Do not use any media") { it?.fileName }
        model = DefaultComboBoxModel<StorageVolume?>().apply {
            addElement(null)
            context.isoImages.forEach { addElement(it) }
        }
    }
    private val bridgeCombo = JComboBox(DefaultComboBoxModel(context.bridges.toTypedArray()))
    // A plain text field, because "no VLAN" has to be expressible and a spinner has no empty state.
    private val vlanField = JBTextField("", 6)

    private val osTypeCombo = JComboBox(DefaultComboBoxModel(OS_TYPES.keys.toTypedArray())).apply {
        renderer = listRenderer("") { OS_TYPES[it] }
        selectedItem = "l26"
    }
    private val biosCombo = JComboBox(DefaultComboBoxModel(arrayOf("seabios", "ovmf"))).apply {
        renderer = listRenderer("") {
            if (it == "ovmf") "OVMF (UEFI)" else "SeaBIOS (default)"
        }
    }
    private val scsiHwCombo = JComboBox(
        DefaultComboBoxModel(arrayOf("virtio-scsi-single", "virtio-scsi-pci", "lsi")),
    )
    private val poolCombo = JComboBox<String?>().apply {
        renderer = listRenderer("No pool") { it }
        model = DefaultComboBoxModel<String?>().apply {
            addElement(null)
            context.pools.forEach { addElement(it) }
        }
    }
    private val agentCheckBox = JBCheckBox("Enable the QEMU guest agent", true)
    private val startCheckBox = JBCheckBox("Start after creation", false)
    private val descriptionField = JBTextField()

    init {
        title = "Create Virtual Machine on ${context.node}"
        setOKButtonText("Create")
        init()
    }

    fun request(): CreateVmRequest = CreateVmRequest(
        node = context.node,
        vmid = vmidSpinner.number,
        name = nameField.text.trim(),
        cores = coresSpinner.number,
        sockets = socketsSpinner.number,
        memoryMib = memorySpinner.number,
        diskGib = diskSpinner.number,
        diskStorage = storageCombo.selectedItem as String,
        isoVolume = (isoCombo.selectedItem as? StorageVolume)?.volid,
        bridge = bridgeCombo.selectedItem as String,
        vlanTag = vlanField.text.trim().toIntOrNull(),
        osType = osTypeCombo.selectedItem as String,
        scsiHw = scsiHwCombo.selectedItem as String,
        bios = biosCombo.selectedItem as String,
        machine = null,
        startAfterCreate = startCheckBox.isSelected,
        agent = agentCheckBox.isSelected,
        pool = poolCombo.selectedItem as? String,
        description = descriptionField.text.trim().takeIf { it.isNotEmpty() },
    )

    override fun createCenterPanel(): JComponent = panel {
        group("General") {
            row("VMID:") { cell(vmidSpinner) }
            row("Name:") { cell(nameField).columns(30) }
            row("Pool:") { cell(poolCombo) }
            row("Description:") { cell(descriptionField).columns(30) }
        }
        group("OS") {
            row("Guest OS:") { cell(osTypeCombo) }
            row("Installation media:") { cell(isoCombo) }
            row("BIOS:") { cell(biosCombo) }
        }
        group("System") {
            row("Cores:") { cell(coresSpinner) }
            row("Sockets:") { cell(socketsSpinner) }
            row("Memory (MiB):") { cell(memorySpinner) }
            row("SCSI controller:") { cell(scsiHwCombo) }
            row { cell(agentCheckBox) }
        }
        group("Disk") {
            row("Storage:") { cell(storageCombo) }
            row("Size (GiB):") { cell(diskSpinner) }
        }
        group("Network") {
            row("Bridge:") { cell(bridgeCombo) }
            row("VLAN tag:") { cell(vlanField).comment("Leave empty for no VLAN") }
        }
        row { cell(startCheckBox) }
    }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun doValidate(): ValidationInfo? = when {
        vmidSpinner.number in context.usedVmids ->
            ValidationInfo("VMID ${vmidSpinner.number} is already in use", vmidSpinner)

        nameField.text.isBlank() -> ValidationInfo("Enter a name", nameField)
        context.diskStorages.isEmpty() ->
            ValidationInfo("No storage on this node can hold disk images", storageCombo)

        context.bridges.isEmpty() -> ValidationInfo("No network bridge found on this node", bridgeCombo)
        !isVlanValid() -> ValidationInfo("VLAN tag must be a number between 1 and 4094", vlanField)
        else -> null
    }

    /** An empty VLAN field means "untagged", which is a valid choice rather than a missing one. */
    private fun isVlanValid(): Boolean {
        val text = vlanField.text.trim()
        return text.isEmpty() || text.toIntOrNull()?.let { it in 1..4094 } == true
    }

    private companion object {
        val OS_TYPES = linkedMapOf(
            "l26" to "Linux 6.x - 2.6 kernel",
            "l24" to "Linux 2.4 kernel",
            "win11" to "Windows 11 / 2022",
            "win10" to "Windows 10 / 2016 / 2019",
            "win8" to "Windows 8 / 2012",
            "win7" to "Windows 7 / 2008 R2",
            "solaris" to "Solaris kernel",
            "other" to "Other",
        )
    }
}
