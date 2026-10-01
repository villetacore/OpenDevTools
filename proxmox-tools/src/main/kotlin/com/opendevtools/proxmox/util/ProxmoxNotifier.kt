package com.opendevtools.proxmox.util

import com.intellij.openapi.project.Project
import com.opendevtools.core.ui.Notifier
import com.opendevtools.proxmox.api.ProxmoxException

/** Balloon notifications of the Proxmox plugin; the group id matches the one in plugin.xml. */
object ProxmoxNotifier : Notifier("Proxmox Tools", "Proxmox") {

    /**
     * Reports a failure with the one piece of context the server does not put in its message:
     * whether the account simply is not allowed to do this. "HTTP 403 Permission check failed"
     * is not much use on its own.
     */
    fun failure(project: Project?, label: String, error: Throwable) {
        val hint = (error as? ProxmoxException)?.let {
            when {
                it.isPermissionFailure -> " - the account lacks the privilege for this"
                it.isAuthFailure -> " - authentication failed; check the token or password"
                else -> null
            }
        }.orEmpty()
        error(project, label + ": " + (error.message ?: error.toString()) + hint)
    }
}
