package com.opendevtools.gpg.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.opendevtools.core.async.Background
import com.opendevtools.core.async.Outcome
import com.opendevtools.gpg.dialogs.ArmoredTextDialog
import com.opendevtools.gpg.dialogs.EncryptDialog
import com.opendevtools.gpg.dialogs.SignDialog
import com.opendevtools.gpg.dialogs.VerificationDialog
import com.opendevtools.gpg.model.DecryptionResult
import com.opendevtools.gpg.model.ExportKind
import com.opendevtools.gpg.model.GpgKey
import com.opendevtools.gpg.model.SignatureStatus
import com.opendevtools.gpg.service.GpgService
import com.opendevtools.gpg.settings.GpgSettings
import com.opendevtools.gpg.util.GpgNotifier
import java.io.File

/**
 * The file and editor operations.
 *
 * These live outside the tool window - in the project view and the editor context menu - because
 * that is where the files are. They read the keyring through the service cache, and only fall back
 * to a real listing when nothing has loaded one yet.
 */
abstract class CryptoAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    protected fun files(e: AnActionEvent): List<VirtualFile> =
        e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.filter { !it.isDirectory }.orEmpty()

    /**
     * The keyring, loaded now if it has to be.
     *
     * Called from `actionPerformed` on the EDT, so the load is wrapped in a modal progress rather
     * than run bare; on a keyring that the tool window has already read it is a map lookup.
     */
    protected fun keyring(project: Project?): List<GpgKey>? {
        GpgService.getInstance().cachedKeyring()?.let { return it.keys }
        return runCatching {
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable { GpgService.getInstance().loadKeyring().keys },
                "Reading the Keyring",
                true,
                project,
            )
        }.onFailure {
            GpgNotifier.failure(project, "Reading the keyring failed", it)
        }.getOrNull()
    }

    protected fun requireConfigured(e: AnActionEvent): Boolean {
        if (GpgSettings.getInstance().isConfigured) return true
        Messages.showWarningDialog(
            e.project,
            "GnuPG was not found. Set the path in Settings | Tools | GPG.",
            "GPG Not Configured",
        )
        return false
    }

    /** Tells the IDE about a file gpg wrote behind its back, so it shows up in the project view. */
    protected fun refreshInIde(file: File) {
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
    }
}

// --- Files --------------------------------------------------------------------------------------

class EncryptFilesAction : CryptoAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = files(e).isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        if (!requireConfigured(e)) return
        val inputs = files(e).map { File(it.path) }
        if (inputs.isEmpty()) return
        val keys = keyring(e.project) ?: return

        val dialog = EncryptDialog(e.project, keys)
        if (!dialog.showAndGet()) return

        val recipients = dialog.recipientFingerprints()
        val signingKey = dialog.signingKey()
        val armor = dialog.armor()
        val untrusted = dialog.allowUntrusted()
        val project = e.project

        Background.runVisible(
            project = project,
            title = "Encrypting",
            cancellable = false,
            compute = {
                inputs.map { input ->
                    val output = File(input.parentFile, input.name + if (armor) ".asc" else ".gpg")
                    GpgService.getInstance()
                        .encryptFile(input, output, recipients, armor, signingKey, untrusted)
                    output
                }
            },
            onDone = { outcome ->
                when (outcome) {
                    is Outcome.Failure ->
                        GpgNotifier.failure(project, "Encryption failed", outcome.error)

                    is Outcome.Success -> {
                        outcome.value.forEach { refreshInIde(it) }
                        GpgNotifier.info(
                            project,
                            "Encrypted " + outcome.value.size + " file(s) to " +
                                outcome.value.joinToString(", ") { it.name },
                        )
                    }
                }
            },
        )
    }
}

class SymmetricEncryptAction : CryptoAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = files(e).size == 1
    }

    override fun actionPerformed(e: AnActionEvent) {
        if (!requireConfigured(e)) return
        val input = files(e).map { File(it.path) }.singleOrNull() ?: return
        val armor = GpgSettings.getInstance().armorByDefault
        val output = File(input.parentFile, input.name + if (armor) ".asc" else ".gpg")
        val project = e.project

        Background.runVisible(
            project = project,
            title = "Encrypting " + input.name + " with a passphrase",
            cancellable = false,
            compute = {
                GpgService.getInstance().encryptSymmetric(input, output, armor)
                output
            },
            onDone = { outcome ->
                when (outcome) {
                    is Outcome.Failure ->
                        GpgNotifier.failure(project, "Encryption failed", outcome.error)

                    is Outcome.Success -> {
                        refreshInIde(outcome.value)
                        GpgNotifier.info(project, "Encrypted to " + outcome.value.name)
                    }
                }
            },
        )
    }
}

class SignFilesAction : CryptoAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = files(e).isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        if (!requireConfigured(e)) return
        val inputs = files(e).map { File(it.path) }
        if (inputs.isEmpty()) return
        val keys = keyring(e.project)?.filter { it.canSign } ?: return
        if (keys.isEmpty()) {
            Messages.showWarningDialog(
                e.project,
                "No certificate in this keyring holds a secret key that can sign.",
                "Nothing to Sign With",
            )
            return
        }

        val dialog = SignDialog(e.project, keys, textMode = false)
        if (!dialog.showAndGet()) return
        val signingKey = dialog.signingKey()
        val detached = dialog.detached()
        val armor = dialog.armor()
        val project = e.project

        Background.runVisible(
            project = project,
            title = "Signing",
            cancellable = false,
            compute = {
                inputs.map { input ->
                    val suffix = when {
                        detached && armor -> ".asc"
                        detached -> ".sig"
                        armor -> ".asc"
                        else -> ".gpg"
                    }
                    val output = File(input.parentFile, input.name + suffix)
                    GpgService.getInstance().signFile(input, output, detached, armor, signingKey)
                    output
                }
            },
            onDone = { outcome ->
                when (outcome) {
                    is Outcome.Failure -> GpgNotifier.failure(project, "Signing failed", outcome.error)
                    is Outcome.Success -> {
                        outcome.value.forEach { refreshInIde(it) }
                        GpgNotifier.info(
                            project,
                            "Signed: " + outcome.value.joinToString(", ") { it.name },
                        )
                    }
                }
            },
        )
    }
}

/**
 * The one action for opening whatever was dropped on it.
 *
 * A detached signature next to its file, an encrypted blob, a signed archive - the user should not
 * have to know which they have, so the extension decides and gpg settles the rest.
 */
class DecryptVerifyFilesAction : CryptoAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = files(e).any { isCryptoFile(it.name) }
    }

    override fun actionPerformed(e: AnActionEvent) {
        if (!requireConfigured(e)) return
        val file = files(e).firstOrNull { isCryptoFile(it.name) }?.let { File(it.path) } ?: return
        val project = e.project

        // A signature sitting beside the file it signs is a verification, not a decryption.
        val signed = File(file.parentFile, file.nameWithoutExtension)
        if (isSignatureName(file.name) && signed.isFile) {
            verify(project, file, signed)
            return
        }

        val output = File(file.parentFile, file.nameWithoutExtension.ifBlank { file.name + ".out" })
        Background.runVisible(
            project = project,
            title = "Decrypting " + file.name,
            cancellable = false,
            compute = { GpgService.getInstance().decryptFile(file, output) },
            onDone = { outcome ->
                when (outcome) {
                    is Outcome.Failure -> {
                        // A file with nothing to decrypt is often a signature after all.
                        if (isSignatureName(file.name)) {
                            verify(project, file, null)
                        } else {
                            GpgNotifier.failure(project, "Decryption failed", outcome.error)
                        }
                    }

                    is Outcome.Success -> {
                        refreshInIde(output)
                        report(project, outcome.value, output)
                    }
                }
            },
        )
    }

    private fun verify(project: Project?, signature: File, signed: File?) {
        Background.runVisible(
            project = project,
            title = "Checking " + signature.name,
            cancellable = false,
            compute = { GpgService.getInstance().verifyFile(signature, signed) },
            onDone = { outcome ->
                when (outcome) {
                    is Outcome.Failure ->
                        GpgNotifier.failure(project, "Could not check the signature", outcome.error)

                    is Outcome.Success -> VerificationDialog(project, outcome.value).show()
                }
            },
        )
    }

    private fun report(project: Project?, result: DecryptionResult, output: File) {
        val signature = result.signatures.firstOrNull()
        val note = when {
            signature == null -> ""
            signature.status == SignatureStatus.VALID ->
                "\nSigned by " + signature.signerDisplay + "."

            else -> "\n" + signature.status.title + " - " + signature.signerDisplay
        }
        val how = if (result.symmetric) " (passphrase)" else ""
        GpgNotifier.info(project, "Decrypted$how to " + output.name + note)
    }

    private fun isCryptoFile(name: String): Boolean =
        name.endsWith(".gpg", true) || name.endsWith(".pgp", true) ||
            name.endsWith(".asc", true) || name.endsWith(".sig", true)

    private fun isSignatureName(name: String): Boolean =
        name.endsWith(".sig", true) || name.endsWith(".asc", true)
}

// --- Editor -------------------------------------------------------------------------------------

/** Base for the actions that work on an editor selection. */
abstract class SelectionAction : CryptoAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = selection(e) != null
    }

    protected fun editor(e: AnActionEvent): Editor? = e.getData(CommonDataKeys.EDITOR)

    protected fun selection(e: AnActionEvent): String? =
        editor(e)?.selectionModel?.selectedText?.takeIf { it.isNotBlank() }

    /** Puts the result back where the selection was, as one undoable step. */
    protected fun replaceSelection(project: Project?, editor: Editor, replacement: String) {
        val model = editor.selectionModel
        val start = model.selectionStart
        val end = model.selectionEnd
        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.replaceString(start, end, replacement)
        }
    }
}

class EncryptSelectionAction : SelectionAction() {

    override fun actionPerformed(e: AnActionEvent) {
        if (!requireConfigured(e)) return
        val editor = editor(e) ?: return
        val text = selection(e) ?: return
        val keys = keyring(e.project) ?: return

        val dialog = EncryptDialog(e.project, keys)
        if (!dialog.showAndGet()) return
        val recipients = dialog.recipientFingerprints()
        val signingKey = dialog.signingKey()
        val untrusted = dialog.allowUntrusted()
        val project = e.project

        Background.runVisible(
            project = project,
            title = "Encrypting the selection",
            cancellable = false,
            compute = { GpgService.getInstance().encryptText(text, recipients, signingKey, untrusted) },
            onDone = { outcome ->
                when (outcome) {
                    is Outcome.Failure ->
                        GpgNotifier.failure(project, "Encryption failed", outcome.error)

                    is Outcome.Success -> replaceSelection(project, editor, outcome.value)
                }
            },
        )
    }
}

class SignSelectionAction : SelectionAction() {

    override fun actionPerformed(e: AnActionEvent) {
        if (!requireConfigured(e)) return
        val editor = editor(e) ?: return
        val text = selection(e) ?: return
        val keys = keyring(e.project)?.filter { it.canSign } ?: return
        if (keys.isEmpty()) {
            Messages.showWarningDialog(
                e.project,
                "No certificate in this keyring holds a secret key that can sign.",
                "Nothing to Sign With",
            )
            return
        }

        val dialog = SignDialog(e.project, keys, textMode = true)
        if (!dialog.showAndGet()) return
        val signingKey = dialog.signingKey()
        val clearSign = dialog.clearSign()
        val project = e.project

        Background.runVisible(
            project = project,
            title = "Signing the selection",
            cancellable = false,
            compute = { GpgService.getInstance().signText(text, signingKey, clearSign) },
            onDone = { outcome ->
                when (outcome) {
                    is Outcome.Failure -> GpgNotifier.failure(project, "Signing failed", outcome.error)
                    is Outcome.Success -> replaceSelection(project, editor, outcome.value)
                }
            },
        )
    }
}

class DecryptSelectionAction : SelectionAction() {

    override fun actionPerformed(e: AnActionEvent) {
        if (!requireConfigured(e)) return
        val editor = editor(e) ?: return
        val text = selection(e) ?: return
        val project = e.project

        Background.runVisible(
            project = project,
            title = "Decrypting the selection",
            cancellable = false,
            compute = { GpgService.getInstance().decryptText(text) },
            onDone = { outcome ->
                when (outcome) {
                    is Outcome.Failure ->
                        GpgNotifier.failure(project, "Decryption failed", outcome.error)

                    is Outcome.Success -> {
                        val (plaintext, result) = outcome.value
                        // Replacing the selection with an empty string would silently delete the
                        // ciphertext, so nothing is written unless gpg actually produced something.
                        if (plaintext.isBlank()) {
                            GpgNotifier.warn(project, "gpg produced no output for that selection")
                            return@runVisible
                        }
                        replaceSelection(project, editor, plaintext)
                        result.signatures.firstOrNull()?.let {
                            GpgNotifier.info(
                                project,
                                it.status.title + " - " + it.signerDisplay,
                            )
                        }
                    }
                }
            },
        )
    }
}

class VerifySelectionAction : SelectionAction() {

    override fun actionPerformed(e: AnActionEvent) {
        if (!requireConfigured(e)) return
        val text = selection(e) ?: return
        val project = e.project

        Background.runVisible(
            project = project,
            title = "Checking the signature",
            cancellable = false,
            compute = { GpgService.getInstance().verifyText(text) },
            onDone = { outcome ->
                when (outcome) {
                    is Outcome.Failure ->
                        GpgNotifier.failure(project, "Could not check the signature", outcome.error)

                    is Outcome.Success -> VerificationDialog(project, outcome.value).show()
                }
            },
        )
    }
}

/** Shows the armored certificate of the default signing key, ready to paste into a mail. */
class ShowMyCertificateAction : CryptoAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = GpgSettings.getInstance().isConfigured
    }

    override fun actionPerformed(e: AnActionEvent) {
        if (!requireConfigured(e)) return
        val keys = keyring(e.project)?.filter { it.hasSecret } ?: return
        val settings = GpgSettings.getInstance()
        val key = keys.firstOrNull { it.fingerprint == settings.defaultSigningKey }
            ?: keys.firstOrNull()
        if (key == null) {
            Messages.showWarningDialog(
                e.project,
                "This keyring holds no certificate of your own. Generate one from the GPG tool window.",
                "No Certificate",
            )
            return
        }
        val project = e.project

        Background.runVisible(
            project = project,
            title = "Exporting " + key.displayName,
            cancellable = false,
            compute = {
                GpgService.getInstance().exportArmored(listOf(key.fingerprint), ExportKind.PUBLIC)
            },
            onDone = { outcome ->
                when (outcome) {
                    is Outcome.Failure -> GpgNotifier.failure(project, "Export failed", outcome.error)
                    is Outcome.Success -> ArmoredTextDialog(
                        project,
                        "My Certificate",
                        outcome.value,
                        "Fingerprint: " + key.readableFingerprint,
                    ).show()
                }
            },
        )
    }
}
