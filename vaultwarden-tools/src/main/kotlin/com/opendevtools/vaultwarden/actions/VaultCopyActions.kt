package com.opendevtools.vaultwarden.actions

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.opendevtools.core.async.Background
import com.opendevtools.core.async.Outcome
import com.opendevtools.vaultwarden.model.VaultItem
import com.opendevtools.vaultwarden.service.VaultwardenService
import com.opendevtools.vaultwarden.settings.VaultwardenSettings
import com.opendevtools.vaultwarden.toolwindow.VaultDataKeys
import com.opendevtools.vaultwarden.util.SecureClipboard
import com.opendevtools.vaultwarden.util.VaultwardenNotifier

/** Base for actions that need exactly one selected item and read one value out of it. */
abstract class SingleItemAction : VaultAction() {

    override fun update(e: AnActionEvent) {
        val item = singleItem(e)
        e.presentation.isEnabled = item != null && isEnabledFor(item)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val item = singleItem(e) ?: return
        VaultwardenService.getInstance().touch()
        perform(e, item)
    }

    protected abstract fun isEnabledFor(item: VaultItem): Boolean

    protected abstract fun perform(e: AnActionEvent, item: VaultItem)
}

/** Copies one plain value of the selected item, wiping the clipboard again after the timeout. */
abstract class CopyValueAction(
    private val label: String,
    private val value: (VaultItem) -> String?,
) : SingleItemAction() {

    override fun isEnabledFor(item: VaultItem): Boolean = !value(item).isNullOrBlank()

    override fun perform(e: AnActionEvent, item: VaultItem) {
        SecureClipboard.copy(e.project, label, value(item).orEmpty())
    }
}

class CopyUsernameAction : CopyValueAction("Username", { it.username })
class CopyPasswordAction : CopyValueAction("Password", { it.password })
class CopyNotesAction : CopyValueAction("Notes", { it.notes })
class CopyUriAction : CopyValueAction("URI", { it.uris.firstOrNull() })
class CopyCardNumberAction : CopyValueAction("Card number", { it.card?.number })
class CopyCardCodeAction : CopyValueAction("Security code", { it.card?.code })
class CopySshPublicKeyAction : CopyValueAction("Public key", { it.sshKey?.publicKey })
class CopySshPrivateKeyAction : CopyValueAction("Private key", { it.sshKey?.privateKey })

/** Generates a fresh TOTP code and copies it, reporting how long it stays valid. */
class CopyTotpAction : SingleItemAction() {

    override fun isEnabledFor(item: VaultItem): Boolean = item.hasTotp

    override fun perform(e: AnActionEvent, item: VaultItem) {
        val project = e.project
        Background.runVisible(
            project = project,
            title = "Generating the one-time password",
            cancellable = false,
            compute = { VaultwardenService.getInstance().totp(item) },
            onDone = { outcome ->
                when (outcome) {
                    is Outcome.Failure -> VaultwardenNotifier.error(project, outcome.message)
                    is Outcome.Success -> SecureClipboard.copy(
                        project,
                        "One-time password (valid ${outcome.value.secondsRemaining}s)",
                        outcome.value.code,
                    )
                }
            },
        )
    }
}

class OpenUriAction : SingleItemAction() {

    override fun isEnabledFor(item: VaultItem): Boolean = item.uris.isNotEmpty()

    override fun perform(e: AnActionEvent, item: VaultItem) {
        item.uris.firstOrNull()?.let { BrowserUtil.browse(it) }
    }
}

/** Pastes the password into the editor - handy for .env files and local configs. */
class InsertPasswordAction : SingleItemAction() {

    override fun isEnabledFor(item: VaultItem): Boolean = !item.password.isNullOrBlank()

    override fun update(e: AnActionEvent) {
        super.update(e)
        val project = e.project
        if (project == null || FileEditorManager.getInstance(project).selectedTextEditor == null) {
            e.presentation.isEnabled = false
        }
    }

    override fun perform(e: AnActionEvent, item: VaultItem) {
        insertAtCaret(e, item.password ?: return)
    }
}

/** Pastes `USERNAME=...` style credentials as two lines, which is the common .env shape. */
class InsertCredentialsAction : SingleItemAction() {

    override fun isEnabledFor(item: VaultItem): Boolean =
        !item.username.isNullOrBlank() || !item.password.isNullOrBlank()

    override fun update(e: AnActionEvent) {
        super.update(e)
        val project = e.project
        if (project == null || FileEditorManager.getInstance(project).selectedTextEditor == null) {
            e.presentation.isEnabled = false
        }
    }

    override fun perform(e: AnActionEvent, item: VaultItem) {
        val text = listOfNotNull(
            item.username?.takeIf { it.isNotBlank() }?.let { "USERNAME=$it" },
            item.password?.takeIf { it.isNotBlank() }?.let { "PASSWORD=$it" },
        ).joinToString(System.lineSeparator())
        if (text.isNotEmpty()) insertAtCaret(e, text)
    }
}

/** Shared write path of the two "insert at caret" actions. */
private fun insertAtCaret(e: AnActionEvent, text: String) {
    val project = e.project ?: return
    val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
    val caret = editor.caretModel.currentCaret
    val start = caret.selectionStart
    val end = caret.selectionEnd
    WriteCommandAction.runWriteCommandAction(project) {
        editor.document.replaceString(start, end, text)
        caret.moveToOffset(start + text.length)
    }
    VaultwardenNotifier.warn(
        project,
        "A secret was written into the editor. Do not commit it.",
    )
}

/** Copies the share link of the selected Send; the link's fragment carries the decryption key. */
class CopySendLinkAction : VaultAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(VaultDataKeys.SEND) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val send = e.getData(VaultDataKeys.SEND) ?: return
        val url = send.shareUrl(VaultwardenSettings.getInstance().serverUrl)
        if (url == null) {
            VaultwardenNotifier.warn(e.project, "This Send has no share link yet")
            return
        }
        SecureClipboard.copy(e.project, "Send link", url)
    }
}
