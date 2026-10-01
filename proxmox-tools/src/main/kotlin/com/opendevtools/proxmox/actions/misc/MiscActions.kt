package com.opendevtools.proxmox.actions.misc

import com.intellij.ide.CopyPasteManagerEx
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.opendevtools.proxmox.actions.ProxmoxAction
import com.opendevtools.proxmox.actions.TaskRequest
import com.opendevtools.proxmox.model.TreeGrouping
import com.opendevtools.proxmox.service.ProxmoxService
import com.opendevtools.proxmox.settings.ConsolePreference
import com.opendevtools.proxmox.settings.ProxmoxSettings
import com.opendevtools.proxmox.toolwindow.ProxmoxConsoleLauncher
import com.opendevtools.proxmox.toolwindow.ProxmoxDataKeys
import com.opendevtools.proxmox.toolwindow.tasks.TaskLogDialog
import java.awt.datatransfer.StringSelection

class RefreshAction : ProxmoxAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = panel(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        ProxmoxService.getInstance().invalidate()
        panel(e)?.refreshAll()
    }
}

class OpenConsoleAction : ProxmoxAction() {

    override fun update(e: AnActionEvent) {
        val guests = e.getData(ProxmoxDataKeys.GUESTS).orEmpty()
        val nodes = e.getData(ProxmoxDataKeys.NODES).orEmpty()
        e.presentation.isEnabled = guests.isNotEmpty() || nodes.isNotEmpty()
        e.presentation.text = if (guests.isEmpty() && nodes.isNotEmpty()) "Open Node Shell" else "Open Console"
    }

    override fun actionPerformed(e: AnActionEvent) {
        ProxmoxConsoleLauncher.open(
            e.project,
            e.getData(ProxmoxDataKeys.GUESTS).orEmpty(),
            e.getData(ProxmoxDataKeys.NODES).orEmpty(),
        )
    }
}

/** Opens the serial console explicitly, which is much lighter over a slow link than noVNC. */
class OpenSerialConsoleAction : ProxmoxAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(ProxmoxDataKeys.GUESTS).orEmpty().isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        ProxmoxConsoleLauncher.open(
            e.project,
            e.getData(ProxmoxDataKeys.GUESTS).orEmpty(),
            emptyList(),
            ConsolePreference.XTERM,
        )
    }
}

class OpenInWebUiAction : ProxmoxAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(ProxmoxDataKeys.GUESTS)?.size == 1
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.getData(ProxmoxDataKeys.GUESTS)?.singleOrNull()?.let {
            ProxmoxConsoleLauncher.openWebUi(e.project, it)
        }
    }
}

class CopyVmidAction : ProxmoxAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(ProxmoxDataKeys.GUESTS).orEmpty().isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val text = e.getData(ProxmoxDataKeys.GUESTS).orEmpty().joinToString("\n") { it.vmid.toString() }
        if (text.isNotEmpty()) CopyPasteManagerEx.getInstance().setContents(StringSelection(text))
    }
}

class OpenSettingsAction : ProxmoxAction() {

    override fun actionPerformed(e: AnActionEvent) {
        panel(e)?.openSettings()
    }
}

/** Filters the tree down to guests that are currently running. */
class ToggleRunningOnlyAction : ToggleAction("Running Only", "Show only running guests", null) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun isSelected(e: AnActionEvent): Boolean =
        e.getData(ProxmoxDataKeys.PANEL)?.runningOnly == true

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        e.getData(ProxmoxDataKeys.PANEL)?.runningOnly = state
    }
}

/** One entry of the grouping menu; the choice is a persistent setting, like in the web UI. */
class SelectGroupingAction(private val grouping: TreeGrouping) :
    ToggleAction(grouping.title, "Group the tree by ${grouping.title}", null) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun isSelected(e: AnActionEvent): Boolean =
        ProxmoxSettings.getInstance().grouping == grouping

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        if (!state) return
        ProxmoxSettings.getInstance().grouping = grouping
        ProxmoxSettings.fireChanged()
    }
}

class GroupingActionGroup : DefaultActionGroup(
    "View",
    TreeGrouping.entries.map { SelectGroupingAction(it) },
) {
    init {
        isPopup = true
        templatePresentation.icon = com.intellij.icons.AllIcons.Actions.GroupBy
    }
}

// --- Task actions -------------------------------------------------------------------------------

class ShowTaskLogAction : ProxmoxAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(ProxmoxDataKeys.TASK) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val task = e.getData(ProxmoxDataKeys.TASK) ?: return
        val project = e.project ?: return
        TaskLogDialog(project, task.node, task.upid, task.description).show()
    }
}

class StopTaskAction : ProxmoxAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(ProxmoxDataKeys.TASK)?.isRunning == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val task = e.getData(ProxmoxDataKeys.TASK) ?: return
        if (!confirm(e.project, "Stop Task", "Stop the running task '${task.description}'?", "Stop")) {
            return
        }
        runTasks(
            e,
            "Proxmox: Stop Task",
            listOf(
                TaskRequest(task.node, "Stop ${task.description}") {
                    ProxmoxService.getInstance().stopTask(task.node, task.upid)
                    null
                },
            ),
        )
    }
}

// --- Node service actions ------------------------------------------------------------------------

/** Start, stop or restart the systemd unit selected in the Services tab. */
abstract class ServiceAction(private val apiAction: String, private val label: String) : ProxmoxAction() {

    override fun update(e: AnActionEvent) {
        val service = e.getData(ProxmoxDataKeys.SERVICE)
        val node = e.getData(ProxmoxDataKeys.NODES)?.singleOrNull()
        e.presentation.text = label
        e.presentation.isEnabled = service != null && node != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val service = e.getData(ProxmoxDataKeys.SERVICE) ?: return
        val node = e.getData(ProxmoxDataKeys.NODES)?.singleOrNull() ?: return
        if (!confirm(e.project, label, "$label ${service.name} on ${node.name}?", label)) return

        runTasks(
            e,
            "Proxmox: $label Service",
            listOf(
                TaskRequest(node.name, "$label ${service.name}") {
                    ProxmoxService.getInstance().client().nodes
                        .serviceAction(node.name, service.name, apiAction)
                },
            ),
        )
    }
}

class StartServiceAction : ServiceAction("start", "Start")
class StopServiceAction : ServiceAction("stop", "Stop")
class RestartServiceAction : ServiceAction("restart", "Restart")

// --- Firewall actions -----------------------------------------------------------------------------

/** Enables or disables the firewall rule selected in the Firewall tab. */
class ToggleFirewallRuleAction : ProxmoxAction() {

    override fun update(e: AnActionEvent) {
        val rule = e.getData(ProxmoxDataKeys.FIREWALL_RULE)
        e.presentation.isEnabled = rule != null && e.getData(ProxmoxDataKeys.GUESTS)?.size == 1
        e.presentation.text = if (rule?.enabled == true) "Disable Rule" else "Enable Rule"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val rule = e.getData(ProxmoxDataKeys.FIREWALL_RULE) ?: return
        val guest = e.getData(ProxmoxDataKeys.GUESTS)?.singleOrNull() ?: return
        val enable = !rule.enabled
        runTasks(
            e,
            "Proxmox: Update Firewall Rule",
            listOf(
                TaskRequest(guest.node, "${if (enable) "Enable" else "Disable"} rule ${rule.position}") {
                    ProxmoxService.getInstance().client().guests.setFirewallRuleEnabled(
                        guest.node,
                        guest.type,
                        guest.vmid,
                        rule.position,
                        enable,
                    )
                    null
                },
            ),
        )
    }
}

class DeleteFirewallRuleAction : ProxmoxAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(ProxmoxDataKeys.FIREWALL_RULE) != null &&
            e.getData(ProxmoxDataKeys.GUESTS)?.size == 1
    }

    override fun actionPerformed(e: AnActionEvent) {
        val rule = e.getData(ProxmoxDataKeys.FIREWALL_RULE) ?: return
        val guest = e.getData(ProxmoxDataKeys.GUESTS)?.singleOrNull() ?: return
        if (!confirm(
                e.project,
                "Delete Firewall Rule",
                "Delete rule ${rule.position} (${rule.summary}) of ${guest.displayName}?",
                "Delete",
            )
        ) {
            return
        }
        runTasks(
            e,
            "Proxmox: Delete Firewall Rule",
            listOf(
                TaskRequest(guest.node, "Delete firewall rule ${rule.position}") {
                    ProxmoxService.getInstance().client().guests
                        .deleteFirewallRule(guest.node, guest.type, guest.vmid, rule.position)
                    null
                },
            ),
        )
    }
}

/** Turns the guest's firewall on or off, which is the switch at the top of the Firewall tab. */
class ToggleGuestFirewallAction : ProxmoxAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(ProxmoxDataKeys.GUESTS)?.size == 1
    }

    override fun actionPerformed(e: AnActionEvent) {
        val guest = e.getData(ProxmoxDataKeys.GUESTS)?.singleOrNull() ?: return
        runBackground(
            project = e.project,
            title = "Reading the firewall options",
            compute = { ProxmoxService.getInstance().guestFirewallOptions(guest).enabled },
            onSuccess = { enabled ->
                runTasks(
                    e,
                    "Proxmox: Toggle Firewall",
                    listOf(
                        TaskRequest(
                            guest.node,
                            "${if (enabled) "Disable" else "Enable"} the firewall of ${guest.displayName}",
                        ) {
                            ProxmoxService.getInstance().client().guests
                                .setFirewallEnabled(guest.node, guest.type, guest.vmid, !enabled)
                            null
                        },
                    ),
                )
            },
        )
    }
}
