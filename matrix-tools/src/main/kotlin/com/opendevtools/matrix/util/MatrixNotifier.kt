package com.opendevtools.matrix.util

import com.intellij.openapi.project.Project
import com.opendevtools.core.ui.Notifier
import com.opendevtools.matrix.api.MatrixException

/** Balloon notifications of the plugin; the group id matches the one in plugin.xml. */
object MatrixNotifier : Notifier("Matrix Tools", "Matrix") {

    /** Adds the piece of context the raw message lacks: what the user can do about it. */
    fun failure(project: Project?, label: String, error: Throwable) {
        val hint = (error as? MatrixException)?.let {
            when {
                it.isUnauthorized -> " - the access token is no longer valid; sign in again"
                it.isForbidden -> " - the account is not allowed to do this in that room"
                it.isRateLimited -> " - the homeserver is rate limiting; it will retry shortly"
                else -> null
            }
        }.orEmpty()
        error(project, label + ": " + (error.message ?: error.toString()) + hint)
    }
}
