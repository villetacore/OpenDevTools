package com.opendevtools.vaultwarden.util

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import com.opendevtools.vaultwarden.settings.VaultwardenSettings
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.util.concurrent.TimeUnit

/**
 * Copies a secret to the clipboard and wipes it again after the configured delay,
 * but only if the clipboard still holds that very value.
 */
object SecureClipboard {

    fun copy(project: Project?, label: String, secret: String) {
        if (secret.isEmpty()) {
            VaultwardenNotifier.warn(project, label + " is empty")
            return
        }
        CopyPasteManager.getInstance().setContents(StringSelection(secret))

        val seconds = VaultwardenSettings.getInstance().clipboardClearSeconds
        if (seconds <= 0) {
            VaultwardenNotifier.info(project, label + " copied to the clipboard")
            return
        }
        VaultwardenNotifier.info(
            project,
            label + " copied to the clipboard, cleared in " + seconds + "s",
        )
        AppExecutorUtil.getAppScheduledExecutorService().schedule(
            { clearIfUnchanged(secret) },
            seconds.toLong(),
            TimeUnit.SECONDS,
        )
    }

    private fun clearIfUnchanged(secret: String) {
        ApplicationManager.getApplication().invokeLater {
            val manager = CopyPasteManager.getInstance()
            val current = runCatching { manager.getContents<String>(DataFlavor.stringFlavor) }.getOrNull()
            if (current == secret) {
                manager.setContents(StringSelection(""))
            }
        }
    }
}
