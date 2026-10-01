package com.opendevtools.proxmox.toolwindow.details

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.opendevtools.core.format.Formats
import com.opendevtools.core.ui.Property
import com.opendevtools.core.ui.PropertiesTable
import com.opendevtools.proxmox.model.GuestConfig
import com.opendevtools.proxmox.model.ProxmoxGuest
import com.opendevtools.proxmox.service.ProxmoxService
import java.awt.BorderLayout

/** One row of the hardware table: a config key plus its decoded device string. */
class ConfigEntry(val key: String, val value: String, val category: String) {

    /** `local-lvm:vm-100-disk-0,size=32G` renders as "32G on local-lvm" for the summary column. */
    val summary: String
        get() {
            val parts = GuestConfig.parseDevice(value)
            val size = parts["size"]
            val volume = parts["volume"]?.substringBefore(',')
            return listOfNotNull(size, volume?.substringBefore(':')?.takeIf { volume.contains(':') })
                .joinToString(" on ")
                .ifBlank { value }
        }
}

/**
 * The guest's configuration, split into hardware devices and plain options. Editing goes through
 * the Edit Configuration dialog rather than being inline, because Proxmox validates the whole
 * document and a half-typed value would be rejected on every keystroke.
 */
class ConfigTab(project: Project) : TableDetailTab<ConfigEntry>(
    project,
    "Configuration",
    AllIcons.Nodes.DataTables,
    "Select a virtual machine or a container",
) {

    private val optionsTable = PropertiesTable("No options")
    private val pendingLabel = JBLabel().apply {
        border = JBUI.Borders.empty(4, 8)
        icon = AllIcons.General.Warning
        isVisible = false
    }

    private var currentGuest: ProxmoxGuest? = null
    private var currentConfig: GuestConfig? = null

    init {
        // The base class fills the tab with the hardware table; here it shares the space with
        // the option sheet below it, so it is moved into a splitter instead.
        remove(tableScrollPane)
        add(pendingLabel, BorderLayout.NORTH)
        add(
            JBSplitter(true, 0.55f).apply {
                firstComponent = tableScrollPane
                secondComponent = optionsTable
            },
            BorderLayout.CENTER,
        )
    }

    override fun supports(target: DetailTarget): Boolean = target is DetailTarget.Guest

    override fun columns(): Array<ColumnInfo<ConfigEntry, *>> = arrayOf(
        column("Device", 12) { it.key },
        column("Kind", 12) { it.category },
        column("Summary", 24) { it.summary },
        column("Value", 48) { it.value },
    )

    override fun loadData(target: DetailTarget) {
        val guest = (target as? DetailTarget.Guest)?.guest ?: run {
            currentGuest = null
            currentConfig = null
            setRows(emptyList())
            optionsTable.clear()
            pendingLabel.isVisible = false
            return
        }
        currentGuest = guest
        showLoading()
        load(
            compute = { ProxmoxService.getInstance().guestConfig(guest) },
            onError = { showError(it) },
            onSuccess = { render(it) },
        )
    }

    fun currentConfig(): GuestConfig? = currentConfig

    fun currentGuest(): ProxmoxGuest? = currentGuest

    private fun render(config: GuestConfig) {
        currentConfig = config

        val devices = buildList {
            config.disks().forEach { (key, value) -> add(ConfigEntry(key, value, "Disk")) }
            config.networks().forEach { (key, value) -> add(ConfigEntry(key, value, "Network")) }
            config.unusedDisks().forEach { (key, value) -> add(ConfigEntry(key, value, "Unused disk")) }
            config.entries["ide2"]?.let { add(ConfigEntry("ide2", it, "CD/DVD")) }
        }.distinctBy { it.key }
        setRows(devices, "No devices")

        optionsTable.setProperties(optionRows(config))

        pendingLabel.isVisible = config.hasPendingChanges()
        if (config.hasPendingChanges()) {
            pendingLabel.text = "Pending changes apply after a reboot: " +
                config.pending.entries.joinToString(", ") { "${it.key}=${it.value}" }
        }
    }

    private fun optionRows(config: GuestConfig): List<Property> {
        val deviceKeys = config.disks().keys + config.networks().keys + config.unusedDisks().keys
        return config.entries
            .filterKeys { it !in deviceKeys && it != "ide2" }
            .map { (key, value) ->
                Property(OPTION_LABELS[key] ?: key, decorate(key, value))
            }
            .sortedBy { it.name }
    }

    /** Raw values are technically correct but unreadable; the few numeric ones get units. */
    private fun decorate(key: String, value: String): String = when (key) {
        "memory", "balloon" -> value.toLongOrNull()?.let { Formats.bytes(it * 1024 * 1024) } ?: value
        "swap" -> value.toLongOrNull()?.let { Formats.bytes(it * 1024 * 1024) } ?: value
        "onboot", "protection", "agent", "unprivileged" -> if (value.startsWith("1")) "yes" else value
        else -> value
    }

    private companion object {
        val OPTION_LABELS = mapOf(
            "cores" to "Cores",
            "sockets" to "Sockets",
            "cpu" to "CPU type",
            "memory" to "Memory",
            "balloon" to "Minimum memory (ballooning)",
            "swap" to "Swap",
            "name" to "Name",
            "hostname" to "Hostname",
            "ostype" to "OS type",
            "boot" to "Boot order",
            "bootdisk" to "Boot disk",
            "onboot" to "Start at boot",
            "protection" to "Protection",
            "agent" to "QEMU guest agent",
            "bios" to "BIOS",
            "machine" to "Machine type",
            "scsihw" to "SCSI controller",
            "description" to "Notes",
            "tags" to "Tags",
            "arch" to "Architecture",
            "unprivileged" to "Unprivileged container",
            "features" to "Features",
            "startup" to "Start/shutdown order",
            "hookscript" to "Hook script",
            "vmgenid" to "VM generation id",
            "smbios1" to "SMBIOS",
            "numa" to "NUMA",
            "hotplug" to "Hotplug",
            "digest" to "Configuration digest",
        )
    }
}
