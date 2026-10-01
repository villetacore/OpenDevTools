package com.opendevtools.proxmox.actions.power

import com.intellij.openapi.actionSystem.AnActionEvent
import com.opendevtools.proxmox.actions.ProxmoxAction
import com.opendevtools.proxmox.actions.TaskRequest
import com.opendevtools.proxmox.model.GuestAction
import com.opendevtools.proxmox.model.ProxmoxGuest
import com.opendevtools.proxmox.service.ProxmoxService
import com.opendevtools.proxmox.toolwindow.ProxmoxDataKeys

/** Runs one power transition against every selected guest that accepts it. */
abstract class GuestPowerAction(private val action: GuestAction) : ProxmoxAction() {

    override fun update(e: AnActionEvent) {
        val guests = applicableGuests(e)
        val selected = e.getData(ProxmoxDataKeys.GUESTS).orEmpty()
        e.presentation.text = action.title
        e.presentation.isEnabledAndVisible = selected.isNotEmpty()
        e.presentation.isEnabled = guests.isNotEmpty() && guests.size == selected.size
    }

    override fun actionPerformed(e: AnActionEvent) {
        val guests = applicableGuests(e)
        if (guests.isEmpty()) return

        if (action.requiresConfirmation && !confirm(
                e.project,
                "Proxmox",
                "${action.title} ${describe(guests)}?",
                action.title,
            )
        ) {
            return
        }

        runTasks(
            e,
            "Proxmox: ${action.title}",
            guests.map { guest ->
                TaskRequest(guest.node, "${action.title} ${guest.displayName}") {
                    ProxmoxService.getInstance().client().guests.powerAction(
                        guest.node,
                        guest.type,
                        guest.vmid,
                        action.api,
                    )
                }
            },
        )
    }

    private fun applicableGuests(e: AnActionEvent): List<ProxmoxGuest> =
        e.getData(ProxmoxDataKeys.GUESTS).orEmpty().filter { action.isApplicable(it) }

    private fun describe(guests: List<ProxmoxGuest>): String =
        if (guests.size == 1) guests.first().displayName else "${guests.size} guests"
}

class StartGuestAction : GuestPowerAction(GuestAction.START)
class ShutdownGuestAction : GuestPowerAction(GuestAction.SHUTDOWN)
class StopGuestAction : GuestPowerAction(GuestAction.STOP)
class RebootGuestAction : GuestPowerAction(GuestAction.REBOOT)
class ResetGuestAction : GuestPowerAction(GuestAction.RESET)
class SuspendGuestAction : GuestPowerAction(GuestAction.SUSPEND)
class ResumeGuestAction : GuestPowerAction(GuestAction.RESUME)

/** Starts every guest on the selected nodes that is marked "start at boot". */
class StartAllGuestsAction : ProxmoxAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.getData(ProxmoxDataKeys.NODES).orEmpty().isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val nodes = e.getData(ProxmoxDataKeys.NODES).orEmpty()
        if (nodes.isEmpty()) return
        if (!confirm(
                e.project,
                "Proxmox",
                "Start all guests on ${nodes.joinToString(", ") { it.name }}?",
                "Start All",
            )
        ) {
            return
        }
        runTasks(
            e,
            "Proxmox: Start All Guests",
            nodes.map { node ->
                TaskRequest(node.name, "Start all guests on ${node.name}") {
                    ProxmoxService.getInstance().client().nodes.startAllGuests(node.name)
                }
            },
        )
    }
}

/** Shuts down every running guest on the selected nodes. */
class StopAllGuestsAction : ProxmoxAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.getData(ProxmoxDataKeys.NODES).orEmpty().isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val nodes = e.getData(ProxmoxDataKeys.NODES).orEmpty()
        if (nodes.isEmpty()) return
        if (!confirm(
                e.project,
                "Proxmox",
                "Stop every running guest on ${nodes.joinToString(", ") { it.name }}?\n" +
                    "This shuts down the workloads on the node.",
                "Stop All",
            )
        ) {
            return
        }
        runTasks(
            e,
            "Proxmox: Stop All Guests",
            nodes.map { node ->
                TaskRequest(node.name, "Stop all guests on ${node.name}") {
                    ProxmoxService.getInstance().client().nodes.stopAllGuests(node.name)
                }
            },
        )
    }
}
