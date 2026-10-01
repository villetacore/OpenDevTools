package com.opendevtools.proxmox.toolwindow.details

import com.intellij.openapi.project.Project
import com.intellij.ui.PopupHandler
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.opendevtools.core.ui.listRenderer
import com.opendevtools.core.format.Formats
import com.opendevtools.proxmox.ProxmoxIcons
import com.opendevtools.proxmox.model.ProxmoxStorage
import com.opendevtools.proxmox.model.StorageContentType
import com.opendevtools.proxmox.model.StorageVolume
import com.opendevtools.proxmox.service.ProxmoxService
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JPanel

/** Volumes on the selected storage: disk images, ISOs, templates, backups and snippets. */
class StorageContentTab(project: Project) : TableDetailTab<StorageVolume>(
    project,
    "Content",
    ProxmoxIcons.Storage,
    "Select a storage",
) {

    /** null means "everything the storage holds". */
    private val filter = JComboBox<StorageContentType?>().apply {
        renderer = listRenderer("All content") { it?.title }
    }

    private var currentStorage: ProxmoxStorage? = null

    init {
        add(
            JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2))).apply {
                add(JBLabel("Show:"))
                add(filter)
            },
            BorderLayout.NORTH,
        )
        filter.addActionListener {
            currentStorage?.let { reload(it) }
        }
        PopupHandler.installPopupMenu(table, "ProxmoxTools.VolumeMenu", "ProxmoxVolumePopup")
    }

    override fun supports(target: DetailTarget): Boolean = target is DetailTarget.Storage

    override fun columns(): Array<ColumnInfo<StorageVolume, *>> = arrayOf(
        column("Name", 40) { it.fileName },
        column("Type", 16) { it.contentType?.title },
        column("VMID", 6) { it.vmid?.toString() },
        column("Size", 10) { Formats.bytes(it.size) },
        column("Format", 10) { it.format },
        column("Created", 18) { Formats.timestamp(it.createdAt) },
        column("Notes", 24) { it.notes },
    )

    override fun loadData(target: DetailTarget) {
        val storage = (target as? DetailTarget.Storage)?.storage ?: run {
            currentStorage = null
            setRows(emptyList())
            return
        }
        currentStorage = storage
        refillFilter(storage)
        reload(storage)
    }

    fun currentStorage(): ProxmoxStorage? = currentStorage

    fun selectedVolume(): StorageVolume? = selectedRow()

    /** The filter only offers what this storage is actually configured to hold. */
    private fun refillFilter(storage: ProxmoxStorage) {
        val previous = filter.selectedItem as? StorageContentType
        val model = DefaultComboBoxModel<StorageContentType?>()
        model.addElement(null)
        storage.content.forEach { model.addElement(it) }
        filter.model = model
        filter.selectedItem = previous?.takeIf { it in storage.content }
    }

    private fun reload(storage: ProxmoxStorage) {
        val contentType = filter.selectedItem as? StorageContentType
        showLoading()
        load(
            compute = {
                ProxmoxService.getInstance().storageContent(storage.node, storage.name, contentType)
            },
            onError = { showError(it) },
            onSuccess = { setRows(it, "This storage is empty") },
        )
    }
}
