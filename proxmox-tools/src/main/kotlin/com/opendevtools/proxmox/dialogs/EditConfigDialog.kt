package com.opendevtools.proxmox.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.opendevtools.proxmox.model.GuestConfig
import com.opendevtools.proxmox.model.GuestType
import com.opendevtools.proxmox.model.ProxmoxGuest
import javax.swing.JComponent

/**
 * Edits the options a person actually changes from day to day - name, CPU, memory, boot
 * behaviour, tags and notes. Devices are left to the web UI on purpose: a disk or NIC change has
 * far more validation behind it than a form like this could reproduce faithfully.
 *
 * Only fields the user actually modified are sent, so a concurrent change to an untouched key is
 * not silently reverted.
 */
class EditConfigDialog(
    project: Project?,
    private val guest: ProxmoxGuest,
    private val config: GuestConfig,
) : DialogWrapper(project) {

    private val nameKey = if (guest.type == GuestType.QEMU) "name" else "hostname"

    private val nameField = JBTextField(config.entries[nameKey].orEmpty())
    private val coresSpinner = JBIntSpinner(config.entries["cores"]?.toIntOrNull() ?: 1, 1, 512)
    private val socketsSpinner = JBIntSpinner(config.entries["sockets"]?.toIntOrNull() ?: 1, 1, 16)
    private val memorySpinner =
        JBIntSpinner(config.entries["memory"]?.toIntOrNull() ?: 512, 16, 4_194_304, 256)
    private val swapSpinner = JBIntSpinner(config.entries["swap"]?.toIntOrNull() ?: 0, 0, 4_194_304, 256)
    private val onBootCheckBox = JBCheckBox("Start at boot", config.entries["onboot"] == "1")
    private val protectionCheckBox = JBCheckBox(
        "Protection (blocks removal and disk deletion)",
        config.entries["protection"] == "1",
    )
    private val agentCheckBox = JBCheckBox(
        "QEMU guest agent",
        config.entries["agent"]?.startsWith("1") == true,
    )
    private val tagsField = JBTextField(config.entries["tags"].orEmpty())
    private val descriptionArea = JBTextArea(4, 40).apply {
        text = config.entries["description"].orEmpty()
    }

    init {
        title = "Edit ${guest.displayName}"
        setOKButtonText("Apply")
        init()
    }

    /** Only the keys whose value actually differs from what the server reported. */
    fun changedParameters(): Map<String, String> = buildMap {
        putIfChanged(nameKey, nameField.text.trim())
        putIfChanged("cores", coresSpinner.number.toString())
        if (guest.type == GuestType.QEMU) putIfChanged("sockets", socketsSpinner.number.toString())
        putIfChanged("memory", memorySpinner.number.toString())
        if (guest.type == GuestType.LXC) putIfChanged("swap", swapSpinner.number.toString())
        putIfChanged("onboot", if (onBootCheckBox.isSelected) "1" else "0")
        putIfChanged("protection", if (protectionCheckBox.isSelected) "1" else "0")
        if (guest.type == GuestType.QEMU) {
            putIfChanged("agent", if (agentCheckBox.isSelected) "1" else "0")
        }
        putIfChanged("tags", tagsField.text.trim())
        putIfChanged("description", descriptionArea.text)

        // The digest makes the server reject the update if the config changed meanwhile.
        config.digest?.let { put("digest", it) }
    }

    private fun MutableMap<String, String>.putIfChanged(key: String, value: String) {
        val current = config.entries[key].orEmpty()
        // A boolean that was never set reads as empty; treat that as its documented default of 0.
        val normalizedCurrent = if (current.isEmpty() && value in setOf("0", "1")) "0" else current
        if (normalizedCurrent != value) put(key, value)
    }

    override fun createCenterPanel(): JComponent = panel {
        row(if (guest.type == GuestType.QEMU) "Name:" else "Hostname:") {
            cell(nameField).columns(30)
        }
        group("Resources") {
            row("Cores:") {
                cell(coresSpinner)
            }
            if (guest.type == GuestType.QEMU) {
                row("Sockets:") {
                    cell(socketsSpinner)
                }
            }
            row("Memory (MiB):") {
                cell(memorySpinner)
            }
            if (guest.type == GuestType.LXC) {
                row("Swap (MiB):") {
                    cell(swapSpinner)
                }
            }
            row {
                comment(
                    if (guest.isRunning) {
                        "The guest is running; Proxmox queues most of these until the next reboot."
                    } else {
                        "The guest is stopped, so the changes take effect on the next start."
                    },
                )
            }
        }
        group("Options") {
            row { cell(onBootCheckBox) }
            row { cell(protectionCheckBox) }
            if (guest.type == GuestType.QEMU) {
                row {
                    cell(agentCheckBox)
                        .comment("Needs qemu-guest-agent installed inside the guest to have any effect")
                }
            }
            row("Tags:") {
                cell(tagsField).columns(30).comment("Semicolon separated")
            }
            row("Notes:") {
                cell(descriptionArea).columns(30)
            }
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun doValidate(): ValidationInfo? =
        if (nameField.text.isBlank()) ValidationInfo("Enter a name", nameField) else null
}
