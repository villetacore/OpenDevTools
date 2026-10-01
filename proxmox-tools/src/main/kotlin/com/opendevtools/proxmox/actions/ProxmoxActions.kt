package com.opendevtools.proxmox.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.opendevtools.core.async.Background
import com.opendevtools.core.async.Outcome
import com.opendevtools.proxmox.service.ProxmoxTaskTracker
import com.opendevtools.proxmox.settings.ProxmoxSettings
import com.opendevtools.proxmox.toolwindow.ProxmoxDataKeys
import com.opendevtools.proxmox.toolwindow.ProxmoxPanel
import com.opendevtools.proxmox.toolwindow.tasks.TaskLogDialog
import com.opendevtools.proxmox.util.ProxmoxNotifier
import javax.swing.Icon

/**
 * One unit of work that ends in a Proxmox task: the API call returns a UPID, the plugin waits
 * for it and reports the outcome. Bundling this in one place is what makes every action behave
 * the same way, including the "Show log" link on a failure.
 */
class TaskRequest(val node: String, val label: String, val call: () -> String?)

/**
 * Base class for every Proxmox action. It resolves the panel, offers confirmation, and runs the
 * requested work on a background thread, refreshing the tool window afterwards.
 */
abstract class ProxmoxAction(
    text: String? = null,
    description: String? = null,
    icon: Icon? = null,
) : AnAction(text, description, icon) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    protected fun panel(e: AnActionEvent): ProxmoxPanel? = e.getData(ProxmoxDataKeys.PANEL)

    /** Asks before a destructive step, unless the user turned confirmations off in the settings. */
    protected fun confirm(project: Project?, title: String, message: String, okText: String): Boolean {
        if (!ProxmoxSettings.getInstance().confirmDestructiveActions) return true
        return Messages.showYesNoDialog(
            project,
            message,
            title,
            okText,
            Messages.getCancelButton(),
            Messages.getQuestionIcon(),
        ) == Messages.YES
    }

    /**
     * Runs [requests] in order, waiting for each task before starting the next one. Sequential
     * execution matters: Proxmox locks a guest for the duration of a task, so firing a stop and
     * a start at the same guest concurrently would simply fail the second one.
     */
    protected fun runTasks(
        e: AnActionEvent,
        progressTitle: String,
        requests: List<TaskRequest>,
        onFinished: (() -> Unit)? = null,
    ) {
        if (requests.isEmpty()) return
        val project = e.project
        val panel = panel(e)

        Background.runVisible(
            project = project,
            title = progressTitle,
            compute = { indicator: ProgressIndicator ->
                val tracker = ProxmoxTaskTracker.getInstance()
                for (request in requests) {
                    indicator.checkCanceled()
                    indicator.text = request.label
                    try {
                        val upid = request.call()
                        if (upid == null) {
                            ProxmoxNotifier.info(project, "${request.label}: done")
                            continue
                        }
                        tracker.awaitAndReport(
                            project = project,
                            node = request.node,
                            upid = upid,
                            label = request.label,
                            indicator = indicator,
                            onLogRequested = { node, taskUpid ->
                                project?.let {
                                    TaskLogDialog(it, node, taskUpid, request.label).show()
                                }
                            },
                        )
                    } catch (t: Throwable) {
                        ProxmoxNotifier.failure(project, request.label, t)
                    }
                }
            },
            onDone = { outcome ->
                if (outcome is Outcome.Failure) ProxmoxNotifier.failure(project, progressTitle, outcome.error)
                panel?.refreshAll()
                onFinished?.invoke()
            },
        )
    }

    /** Work that returns a value rather than a task, for example a dialog's prefetched data. */
    protected fun <T> runBackground(
        project: Project?,
        title: String,
        compute: () -> T,
        onSuccess: (T) -> Unit,
    ) {
        Background.runVisible(
            project = project,
            title = title,
            compute = { compute() },
            onDone = { outcome ->
                when (outcome) {
                    is Outcome.Success -> onSuccess(outcome.value)
                    is Outcome.Failure -> ProxmoxNotifier.failure(project, title, outcome.error)
                }
            },
        )
    }
}
