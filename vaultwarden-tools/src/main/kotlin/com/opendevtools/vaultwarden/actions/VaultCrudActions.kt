package com.opendevtools.vaultwarden.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.opendevtools.core.async.Background
import com.opendevtools.core.async.Outcome
import com.opendevtools.vaultwarden.dialogs.FolderDialog
import com.opendevtools.vaultwarden.dialogs.MoveToFolderDialog
import com.opendevtools.vaultwarden.dialogs.PasswordGeneratorDialog
import com.opendevtools.vaultwarden.dialogs.VaultReportDialog
import com.opendevtools.vaultwarden.model.VaultFolder
import com.opendevtools.vaultwarden.model.VaultGrouping
import com.opendevtools.vaultwarden.model.VaultItemType
import com.opendevtools.vaultwarden.service.VaultReports
import com.opendevtools.vaultwarden.service.VaultwardenService
import com.opendevtools.vaultwarden.settings.VaultwardenSettings
import com.opendevtools.vaultwarden.toolwindow.VaultDataKeys
import com.opendevtools.vaultwarden.util.SecureClipboard
import com.opendevtools.vaultwarden.util.VaultwardenNotifier
import java.io.File

// --- Item lifecycle ---------------------------------------------------------------------------------

/** One entry of the "New Item" menu, one per item type. */
class NewItemAction(private val type: VaultItemType) :
    VaultAction(type.singular, "Create a new ${type.singular.lowercase()}", null) {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = VaultwardenService.getInstance().isUnlocked
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        VaultItemEditing.create(project, panel(e), type)
    }
}

class NewItemActionGroup : DefaultActionGroup(
    "New Item",
    VaultItemType.entries.map { NewItemAction(it) },
) {
    init {
        isPopup = true
        templatePresentation.icon = com.intellij.icons.AllIcons.General.Add
    }
}

class EditItemAction : VaultAction() {

    override fun update(e: AnActionEvent) {
        val item = singleItem(e)
        e.presentation.isEnabled = item != null && !item.readOnly && !item.isDeleted
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val item = singleItem(e) ?: return
        VaultItemEditing.edit(project, panel(e), item)
    }
}

/** Moves the selected items to the trash, where they can still be restored. */
class TrashItemAction : VaultAction() {

    override fun update(e: AnActionEvent) {
        val items = items(e)
        e.presentation.isEnabled = items.isNotEmpty() && items.none { it.isDeleted || it.readOnly }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val items = items(e).filterNot { it.isDeleted }
        if (items.isEmpty()) return
        val what = if (items.size == 1) "\"${items.first().name}\"" else "${items.size} items"
        if (!confirm(e.project, "Move to Trash", "Move $what to the trash?", "Move to Trash")) return

        runWrite(e, "Moving to the trash", "$what moved to the trash") {
            val service = VaultwardenService.getInstance()
            items.forEach { service.trashItem(it) }
        }
    }
}

class RestoreItemAction : VaultAction() {

    override fun update(e: AnActionEvent) {
        val items = items(e)
        e.presentation.isEnabled = items.isNotEmpty() && items.all { it.isDeleted }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val items = items(e).filter { it.isDeleted }
        if (items.isEmpty()) return
        runWrite(e, "Restoring from the trash", "Restored ${items.size} item(s)") {
            val service = VaultwardenService.getInstance()
            items.forEach { service.restoreItem(it) }
        }
    }
}

/** Removes items for good; there is no undo on the server side, so this always asks. */
class DeleteItemPermanentlyAction : VaultAction() {

    override fun update(e: AnActionEvent) {
        val items = items(e)
        e.presentation.isEnabled = items.isNotEmpty() && items.none { it.readOnly }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val items = items(e)
        if (items.isEmpty()) return
        val what = if (items.size == 1) "\"${items.first().name}\"" else "${items.size} items"

        val confirmed = Messages.showYesNoDialog(
            e.project,
            "Permanently delete $what?\n\nThis cannot be undone.",
            "Delete Permanently",
            "Delete",
            Messages.getCancelButton(),
            Messages.getWarningIcon(),
        ) == Messages.YES
        if (!confirmed) return

        runWrite(e, "Deleting from the vault", "$what deleted") {
            val service = VaultwardenService.getInstance()
            items.forEach { service.deleteItemPermanently(it) }
        }
    }
}

class ToggleFavoriteAction : VaultAction() {

    override fun update(e: AnActionEvent) {
        val item = singleItem(e)
        e.presentation.isEnabled = item != null && !item.readOnly
        e.presentation.text = if (item?.favorite == true) "Remove from Favorites" else "Add to Favorites"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val item = singleItem(e) ?: return
        val updated = item.copy(favorite = !item.favorite)
        runWrite(e, "Updating the item", "\"${item.name}\" updated") {
            VaultwardenService.getInstance().updateItem(updated)
        }
    }
}

class MoveToFolderAction : VaultAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = items(e).isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val items = items(e)
        if (items.isEmpty()) return
        val folders = panel(e)?.currentSnapshot()?.foldersWithNone() ?: listOf(VaultFolder.NONE)

        val dialog = MoveToFolderDialog(e.project, folders, items.size)
        if (!dialog.showAndGet()) return
        val target = dialog.targetFolderId()

        runWrite(e, "Moving the items", "Moved ${items.size} item(s)") {
            VaultwardenService.getInstance().moveToFolder(items, target)
        }
    }
}

// --- Folders ------------------------------------------------------------------------------------------

class NewFolderAction : VaultAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = VaultwardenService.getInstance().isUnlocked
    }

    override fun actionPerformed(e: AnActionEvent) {
        val existing = panel(e)?.currentSnapshot()?.folders?.map { it.name }?.toSet().orEmpty()
        val dialog = FolderDialog(e.project, existing)
        if (!dialog.showAndGet()) return
        val name = dialog.folderName()

        runWrite(e, "Creating the folder", "Folder \"$name\" created") {
            VaultwardenService.getInstance().createFolder(name)
        }
    }
}

class RenameFolderAction : VaultAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(VaultDataKeys.FOLDER)?.id != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val folder = e.getData(VaultDataKeys.FOLDER) ?: return
        val id = folder.id ?: return
        val existing = panel(e)?.currentSnapshot()?.folders?.map { it.name }?.toSet().orEmpty()

        val dialog = FolderDialog(e.project, existing, folder.name)
        if (!dialog.showAndGet()) return
        val name = dialog.folderName()

        runWrite(e, "Renaming the folder", "Folder renamed to \"$name\"") {
            VaultwardenService.getInstance().renameFolder(id, name)
        }
    }
}

/** Deleting a folder leaves its items in place; they simply fall back to "No Folder". */
class DeleteFolderAction : VaultAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(VaultDataKeys.FOLDER)?.id != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val folder = e.getData(VaultDataKeys.FOLDER) ?: return
        val id = folder.id ?: return
        if (!confirm(
                e.project,
                "Delete Folder",
                "Delete the folder \"${folder.name}\"?\n\n" +
                    "The items inside it are kept and move to \"No Folder\".",
                "Delete",
            )
        ) {
            return
        }
        runWrite(e, "Deleting the folder", "Folder \"${folder.name}\" deleted") {
            VaultwardenService.getInstance().deleteFolder(id)
        }
    }
}

// --- Attachments ----------------------------------------------------------------------------------------

class SaveAttachmentAction : VaultAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = singleItem(e)?.hasAttachments == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val item = singleItem(e) ?: return
        if (item.attachments.isEmpty()) return

        val attachment = if (item.attachments.size == 1) {
            item.attachments.first()
        } else {
            val names = item.attachments.map { it.fileName }
            // This overload answers with the index of the chosen row, or -1 when cancelled.
            val chosen = Messages.showChooseDialog(
                project,
                "Which attachment do you want to save?",
                "Save Attachment",
                null,
                names.toTypedArray(),
                names.first(),
            )
            if (chosen < 0) return
            item.attachments[chosen]
        }

        val settings = VaultwardenSettings.getInstance()
        val descriptor = FileSaverDescriptor("Save Attachment", "Choose where to store the file")
        val baseDir = settings.lastDownloadDirectory.takeIf { it.isNotBlank() }
            ?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        val target = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save(baseDir, attachment.fileName) ?: return

        Background.runVisible(
            project = project,
            title = "Downloading ${attachment.fileName}",
            cancellable = false,
            compute = { VaultwardenService.getInstance().downloadAttachment(item, attachment) },
            onDone = { outcome ->
                when (outcome) {
                    is Outcome.Failure ->
                        VaultwardenNotifier.failure(project, "Could not download the attachment", outcome.error)

                    is Outcome.Success -> {
                        val file = target.file
                        runCatching { file.writeBytes(outcome.value) }
                            .onSuccess {
                                settings.lastDownloadDirectory = file.parent.orEmpty()
                                VaultwardenNotifier.info(project, "Saved to ${file.absolutePath}")
                            }
                            .onFailure {
                                VaultwardenNotifier.failure(project, "Could not write the file", it)
                            }
                    }
                }
            },
        )
    }
}

class AddAttachmentAction : VaultAction() {

    override fun update(e: AnActionEvent) {
        val item = singleItem(e)
        e.presentation.isEnabled = item != null && !item.readOnly && !item.isDeleted
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val item = singleItem(e) ?: return
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
            .withTitle("Attach a File")
        val chosen = FileChooser.chooseFile(descriptor, project, null) ?: return
        val file = File(chosen.path)

        if (file.length() > MAX_ATTACHMENT_BYTES) {
            VaultwardenNotifier.warn(
                project,
                "The file is larger than the ${MAX_ATTACHMENT_BYTES / 1024 / 1024} MB the plugin uploads",
            )
            return
        }

        runWrite(e, "Uploading ${file.name}", "\"${file.name}\" attached") {
            VaultwardenService.getInstance().uploadAttachment(item, file.name, file.readBytes())
        }
    }

    private companion object {
        /** The whole file is held in memory twice while it is encrypted, so keep the cap modest. */
        const val MAX_ATTACHMENT_BYTES = 50L * 1024 * 1024
    }
}

class DeleteAttachmentAction : VaultAction() {

    override fun update(e: AnActionEvent) {
        val item = singleItem(e)
        e.presentation.isEnabled = item?.hasAttachments == true && !item.readOnly
    }

    override fun actionPerformed(e: AnActionEvent) {
        val item = singleItem(e) ?: return
        val names = item.attachments.map { it.fileName }
        if (names.isEmpty()) return

        val chosen = Messages.showChooseDialog(
            e.project,
            "Which attachment do you want to delete?",
            "Delete Attachment",
            null,
            names.toTypedArray(),
            names.first(),
        )
        if (chosen < 0) return
        val attachment = item.attachments[chosen]
        val choice = attachment.fileName

        if (!confirm(e.project, "Delete Attachment", "Delete \"$choice\"?", "Delete")) return

        runWrite(e, "Deleting the attachment", "\"$choice\" deleted") {
            VaultwardenService.getInstance().deleteAttachment(item, attachment)
        }
    }
}

// --- Tools --------------------------------------------------------------------------------------------------

/** The password generator, reachable without editing an item. */
class GeneratePasswordAction : VaultAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val dialog = PasswordGeneratorDialog(e.project)
        if (!dialog.showAndGet()) return
        SecureClipboard.copy(e.project, "Generated password", dialog.password())
    }
}

class VaultReportAction : VaultAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = VaultwardenService.getInstance().isUnlocked
    }

    override fun actionPerformed(e: AnActionEvent) {
        val snapshot = panel(e)?.currentSnapshot()
            ?: VaultwardenService.getInstance().cachedSnapshot()
            ?: return
        val report = VaultReports.analyse(snapshot)
        val dialog = VaultReportDialog(e.project, report)
        dialog.show()
        dialog.selectedFinding()?.let { panel(e)?.selectItem(it.item) }
    }
}

class ToggleShowSecretsAction : ToggleAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun isSelected(e: AnActionEvent): Boolean =
        e.getData(VaultDataKeys.PANEL)?.showSecrets == true

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        e.getData(VaultDataKeys.PANEL)?.showSecrets = state
    }
}

class ToggleFavoritesOnlyAction : ToggleAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun isSelected(e: AnActionEvent): Boolean =
        e.getData(VaultDataKeys.PANEL)?.favoritesOnly == true

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        e.getData(VaultDataKeys.PANEL)?.favoritesOnly = state
    }
}

/** One entry of the grouping menu; the choice is a persistent setting. */
class SelectGroupingAction(private val grouping: VaultGrouping) :
    ToggleAction(grouping.title, "Group the vault by ${grouping.title}", null) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun isSelected(e: AnActionEvent): Boolean =
        VaultwardenSettings.getInstance().grouping == grouping

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        if (!state) return
        VaultwardenSettings.getInstance().grouping = grouping
        VaultwardenSettings.fireStateChanged()
    }
}

class GroupingActionGroup : DefaultActionGroup(
    "View",
    VaultGrouping.entries.map { SelectGroupingAction(it) },
) {
    init {
        isPopup = true
        templatePresentation.icon = com.intellij.icons.AllIcons.Actions.GroupBy
    }
}

/** Deletes the Send selected in the tree; the share link stops working immediately. */
class DeleteSendAction : VaultAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(VaultDataKeys.SEND) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val send = e.getData(VaultDataKeys.SEND) ?: return
        if (!confirm(
                e.project,
                "Delete Send",
                "Delete the Send \"${send.name}\"?\n\nAnyone holding the link loses access at once.",
                "Delete",
            )
        ) {
            return
        }
        runWrite(e, "Deleting the Send", "Send \"${send.name}\" deleted") {
            VaultwardenService.getInstance().deleteSend(send)
        }
    }
}
