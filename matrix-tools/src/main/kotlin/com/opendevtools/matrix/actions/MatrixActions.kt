package com.opendevtools.matrix.actions

import com.intellij.ide.CopyPasteManagerEx
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.opendevtools.core.async.Background
import com.opendevtools.core.async.Outcome
import com.opendevtools.matrix.model.MatrixRoom
import com.opendevtools.matrix.model.RoomGrouping
import com.opendevtools.matrix.model.RoomMembership
import com.opendevtools.matrix.service.MatrixService
import com.opendevtools.matrix.settings.MatrixSettings
import com.opendevtools.matrix.toolwindow.MatrixDataKeys
import com.opendevtools.matrix.toolwindow.MatrixPanel
import com.opendevtools.matrix.util.MatrixNotifier
import java.awt.datatransfer.StringSelection
import javax.swing.Icon

/** Base for every Matrix action: resolves the panel and reports failures the same way. */
abstract class MatrixAction(
    text: String? = null,
    description: String? = null,
    icon: Icon? = null,
) : AnAction(text, description, icon) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    protected fun panel(e: AnActionEvent): MatrixPanel? = e.getData(MatrixDataKeys.PANEL)

    protected fun room(e: AnActionEvent): MatrixRoom? = e.getData(MatrixDataKeys.ROOM)

    /** Runs a server call and refreshes nothing: the sync loop delivers the result on its own. */
    protected fun run(
        project: Project?,
        title: String,
        onSuccess: () -> Unit = {},
        work: () -> Unit,
    ) {
        Background.runVisible(
            project = project,
            title = title,
            cancellable = false,
            compute = { work() },
            onDone = { outcome ->
                when (outcome) {
                    is Outcome.Failure -> MatrixNotifier.failure(project, title, outcome.error)
                    is Outcome.Success -> onSuccess()
                }
            },
        )
    }
}

class ReconnectAction : MatrixAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = panel(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        MatrixService.getInstance().stopSync()
        panel(e)?.connect()
    }
}

class OpenMatrixSettingsAction : MatrixAction() {

    override fun actionPerformed(e: AnActionEvent) {
        panel(e)?.openSettings()
    }
}

/** Joins a room by id or alias, then selects it. */
class JoinRoomAction : MatrixAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = MatrixService.getInstance().isConnected
    }

    override fun actionPerformed(e: AnActionEvent) {
        val panel = panel(e)
        val target = Messages.showInputDialog(
            e.project,
            "Room id or alias, for example #room:example.org",
            "Join Room",
            null,
        )?.trim().orEmpty()
        if (target.isEmpty()) return

        Background.runVisible(
            project = e.project,
            title = "Joining $target",
            cancellable = false,
            compute = { MatrixService.getInstance().joinRoom(target) },
            onDone = { outcome ->
                when (outcome) {
                    is Outcome.Failure ->
                        MatrixNotifier.failure(e.project, "Could not join $target", outcome.error)

                    is Outcome.Success -> panel?.selectRoom(outcome.value)
                }
            },
        )
    }
}

/** Accepts a pending invitation. */
class AcceptInviteAction : MatrixAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = room(e)?.membership == RoomMembership.INVITED
    }

    override fun actionPerformed(e: AnActionEvent) {
        val room = room(e) ?: return
        val panel = panel(e)
        run(e.project, "Joining ${room.displayName}", onSuccess = { panel?.selectRoom(room.id) }) {
            MatrixService.getInstance().joinRoom(room.id)
        }
    }
}

/** Leaves a room, or declines an invitation - the server call is the same one. */
class LeaveRoomAction : MatrixAction() {

    override fun update(e: AnActionEvent) {
        val room = room(e)
        e.presentation.isEnabled = room != null
        e.presentation.text =
            if (room?.membership == RoomMembership.INVITED) "Decline Invitation" else "Leave Room"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val room = room(e) ?: return
        val declining = room.membership == RoomMembership.INVITED
        val confirmed = Messages.showYesNoDialog(
            e.project,
            if (declining) {
                "Decline the invitation to ${room.displayName}?"
            } else {
                "Leave ${room.displayName}?\n\nYou will need a new invitation to return to a private room."
            },
            if (declining) "Decline Invitation" else "Leave Room",
            Messages.getWarningIcon(),
        ) == Messages.YES
        if (!confirmed) return

        run(e.project, "Leaving ${room.displayName}") {
            MatrixService.getInstance().leaveRoom(room.id)
        }
    }
}

class InviteUserAction : MatrixAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = room(e)?.membership == RoomMembership.JOINED
    }

    override fun actionPerformed(e: AnActionEvent) {
        val room = room(e) ?: return
        val userId = Messages.showInputDialog(
            e.project,
            "User id, for example @alex:example.org",
            "Invite to ${room.displayName}",
            null,
        )?.trim().orEmpty()
        if (userId.isEmpty()) return

        run(e.project, "Inviting $userId") { MatrixService.getInstance().invite(room.id, userId) }
    }
}

class MarkRoomReadAction : MatrixAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = room(e)?.hasUnread == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val room = room(e) ?: return
        run(e.project, "Marking ${room.displayName} read") {
            MatrixService.getInstance().markRead(room.id)
        }
    }
}

class CopyRoomAddressAction : MatrixAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = room(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val room = room(e) ?: return
        val address = room.canonicalAlias ?: room.id
        CopyPasteManagerEx.getInstance().setContents(StringSelection(address))
        MatrixNotifier.info(e.project, "Copied $address")
    }
}

/** Copies whatever is selected in the timeline - the reason the timeline is one text area. */
class CopySelectedMessagesAction : MatrixAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = panel(e)?.selectedTimelineText()?.isNotEmpty() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val text = panel(e)?.selectedTimelineText().orEmpty()
        if (text.isNotEmpty()) CopyPasteManagerEx.getInstance().setContents(StringSelection(text))
    }
}

/** Pastes the selected conversation text into the editor, for pulling a snippet out of a chat. */
class InsertSelectionAtCaretAction : MatrixAction() {

    override fun update(e: AnActionEvent) {
        val project = e.project
        val hasEditor = project != null &&
            FileEditorManager.getInstance(project).selectedTextEditor != null
        e.presentation.isEnabled = hasEditor && panel(e)?.selectedTimelineText()?.isNotEmpty() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        val text = panel(e)?.selectedTimelineText().orEmpty()
        if (text.isEmpty()) return

        val caret = editor.caretModel.currentCaret
        val start = caret.selectionStart
        val end = caret.selectionEnd
        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.replaceString(start, end, text)
            caret.moveToOffset(start + text.length)
        }
    }
}

class ToggleShowEncryptedRoomsAction : ToggleAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun isSelected(e: AnActionEvent): Boolean =
        MatrixSettings.getInstance().showEncryptedRooms

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        MatrixSettings.getInstance().showEncryptedRooms = state
        MatrixSettings.fireStateChanged()
    }
}

/** One entry of the ordering menu; the choice is a persistent setting. */
class SelectGroupingAction(private val grouping: RoomGrouping) :
    ToggleAction(grouping.title, "Order the room list by ${grouping.title}", null) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun isSelected(e: AnActionEvent): Boolean =
        MatrixSettings.getInstance().grouping == grouping

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        if (!state) return
        MatrixSettings.getInstance().grouping = grouping
        MatrixSettings.fireStateChanged()
    }
}

class GroupingActionGroup : DefaultActionGroup(
    "Order",
    RoomGrouping.entries.map { SelectGroupingAction(it) },
) {
    init {
        isPopup = true
        templatePresentation.icon = com.intellij.icons.AllIcons.Actions.GroupBy
    }
}
