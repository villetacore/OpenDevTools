package com.opendevtools.vaultwarden.util

import com.intellij.openapi.project.Project
import com.opendevtools.core.ui.Notifier
import com.opendevtools.vaultwarden.api.VaultwardenException

/** Balloon notifications of the plugin; the group id matches the one in plugin.xml. */
object VaultwardenNotifier : Notifier("Vaultwarden Tools", "Vaultwarden") {

    /**
     * Reports a failure and, where the status code says something the message does not, what to
     * do about it - a stale item is the one failure a person can actually resolve themselves.
     */
    fun failure(project: Project?, label: String, error: Throwable) {
        val hint = (error as? VaultwardenException)?.let {
            when {
                it.isConflict -> " - the item changed on the server; sync and try again"
                it.isAuthFailure -> " - the API key was rejected; check it in the settings"
                else -> null
            }
        }.orEmpty()
        error(project, label + ": " + (error.message ?: error.toString()) + hint)
    }
}
