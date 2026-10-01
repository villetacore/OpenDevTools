package com.opendevtools.proxmox.toolwindow.details

import com.intellij.openapi.project.Project
import com.intellij.ui.PopupHandler
import com.intellij.util.ui.ColumnInfo
import com.opendevtools.core.format.Formats
import com.opendevtools.proxmox.ProxmoxIcons
import com.opendevtools.proxmox.model.ProxmoxGuest
import com.opendevtools.proxmox.model.StorageVolume
import com.opendevtools.proxmox.service.ProxmoxService

/** Backup archives of the selected guest, collected from every backup storage on its node. */
class BackupsTab(project: Project) : TableDetailTab<StorageVolume>(
    project,
    "Backups",
    ProxmoxIcons.Backup,
    "Select a virtual machine or a container",
) {

    private var currentGuest: ProxmoxGuest? = null

    init {
        PopupHandler.installPopupMenu(table, "ProxmoxTools.BackupMenu", "ProxmoxBackupPopup")
    }

    override fun supports(target: DetailTarget): Boolean = target is DetailTarget.Guest

    override fun columns(): Array<ColumnInfo<StorageVolume, *>> = arrayOf(
        column("Created", 18) { Formats.timestamp(it.createdAt) },
        column("Storage", 12) { it.storage },
        column("Size", 10) { Formats.bytes(it.size) },
        column("Format", 10) { it.format },
        column("Protected", 9) { if (it.protectedVolume) "yes" else "" },
        column("Verified", 10) { it.verification },
        column("Notes", 30) { it.notes },
    )

    override fun loadData(target: DetailTarget) {
        val guest = (target as? DetailTarget.Guest)?.guest ?: run {
            currentGuest = null
            setRows(emptyList())
            return
        }
        currentGuest = guest
        showLoading()
        load(
            compute = { ProxmoxService.getInstance().guestBackups(guest) },
            onError = { showError(it) },
            onSuccess = { setRows(it, "No backups on this node") },
        )
    }

    fun currentGuest(): ProxmoxGuest? = currentGuest

    fun selectedBackup(): StorageVolume? = selectedRow()
}
