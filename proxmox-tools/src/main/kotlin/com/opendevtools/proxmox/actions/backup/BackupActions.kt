package com.opendevtools.proxmox.actions.backup

import com.intellij.openapi.actionSystem.AnActionEvent
import com.opendevtools.proxmox.actions.ProxmoxAction
import com.opendevtools.proxmox.actions.TaskRequest
import com.opendevtools.proxmox.dialogs.BackupDialog
import com.opendevtools.proxmox.dialogs.RestoreDialog
import com.opendevtools.proxmox.model.StorageContentType
import com.opendevtools.proxmox.service.ProxmoxService
import com.opendevtools.proxmox.toolwindow.ProxmoxDataKeys
import com.opendevtools.proxmox.util.ProxmoxNotifier

/** Starts a one-off vzdump for the selected guests. */
class BackupNowAction : ProxmoxAction() {

    override fun update(e: AnActionEvent) {
        val guests = e.getData(ProxmoxDataKeys.GUESTS).orEmpty()
        // vzdump runs on one node, so a cross-node selection cannot be expressed as one job.
        e.presentation.isEnabled = guests.isNotEmpty() && guests.map { it.node }.distinct().size == 1
    }

    override fun actionPerformed(e: AnActionEvent) {
        val guests = e.getData(ProxmoxDataKeys.GUESTS).orEmpty()
        if (guests.isEmpty()) return
        val node = guests.first().node

        runBackground(
            project = e.project,
            title = "Looking for backup storages",
            compute = { ProxmoxService.getInstance().storagesFor(node, StorageContentType.BACKUP) },
            onSuccess = { storages ->
                if (storages.isEmpty()) {
                    ProxmoxNotifier.warn(e.project, "No storage on $node accepts backups")
                    return@runBackground
                }
                val dialog = BackupDialog(e.project, guests, storages)
                if (!dialog.showAndGet()) return@runBackground
                val request = dialog.request()
                runTasks(
                    e,
                    "Proxmox: Backup",
                    listOf(
                        TaskRequest(node, "Back up ${guests.joinToString(", ") { it.displayName }}") {
                            ProxmoxService.getInstance().client().nodes.backup(request)
                        },
                    ),
                )
            },
        )
    }
}

/** Restores the backup archive selected in the Backups or Content tab. */
class RestoreBackupAction : ProxmoxAction() {

    override fun update(e: AnActionEvent) {
        val volume = e.getData(ProxmoxDataKeys.VOLUME)
        e.presentation.isEnabled = volume?.isBackup == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val archive = e.getData(ProxmoxDataKeys.VOLUME) ?: return
        if (!archive.isBackup) return
        val panel = panel(e)

        runBackground(
            project = e.project,
            title = "Preparing the restore dialog",
            compute = {
                val service = ProxmoxService.getInstance()
                val snapshot = panel?.currentSnapshot() ?: service.cachedSnapshot()
                val used = snapshot?.allGuests?.map { it.vmid }?.toSet().orEmpty()
                Triple(
                    runCatching { service.client().cluster.nextVmid() }.getOrNull() ?: 100,
                    used,
                    service.storagesFor(archive.node, StorageContentType.IMAGES) +
                        service.storagesFor(archive.node, StorageContentType.ROOTDIR),
                )
            },
            onSuccess = { (nextVmid, used, storages) ->
                val dialog = RestoreDialog(
                    e.project,
                    archive,
                    nextVmid,
                    used,
                    storages.distinct().sorted(),
                )
                if (!dialog.showAndGet()) return@runBackground
                val request = dialog.request()
                runTasks(
                    e,
                    "Proxmox: Restore",
                    listOf(
                        TaskRequest(archive.node, "Restore ${archive.fileName} as ${request.vmid}") {
                            ProxmoxService.getInstance().client().nodes.restore(request)
                        },
                    ),
                )
            },
        )
    }
}

/** Deletes the selected volume - a backup archive, an ISO or an orphaned disk image. */
class DeleteVolumeAction : ProxmoxAction() {

    override fun update(e: AnActionEvent) {
        val volume = e.getData(ProxmoxDataKeys.VOLUME)
        e.presentation.isEnabled = volume != null && !volume.protectedVolume
        e.presentation.description = when {
            volume == null -> "Select a volume first"
            volume.protectedVolume -> "This volume is protected from removal"
            else -> "Delete ${volume.fileName}"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val volume = e.getData(ProxmoxDataKeys.VOLUME) ?: return
        if (!confirm(
                e.project,
                "Delete Volume",
                "Delete ${volume.fileName} from ${volume.storage}?\n\nThis cannot be undone.",
                "Delete",
            )
        ) {
            return
        }
        runTasks(
            e,
            "Proxmox: Delete Volume",
            listOf(
                TaskRequest(volume.node, "Delete ${volume.fileName}") {
                    ProxmoxService.getInstance().client().storages
                        .deleteVolume(volume.node, volume.storage, volume.volid)
                },
            ),
        )
    }
}

/** Toggles the protection flag that keeps a backup out of the retention pruning. */
class ToggleBackupProtectionAction : ProxmoxAction() {

    override fun update(e: AnActionEvent) {
        val volume = e.getData(ProxmoxDataKeys.VOLUME)
        e.presentation.isEnabled = volume?.isBackup == true
        e.presentation.text = if (volume?.protectedVolume == true) "Remove Protection" else "Protect"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val volume = e.getData(ProxmoxDataKeys.VOLUME) ?: return
        val newValue = !volume.protectedVolume
        runTasks(
            e,
            "Proxmox: Update Backup",
            listOf(
                TaskRequest(volume.node, "Update ${volume.fileName}") {
                    ProxmoxService.getInstance().client().storages.updateVolumeAttributes(
                        volume.node,
                        volume.storage,
                        volume.volid,
                        notes = null,
                        protectVolume = newValue,
                    )
                    null
                },
            ),
        )
    }
}
