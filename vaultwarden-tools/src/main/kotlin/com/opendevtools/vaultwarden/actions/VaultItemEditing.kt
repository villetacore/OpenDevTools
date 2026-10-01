package com.opendevtools.vaultwarden.actions

import com.intellij.openapi.project.Project
import com.opendevtools.core.async.Background
import com.opendevtools.core.async.Outcome
import com.opendevtools.vaultwarden.dialogs.ItemEditorDialog
import com.opendevtools.vaultwarden.model.VaultFolder
import com.opendevtools.vaultwarden.model.VaultItem
import com.opendevtools.vaultwarden.model.VaultItemType
import com.opendevtools.vaultwarden.service.VaultwardenService
import com.opendevtools.vaultwarden.toolwindow.VaultwardenPanel
import com.opendevtools.vaultwarden.util.VaultwardenNotifier

/**
 * Opening the item editor and saving the result. It lives outside the actions so the tool
 * window's double click and the menu entry share one implementation rather than two that drift.
 */
object VaultItemEditing {

    fun create(project: Project, panel: VaultwardenPanel?, type: VaultItemType) {
        val snapshot = panel?.currentSnapshot() ?: VaultwardenService.getInstance().cachedSnapshot()
        val folders = snapshot?.foldersWithNone() ?: listOf(VaultFolder.NONE)
        val dialog = ItemEditorDialog(
            project = project,
            original = null,
            folders = folders,
            defaultFolderId = panel?.selectedFolder()?.id,
            defaultType = type,
        )
        if (!dialog.showAndGet()) return
        val item = dialog.item()

        Background.runVisible(
            project = project,
            title = "Creating the vault item",
            cancellable = false,
            compute = { VaultwardenService.getInstance().createItem(item) },
            onDone = { outcome -> finish(project, panel, outcome, "created") },
        )
    }

    fun edit(project: Project, panel: VaultwardenPanel?, item: VaultItem) {
        if (item.readOnly) {
            VaultwardenNotifier.warn(project, "This item is read-only for your account")
            return
        }
        val snapshot = panel?.currentSnapshot() ?: VaultwardenService.getInstance().cachedSnapshot()
        val folders = snapshot?.foldersWithNone() ?: listOf(VaultFolder.NONE)
        val dialog = ItemEditorDialog(
            project = project,
            original = item,
            folders = folders,
            defaultFolderId = item.folderId,
            defaultType = item.type,
        )
        if (!dialog.showAndGet()) return
        val edited = dialog.item()
        val previousPassword = dialog.previousPassword()

        Background.runVisible(
            project = project,
            title = "Saving the vault item",
            cancellable = false,
            compute = { VaultwardenService.getInstance().updateItem(edited, previousPassword) },
            onDone = { outcome -> finish(project, panel, outcome, "saved") },
        )
    }

    private fun finish(
        project: Project,
        panel: VaultwardenPanel?,
        outcome: Outcome<VaultItem>,
        verb: String,
    ) {
        when (outcome) {
            is Outcome.Failure ->
                VaultwardenNotifier.failure(project, "Could not save the item", outcome.error)
            is Outcome.Success -> {
                VaultwardenNotifier.info(project, "\"${outcome.value.name}\" $verb")
                // A write changes the server state, so the next view has to come from a fresh sync.
                panel?.refresh(reload = true)
            }
        }
    }
}
