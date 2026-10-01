package com.opendevtools.proxmox.service

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.opendevtools.proxmox.model.ProxmoxTask
import com.opendevtools.proxmox.util.ProxmoxNotifier

/** Result of waiting for one task. */
sealed class TaskOutcome {

    class Finished(val task: ProxmoxTask) : TaskOutcome() {
        val succeeded: Boolean get() = task.succeeded
        val exitStatus: String get() = task.exitStatus ?: "unknown"
    }

    /** The task is still running on the server; the plugin simply stopped waiting for it. */
    class StillRunning(val upid: String, val node: String) : TaskOutcome()
}

/**
 * Waits for Proxmox tasks (UPIDs). Power actions, clones, migrations and backups all return one,
 * and the interesting part - whether it actually worked - only shows up in the task status.
 */
@Service(Service.Level.APP)
class ProxmoxTaskTracker {

    /**
     * Polls until the task leaves the running state, the indicator is cancelled or the timeout
     * hits. The poll interval backs off, because a migration can run for many minutes and there
     * is no point asking every second for the whole of it.
     */
    fun await(
        node: String,
        upid: String,
        indicator: ProgressIndicator? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): TaskOutcome {
        val service = ProxmoxService.getInstance()
        val deadline = System.currentTimeMillis() + timeoutMs
        var interval = MIN_POLL_MS
        while (System.currentTimeMillis() < deadline) {
            indicator?.checkCanceled()
            val task = runCatching { service.taskStatus(node, upid) }.getOrNull()
            if (task != null) {
                indicator?.text2 = task.description
                if (!task.isRunning) return TaskOutcome.Finished(task)
            }
            try {
                Thread.sleep(interval)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return TaskOutcome.StillRunning(upid, node)
            }
            interval = (interval * 2).coerceAtMost(MAX_POLL_MS)
        }
        return TaskOutcome.StillRunning(upid, node)
    }

    /**
     * Waits for a task and reports the outcome as a notification, with a link to the log when it
     * failed. This is what every action uses, so success and failure always look the same.
     */
    fun awaitAndReport(
        project: Project?,
        node: String,
        upid: String,
        label: String,
        indicator: ProgressIndicator? = null,
        onLogRequested: ((node: String, upid: String) -> Unit)? = null,
    ): TaskOutcome {
        val outcome = await(node, upid, indicator)
        when (outcome) {
            is TaskOutcome.StillRunning ->
                ProxmoxNotifier.warn(project, "$label: still running on the server")

            is TaskOutcome.Finished -> when {
                outcome.succeeded -> ProxmoxNotifier.info(project, "$label: done")
                onLogRequested != null -> ProxmoxNotifier.withAction(
                    project,
                    "$label failed: ${outcome.exitStatus}",
                    NotificationType.ERROR,
                    "Show log",
                ) { onLogRequested(node, upid) }

                else -> ProxmoxNotifier.error(project, "$label failed: ${outcome.exitStatus}")
            }
        }
        return outcome
    }

    companion object {
        private const val MIN_POLL_MS = 700L
        private const val MAX_POLL_MS = 5_000L
        private const val DEFAULT_TIMEOUT_MS = 60L * 60 * 1000

        fun getInstance(): ProxmoxTaskTracker =
            ApplicationManager.getApplication().getService(ProxmoxTaskTracker::class.java)
    }
}
