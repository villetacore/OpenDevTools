package com.opendevtools.proxmox.toolwindow.details

import com.intellij.openapi.project.Project
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.PopupHandler
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.opendevtools.core.format.Formats
import com.opendevtools.proxmox.ProxmoxIcons
import com.opendevtools.proxmox.model.ProxmoxTask
import com.opendevtools.proxmox.service.ProxmoxService
import com.opendevtools.proxmox.settings.ProxmoxSettings
import com.opendevtools.proxmox.toolwindow.tasks.TaskLogDialog
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseEvent

/**
 * Task history. A node shows its own tasks, a guest only the tasks that touched it, and the
 * datacenter the cluster-wide list - the same three scopes the web UI offers.
 */
class TasksTab(project: Project) : TableDetailTab<ProxmoxTask>(
    project,
    "Tasks",
    ProxmoxIcons.Task,
    "Select a node, a guest or the datacenter",
) {

    private val errorsOnly = JBCheckBox("Errors only")
    private var target: DetailTarget = DetailTarget.None

    init {
        add(
            javax.swing.JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2))).apply {
                add(errorsOnly)
            },
            BorderLayout.NORTH,
        )
        errorsOnly.addActionListener { loadData(target) }

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean = openSelectedLog()
        }.installOn(table)
        PopupHandler.installPopupMenu(table, "ProxmoxTools.TaskMenu", "ProxmoxTaskPopup")
    }

    override fun supports(target: DetailTarget): Boolean = target !is DetailTarget.Storage

    override fun columns(): Array<ColumnInfo<ProxmoxTask, *>> = arrayOf(
        column("Start", 18) { Formats.timestamp(it.startTime) },
        column("Duration", 10) { if (it.isRunning) "running" else Formats.duration(it.durationSeconds) },
        column("Node", 10) { it.node },
        column("Task", 26) { it.description },
        column("User", 16) { it.user },
        column("Status", 20) { if (it.isRunning) "running" else it.exitStatus ?: "-" },
    )

    override fun loadData(target: DetailTarget) {
        this.target = target
        val limit = ProxmoxSettings.getInstance().taskHistoryLimit
        val onlyErrors = errorsOnly.isSelected
        val service = ProxmoxService.getInstance()

        val loader: (() -> List<ProxmoxTask>)? = when (target) {
            is DetailTarget.Node -> {
                { service.tasks(target.node.name, limit, onlyErrors, null) }
            }

            is DetailTarget.Guest -> {
                { service.tasks(target.guest.node, limit, onlyErrors, target.guest.vmid) }
            }

            is DetailTarget.Datacenter -> {
                {
                    service.clusterTasks(limit)
                        .filter { !onlyErrors || it.failed }
                }
            }

            else -> null
        }
        if (loader == null) {
            setRows(emptyList())
            return
        }
        showLoading()
        load(
            compute = loader,
            onError = { showError(it) },
            onSuccess = { setRows(it, if (onlyErrors) "No failed tasks" else "No tasks") },
        )
    }

    fun selectedTask(): ProxmoxTask? = selectedRow()

    fun openSelectedLog(): Boolean {
        val task = selectedRow() ?: return false
        TaskLogDialog(project, task.node, task.upid, task.description).show()
        return true
    }
}
