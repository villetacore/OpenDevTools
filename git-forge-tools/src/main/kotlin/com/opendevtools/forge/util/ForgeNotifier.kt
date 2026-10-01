package com.opendevtools.forge.util

import com.intellij.openapi.project.Project
import com.opendevtools.core.ui.Notifier
import com.opendevtools.forge.api.ForgeException

/** Balloon notifications of the plugin; the group id matches the one in plugin.xml. */
object ForgeNotifier : Notifier("Git Forge Tools", "Git Forges") {

    /** Adds what the status code implies, which the forge's own message usually leaves out. */
    fun failure(project: Project?, label: String, error: Throwable) {
        val hint = (error as? ForgeException)?.let {
            when {
                it.isUnauthorized -> " - the token was rejected; check it in the settings"
                it.isForbidden -> " - the token lacks the scope for this"
                it.isNotFound -> " - not found, or the token cannot see it"
                else -> null
            }
        }.orEmpty()
        error(project, label + ": " + (error.message ?: error.toString()) + hint)
    }
}
