package com.opendevtools.proxmox.actions.snapshot

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.opendevtools.proxmox.actions.ProxmoxAction
import com.opendevtools.proxmox.actions.TaskRequest
import com.opendevtools.proxmox.dialogs.SnapshotDialog
import com.opendevtools.proxmox.model.GuestSnapshot
import com.opendevtools.proxmox.model.ProxmoxGuest
import com.opendevtools.proxmox.service.ProxmoxService
import com.opendevtools.proxmox.toolwindow.ProxmoxDataKeys

/** Takes a snapshot of the selected guest. */
class CreateSnapshotAction : ProxmoxAction() {

    override fun update(e: AnActionEvent) {
        val guest = singleGuest(e)
        e.presentation.isEnabled = guest != null && !guest.template && guest.lock == null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val guest = singleGuest(e) ?: return
        runBackground(
            project = e.project,
            title = "Reading existing snapshots",
            compute = {
                runCatching { ProxmoxService.getInstance().snapshots(guest) }
                    .getOrDefault(emptyList())
                    .map { it.name }
                    .toSet()
            },
            onSuccess = { existing ->
                val dialog = SnapshotDialog(e.project, guest, existing)
                if (!dialog.showAndGet()) return@runBackground
                val spec = dialog.spec()
                runTasks(
                    e,
                    "Proxmox: Take Snapshot",
                    listOf(
                        TaskRequest(guest.node, "Snapshot ${guest.displayName} as ${spec.name}") {
                            ProxmoxService.getInstance().client().guests.createSnapshot(
                                guest.node,
                                guest.type,
                                guest.vmid,
                                spec.name,
                                spec.description,
                                spec.includeRam,
                            )
                        },
                    ),
                )
            },
        )
    }

    private fun singleGuest(e: AnActionEvent): ProxmoxGuest? =
        e.getData(ProxmoxDataKeys.GUESTS)?.singleOrNull()
}

/** Base for the actions that operate on the snapshot selected in the Snapshots tab. */
abstract class SelectedSnapshotAction : ProxmoxAction() {

    protected fun guest(e: AnActionEvent): ProxmoxGuest? =
        e.getData(ProxmoxDataKeys.GUESTS)?.singleOrNull()

    protected fun snapshot(e: AnActionEvent): GuestSnapshot? = e.getData(ProxmoxDataKeys.SNAPSHOT)

    override fun update(e: AnActionEvent) {
        val snapshot = snapshot(e)
        e.presentation.isEnabled = guest(e) != null && snapshot != null && !snapshot.isCurrentState
    }
}

/**
 * Rolls the guest back to a snapshot. This discards everything written since the snapshot was
 * taken, so it always asks, even when confirmations are otherwise off.
 */
class RollbackSnapshotAction : SelectedSnapshotAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val guest = guest(e) ?: return
        val snapshot = snapshot(e) ?: return

        val confirmed = Messages.showYesNoDialog(
            e.project,
            "Roll ${guest.displayName} back to '${snapshot.name}'?\n\n" +
                "Everything written since the snapshot was taken is discarded." +
                if (guest.isRunning) "\nThe guest is running and will be stopped first." else "",
            "Roll Back Snapshot",
            "Roll Back",
            Messages.getCancelButton(),
            Messages.getWarningIcon(),
        ) == Messages.YES
        if (!confirmed) return

        val restart = guest.isRunning && snapshot.withRam
        runTasks(
            e,
            "Proxmox: Roll Back Snapshot",
            listOf(
                TaskRequest(guest.node, "Roll ${guest.displayName} back to ${snapshot.name}") {
                    ProxmoxService.getInstance().client().guests.rollbackSnapshot(
                        guest.node,
                        guest.type,
                        guest.vmid,
                        snapshot.name,
                        restart,
                    )
                },
            ),
        )
    }
}

class DeleteSnapshotAction : SelectedSnapshotAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val guest = guest(e) ?: return
        val snapshot = snapshot(e) ?: return
        if (!confirm(
                e.project,
                "Delete Snapshot",
                "Delete snapshot '${snapshot.name}' of ${guest.displayName}?",
                "Delete",
            )
        ) {
            return
        }
        runTasks(
            e,
            "Proxmox: Delete Snapshot",
            listOf(
                TaskRequest(guest.node, "Delete snapshot ${snapshot.name} of ${guest.displayName}") {
                    ProxmoxService.getInstance().client().guests.deleteSnapshot(
                        guest.node,
                        guest.type,
                        guest.vmid,
                        snapshot.name,
                        force = false,
                    )
                },
            ),
        )
    }
}

/** Edits a snapshot's description, the one snapshot attribute Proxmox lets you change. */
class EditSnapshotDescriptionAction : SelectedSnapshotAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val guest = guest(e) ?: return
        val snapshot = snapshot(e) ?: return
        val text = Messages.showMultilineInputDialog(
            e.project,
            "Description of snapshot '${snapshot.name}'",
            "Edit Snapshot Description",
            snapshot.description.orEmpty(),
            Messages.getQuestionIcon(),
            null,
        ) ?: return

        runTasks(
            e,
            "Proxmox: Update Snapshot",
            listOf(
                TaskRequest(guest.node, "Update snapshot ${snapshot.name}") {
                    ProxmoxService.getInstance().client().guests.updateSnapshotDescription(
                        guest.node,
                        guest.type,
                        guest.vmid,
                        snapshot.name,
                        text,
                    )
                    null
                },
            ),
        )
    }
}
