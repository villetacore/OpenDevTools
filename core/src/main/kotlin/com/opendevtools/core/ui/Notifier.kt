package com.opendevtools.core.ui

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/**
 * Balloon notifications for one notification group. Both plugins report long-running server work
 * this way, so the wiring lives here and each plugin only supplies its group id and title.
 */
open class Notifier(private val groupId: String, private val title: String) {

    fun info(project: Project?, content: String) = notify(project, content, NotificationType.INFORMATION)

    fun warn(project: Project?, content: String) = notify(project, content, NotificationType.WARNING)

    fun error(project: Project?, content: String) = notify(project, content, NotificationType.ERROR)

    fun error(project: Project?, prefix: String, error: Throwable) =
        error(project, prefix + ": " + (error.message ?: error.toString()))

    /** A notification carrying a single follow-up, for example "Show log" after a failed task. */
    fun withAction(
        project: Project?,
        content: String,
        type: NotificationType,
        actionText: String,
        action: () -> Unit,
    ) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(groupId)
            .createNotification(title, content, type)
            .addAction(NotificationAction.createSimpleExpiring(actionText) { action() })
            .notify(project)
    }

    private fun notify(project: Project?, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(groupId)
            .createNotification(title, content, type)
            .notify(project)
    }
}
