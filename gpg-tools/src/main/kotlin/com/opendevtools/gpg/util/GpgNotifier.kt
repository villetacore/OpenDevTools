package com.opendevtools.gpg.util

import com.intellij.openapi.project.Project
import com.opendevtools.core.ui.Notifier
import com.opendevtools.gpg.cli.GpgException

/** Balloon notifications of the plugin; the group id matches the one in plugin.xml. */
object GpgNotifier : Notifier("GPG Tools", "GPG") {

    /**
     * Reports a failure, except for the one "failure" that is not one: dismissing the pinentry
     * dialog is the user saying no, and answering that with a red balloon would be nagging.
     */
    fun failure(project: Project?, label: String, error: Throwable) {
        if ((error as? GpgException)?.cancelled == true) {
            info(project, "$label cancelled")
            return
        }
        error(project, label + ": " + (error.message ?: error.toString()))
    }
}
