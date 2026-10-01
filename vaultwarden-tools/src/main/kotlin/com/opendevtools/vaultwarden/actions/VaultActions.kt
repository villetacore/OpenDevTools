package com.opendevtools.vaultwarden.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.opendevtools.core.async.Background
import com.opendevtools.core.async.Outcome
import com.opendevtools.vaultwarden.model.VaultItem
import com.opendevtools.vaultwarden.service.VaultwardenService
import com.opendevtools.vaultwarden.settings.VaultwardenSettings
import com.opendevtools.vaultwarden.toolwindow.VaultDataKeys
import com.opendevtools.vaultwarden.toolwindow.VaultwardenPanel
import com.opendevtools.vaultwarden.util.VaultwardenNotifier
import javax.swing.Icon

/**
 * Base for every Vaultwarden action: resolves the panel, offers confirmation, and runs server
 * work off the EDT with a single place that reports the outcome.
 */
abstract class VaultAction(
    text: String? = null,
    description: String? = null,
    icon: Icon? = null,
) : AnAction(text, description, icon) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    protected fun panel(e: AnActionEvent): VaultwardenPanel? = e.getData(VaultDataKeys.PANEL)

    protected fun items(e: AnActionEvent): List<VaultItem> = e.getData(VaultDataKeys.ITEMS).orEmpty()

    protected fun singleItem(e: AnActionEvent): VaultItem? = items(e).singleOrNull()

    protected fun confirm(project: Project?, title: String, message: String, okText: String): Boolean {
        if (!VaultwardenSettings.getInstance().confirmDestructiveActions) return true
        return Messages.showYesNoDialog(
            project,
            message,
            title,
            okText,
            Messages.getCancelButton(),
            Messages.getWarningIcon(),
        ) == Messages.YES
    }

    /** Runs a vault write and refreshes the tool window from the server afterwards. */
    protected fun runWrite(
        e: AnActionEvent,
        title: String,
        successMessage: String,
        work: () -> Unit,
    ) {
        val project = e.project
        val panel = panel(e)
        Background.runVisible(
            project = project,
            title = title,
            cancellable = false,
            compute = { work() },
            onDone = { outcome ->
                when (outcome) {
                    is Outcome.Failure -> VaultwardenNotifier.failure(project, title, outcome.error)
                    is Outcome.Success -> {
                        VaultwardenNotifier.info(project, successMessage)
                        panel?.refresh(reload = true)
                    }
                }
            },
        )
    }
}

// --- Session --------------------------------------------------------------------------------------

class UnlockVaultAction : VaultAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = panel(e) != null && !VaultwardenService.getInstance().isUnlocked
    }

    override fun actionPerformed(e: AnActionEvent) {
        panel(e)?.unlockInteractive()
    }
}

class LockVaultAction : VaultAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = VaultwardenService.getInstance().isUnlocked
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        Background.runVisible(
            project = project,
            title = "Locking the Vaultwarden vault",
            cancellable = false,
            compute = { VaultwardenService.getInstance().lock() },
            onDone = { VaultwardenNotifier.info(project, "Vault locked") },
        )
    }
}

class SyncVaultAction : VaultAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = VaultwardenService.getInstance().isUnlocked
    }

    override fun actionPerformed(e: AnActionEvent) {
        panel(e)?.refresh(reload = true)
    }
}

class RefreshVaultAction : VaultAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = panel(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        panel(e)?.refresh(reload = false)
    }
}

class OpenVaultwardenSettingsAction : VaultAction() {

    override fun actionPerformed(e: AnActionEvent) {
        panel(e)?.openSettings()
    }
}
