package com.opendevtools.proxmox.actions.lifecycle

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.opendevtools.proxmox.actions.ProxmoxAction
import com.opendevtools.proxmox.actions.TaskRequest
import com.opendevtools.proxmox.dialogs.CloneContext
import com.opendevtools.proxmox.dialogs.CloneGuestDialog
import com.opendevtools.proxmox.dialogs.CreateContainerDialog
import com.opendevtools.proxmox.dialogs.CreateContext
import com.opendevtools.proxmox.dialogs.CreateVmDialog
import com.opendevtools.proxmox.dialogs.EditConfigDialog
import com.opendevtools.proxmox.dialogs.MigrateGuestDialog
import com.opendevtools.proxmox.dialogs.ResizeDiskDialog
import com.opendevtools.proxmox.model.GuestType
import com.opendevtools.proxmox.model.ProxmoxGuest
import com.opendevtools.proxmox.model.StorageContentType
import com.opendevtools.proxmox.service.ProxmoxService
import com.opendevtools.proxmox.service.mapper.DetailMapper
import com.opendevtools.proxmox.toolwindow.ProxmoxDataKeys
import com.opendevtools.proxmox.util.ProxmoxNotifier

/** Shared helpers for the actions that need a single selected guest. */
abstract class SingleGuestAction : ProxmoxAction() {

    protected fun guest(e: AnActionEvent): ProxmoxGuest? =
        e.getData(ProxmoxDataKeys.GUESTS)?.singleOrNull()

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = guest(e)?.let { isEnabledFor(it) } ?: false
    }

    protected open fun isEnabledFor(guest: ProxmoxGuest): Boolean = guest.lock == null
}

class CloneGuestAction : SingleGuestAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val guest = guest(e) ?: return
        val panel = panel(e)
        runBackground(
            project = e.project,
            title = "Preparing the clone dialog",
            compute = {
                val service = ProxmoxService.getInstance()
                val client = service.client()
                val snapshot = panel?.currentSnapshot() ?: service.cachedSnapshot()
                CloneContext(
                    nextVmid = runCatching { client.cluster.nextVmid() }.getOrNull() ?: (guest.vmid + 1),
                    nodes = snapshot?.nodes?.map { it.name }.orEmpty().ifEmpty { listOf(guest.node) },
                    storages = service.storagesFor(
                        guest.node,
                        if (guest.type == GuestType.QEMU) {
                            StorageContentType.IMAGES
                        } else {
                            StorageContentType.ROOTDIR
                        },
                    ),
                    pools = snapshot?.pools?.map { it.id }.orEmpty(),
                    snapshots = runCatching { service.snapshots(guest) }
                        .getOrDefault(emptyList())
                        .filterNot { it.isCurrentState }
                        .map { it.name },
                    usedVmids = snapshot?.allGuests?.map { it.vmid }?.toSet().orEmpty(),
                )
            },
            onSuccess = { context ->
                val dialog = CloneGuestDialog(e.project, guest, context)
                if (!dialog.showAndGet()) return@runBackground
                val request = dialog.request()
                runTasks(
                    e,
                    "Proxmox: Clone",
                    listOf(
                        TaskRequest(guest.node, "Clone ${guest.displayName} to ${request.newVmid}") {
                            ProxmoxService.getInstance().client().guests.clone(request)
                        },
                    ),
                )
            },
        )
    }
}

class MigrateGuestAction : SingleGuestAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val guest = guest(e) ?: return
        val panel = panel(e)
        runBackground(
            project = e.project,
            title = "Checking migration preconditions",
            compute = {
                val service = ProxmoxService.getInstance()
                val client = service.client()
                val snapshot = panel?.currentSnapshot() ?: service.cachedSnapshot()
                val preconditions = if (guest.type == GuestType.QEMU) {
                    runCatching {
                        DetailMapper.toMigrationPreconditions(
                            client.guests.migrationPreconditions(guest.node, guest.vmid, null),
                        )
                    }.getOrNull()
                } else {
                    null
                }
                Triple(
                    preconditions,
                    snapshot?.nodes?.map { it.name }.orEmpty(),
                    service.storagesFor(
                        guest.node,
                        if (guest.type == GuestType.QEMU) {
                            StorageContentType.IMAGES
                        } else {
                            StorageContentType.ROOTDIR
                        },
                    ),
                )
            },
            onSuccess = { (preconditions, nodes, storages) ->
                if (nodes.size < 2) {
                    ProxmoxNotifier.warn(e.project, "There is no other node to migrate to")
                    return@runBackground
                }
                val dialog = MigrateGuestDialog(e.project, guest, preconditions, nodes, storages)
                if (!dialog.showAndGet()) return@runBackground
                val request = dialog.request()
                runTasks(
                    e,
                    "Proxmox: Migrate",
                    listOf(
                        TaskRequest(guest.node, "Migrate ${guest.displayName} to ${request.targetNode}") {
                            ProxmoxService.getInstance().client().guests.migrate(request)
                        },
                    ),
                )
            },
        )
    }
}

/** Destroys a guest and, optionally, the disks nothing else references. */
class DeleteGuestAction : SingleGuestAction() {

    override fun isEnabledFor(guest: ProxmoxGuest): Boolean = guest.lock == null && !guest.isRunning

    override fun actionPerformed(e: AnActionEvent) {
        val guest = guest(e) ?: return

        val answer = Messages.showYesNoCancelDialog(
            e.project,
            "Destroy ${guest.displayName} on ${guest.node}?\n\n" +
                "This cannot be undone. Choose whether unreferenced disks should be removed too.",
            "Destroy Guest",
            "Destroy with Disks",
            "Destroy, Keep Disks",
            Messages.getCancelButton(),
            Messages.getWarningIcon(),
        )
        if (answer == Messages.CANCEL) return
        val destroyDisks = answer == Messages.YES

        runTasks(
            e,
            "Proxmox: Destroy Guest",
            listOf(
                TaskRequest(guest.node, "Destroy ${guest.displayName}") {
                    ProxmoxService.getInstance().client().guests.delete(
                        guest.node,
                        guest.type,
                        guest.vmid,
                        purgeFromJobs = true,
                        destroyUnreferencedDisks = destroyDisks,
                    )
                },
            ),
        )
    }
}

/** Converts a stopped guest into a template, which is what makes linked clones possible. */
class ConvertToTemplateAction : SingleGuestAction() {

    override fun isEnabledFor(guest: ProxmoxGuest): Boolean =
        guest.lock == null && !guest.isRunning && !guest.template

    override fun actionPerformed(e: AnActionEvent) {
        val guest = guest(e) ?: return
        if (!confirm(
                e.project,
                "Convert to Template",
                "Convert ${guest.displayName} into a template?\n\n" +
                    "A template cannot be started again; it only serves as a source for clones.",
                "Convert",
            )
        ) {
            return
        }
        runTasks(
            e,
            "Proxmox: Convert to Template",
            listOf(
                TaskRequest(guest.node, "Convert ${guest.displayName} to a template") {
                    ProxmoxService.getInstance().client().guests
                        .convertToTemplate(guest.node, guest.type, guest.vmid)
                },
            ),
        )
    }
}

class EditGuestConfigAction : SingleGuestAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val guest = guest(e) ?: return
        runBackground(
            project = e.project,
            title = "Reading the guest configuration",
            compute = { ProxmoxService.getInstance().guestConfig(guest) },
            onSuccess = { config ->
                val dialog = EditConfigDialog(e.project, guest, config)
                if (!dialog.showAndGet()) return@runBackground
                val parameters = dialog.changedParameters()
                // The digest is always present as a concurrency guard, so it alone means no edit.
                if (parameters.keys.none { it != "digest" }) {
                    ProxmoxNotifier.info(e.project, "Nothing changed")
                    return@runBackground
                }
                runTasks(
                    e,
                    "Proxmox: Update Configuration",
                    listOf(
                        TaskRequest(guest.node, "Update ${guest.displayName}") {
                            ProxmoxService.getInstance().client().guests
                                .updateConfig(guest.node, guest.type, guest.vmid, parameters)
                        },
                    ),
                )
            },
        )
    }
}

class ResizeDiskAction : SingleGuestAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val guest = guest(e) ?: return
        runBackground(
            project = e.project,
            title = "Reading the guest configuration",
            compute = { ProxmoxService.getInstance().guestConfig(guest) },
            onSuccess = { config ->
                val dialog = ResizeDiskDialog(e.project, guest, config)
                if (!dialog.showAndGet()) return@runBackground
                val disk = dialog.selectedDisk()
                val increment = dialog.sizeIncrement()
                runTasks(
                    e,
                    "Proxmox: Resize Disk",
                    listOf(
                        TaskRequest(guest.node, "Resize $disk of ${guest.displayName} by $increment") {
                            ProxmoxService.getInstance().client().guests
                                .resizeDisk(guest.node, guest.type, guest.vmid, disk, increment)
                        },
                    ),
                )
            },
        )
    }
}

/** Base for the two create wizards; both need the same server-side inventory. */
abstract class CreateGuestAction(private val forContainer: Boolean) : ProxmoxAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = targetNode(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val node = targetNode(e) ?: return
        val panel = panel(e)
        runBackground(
            project = e.project,
            title = "Preparing the create wizard",
            compute = {
                val service = ProxmoxService.getInstance()
                val client = service.client()
                val snapshot = panel?.currentSnapshot() ?: service.cachedSnapshot()
                CreateContext(
                    node = node,
                    nextVmid = runCatching { client.cluster.nextVmid() }.getOrNull() ?: 100,
                    usedVmids = snapshot?.allGuests?.map { it.vmid }?.toSet().orEmpty(),
                    diskStorages = service.storagesFor(
                        node,
                        if (forContainer) StorageContentType.ROOTDIR else StorageContentType.IMAGES,
                    ),
                    isoImages = if (forContainer) {
                        emptyList()
                    } else {
                        volumesOfType(node, StorageContentType.ISO)
                    },
                    containerTemplates = if (forContainer) {
                        volumesOfType(node, StorageContentType.VZTMPL)
                    } else {
                        emptyList()
                    },
                    bridges = service.bridgesOn(node),
                    pools = snapshot?.pools?.map { it.id }.orEmpty(),
                )
            },
            onSuccess = { context -> openWizard(e, context) },
        )
    }

    protected abstract fun openWizard(e: AnActionEvent, context: CreateContext)

    private fun volumesOfType(node: String, type: StorageContentType) =
        ProxmoxService.getInstance().let { service ->
            service.storagesFor(node, type).flatMap { storage ->
                runCatching { service.storageContent(node, storage, type) }.getOrDefault(emptyList())
            }
        }

    /** The selected node, or the only node there is when the datacenter row is selected. */
    private fun targetNode(e: AnActionEvent): String? =
        e.getData(ProxmoxDataKeys.NODES)?.singleOrNull()?.name
            ?: e.getData(ProxmoxDataKeys.GUESTS)?.singleOrNull()?.node
            ?: panel(e)?.currentSnapshot()?.nodes?.singleOrNull()?.name
}

class CreateVmAction : CreateGuestAction(forContainer = false) {

    override fun openWizard(e: AnActionEvent, context: CreateContext) {
        val dialog = CreateVmDialog(e.project, context)
        if (!dialog.showAndGet()) return
        val request = dialog.request()
        runTasks(
            e,
            "Proxmox: Create Virtual Machine",
            listOf(
                TaskRequest(context.node, "Create VM ${request.vmid} (${request.name})") {
                    ProxmoxService.getInstance().client().nodes
                        .createVm(context.node, request.toParameters())
                },
            ),
        )
    }
}

class CreateContainerAction : CreateGuestAction(forContainer = true) {

    override fun openWizard(e: AnActionEvent, context: CreateContext) {
        val dialog = CreateContainerDialog(e.project, context)
        if (!dialog.showAndGet()) return
        val request = dialog.request()
        runTasks(
            e,
            "Proxmox: Create Container",
            listOf(
                TaskRequest(context.node, "Create container ${request.vmid} (${request.hostname})") {
                    ProxmoxService.getInstance().client().nodes
                        .createContainer(context.node, request.toParameters())
                },
            ),
        )
    }
}
