package com.opendevtools.gpg.actions

import com.intellij.ide.CopyPasteManagerEx
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.opendevtools.core.async.Background
import com.opendevtools.core.async.Outcome
import com.opendevtools.gpg.dialogs.AddSubkeyDialog
import com.opendevtools.gpg.dialogs.AddUserIdDialog
import com.opendevtools.gpg.dialogs.ArmoredTextDialog
import com.opendevtools.gpg.dialogs.CertifyDialog
import com.opendevtools.gpg.dialogs.ChangeExpiryDialog
import com.opendevtools.gpg.dialogs.ExportDialog
import com.opendevtools.gpg.dialogs.GenerateKeyDialog
import com.opendevtools.gpg.dialogs.KeyserverSearchDialog
import com.opendevtools.gpg.dialogs.OwnerTrustDialog
import com.opendevtools.gpg.dialogs.RevokeUserIdDialog
import com.opendevtools.gpg.model.ExportKind
import com.opendevtools.gpg.model.GpgKey
import com.opendevtools.gpg.service.GpgService
import com.opendevtools.gpg.settings.GpgSettings
import com.opendevtools.gpg.toolwindow.GpgDataKeys
import com.opendevtools.gpg.toolwindow.GpgPanel
import com.opendevtools.gpg.util.GpgNotifier
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.Icon

/** Base for every GPG action: resolves the panel and reports failures the same way. */
abstract class GpgAction(
    text: String? = null,
    description: String? = null,
    icon: Icon? = null,
) : AnAction(text, description, icon) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    protected fun panel(e: AnActionEvent): GpgPanel? = e.getData(GpgDataKeys.PANEL)

    protected fun certificate(e: AnActionEvent): GpgKey? = e.getData(GpgDataKeys.CERTIFICATE)

    protected fun confirm(project: Project?, title: String, message: String, okText: String): Boolean {
        if (!GpgSettings.getInstance().confirmDestructiveActions) return true
        return Messages.showYesNoDialog(
            project,
            message,
            title,
            okText,
            Messages.getCancelButton(),
            Messages.getWarningIcon(),
        ) == Messages.YES
    }

    /**
     * Runs a change and reloads afterwards.
     *
     * The timeout is generous because most of these wait on a pinentry dialog, so "slow" here
     * usually means the user has not typed their passphrase yet.
     */
    protected fun runWrite(
        e: AnActionEvent,
        title: String,
        successMessage: String,
        fullRefresh: Boolean = false,
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
                    is Outcome.Failure -> GpgNotifier.failure(project, title, outcome.error)
                    is Outcome.Success -> {
                        GpgNotifier.info(project, successMessage)
                        if (fullRefresh) panel?.refresh() else panel?.reloadSelected()
                    }
                }
            },
        )
    }
}

/** Base for the actions that need one certificate selected. */
abstract class CertificateAction(
    text: String? = null,
    description: String? = null,
    icon: Icon? = null,
) : GpgAction(text, description, icon) {

    /** Narrows the action further; the default is "any selected certificate will do". */
    protected open fun isEnabledFor(key: GpgKey): Boolean = true

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = certificate(e)?.let { isEnabledFor(it) } == true
    }
}

// --- Keyring ------------------------------------------------------------------------------------

class RefreshKeyringAction : GpgAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = panel(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        GpgService.getInstance().invalidate()
        panel(e)?.refresh()
    }
}

class GenerateKeyAction : GpgAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = GpgSettings.getInstance().isConfigured
    }

    override fun actionPerformed(e: AnActionEvent) {
        val dialog = GenerateKeyDialog(e.project)
        if (!dialog.showAndGet()) return
        val spec = dialog.spec()
        val panel = panel(e)
        val project = e.project

        Background.runVisible(
            project = project,
            title = "Generating a key pair",
            cancellable = false,
            compute = { GpgService.getInstance().generateKey(spec) },
            onDone = { outcome ->
                when (outcome) {
                    is Outcome.Failure ->
                        GpgNotifier.failure(project, "Generating the key failed", outcome.error)

                    is Outcome.Success -> {
                        val fingerprint = outcome.value.firstOrNull()
                        GpgNotifier.info(
                            project,
                            "Created " + spec.userId +
                                (fingerprint?.let { "\n$it" }.orEmpty()),
                        )
                        panel?.refresh(fingerprint)
                    }
                }
            },
        )
    }
}

class ImportCertificateAction : GpgAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = GpgSettings.getInstance().isConfigured
    }

    override fun actionPerformed(e: AnActionEvent) {
        val descriptor = FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor()
        val files = FileChooser.chooseFiles(descriptor, e.project, null)
        if (files.isEmpty()) return
        val paths = files.map { it.path }
        val project = e.project
        val panel = panel(e)

        Background.runVisible(
            project = project,
            title = "Importing certificates",
            cancellable = false,
            compute = { GpgService.getInstance().importFiles(paths) },
            onDone = { outcome ->
                when (outcome) {
                    is Outcome.Failure -> GpgNotifier.failure(project, "Import failed", outcome.error)
                    is Outcome.Success -> {
                        GpgNotifier.info(project, "Imported: " + outcome.value.describe())
                        panel?.refresh(outcome.value.fingerprints.firstOrNull())
                    }
                }
            },
        )
    }
}

class ImportFromClipboardAction : GpgAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = GpgSettings.getInstance().isConfigured
    }

    override fun actionPerformed(e: AnActionEvent) {
        val text = clipboardText()
        if (text.isNullOrBlank() || !text.contains("BEGIN PGP")) {
            Messages.showWarningDialog(
                e.project,
                "The clipboard does not hold an OpenPGP block. Copy a key that starts with " +
                    "\"-----BEGIN PGP PUBLIC KEY BLOCK-----\" and try again.",
                "Nothing to Import",
            )
            return
        }
        val project = e.project
        val panel = panel(e)
        Background.runVisible(
            project = project,
            title = "Importing from the clipboard",
            cancellable = false,
            compute = { GpgService.getInstance().importText(text) },
            onDone = { outcome ->
                when (outcome) {
                    is Outcome.Failure -> GpgNotifier.failure(project, "Import failed", outcome.error)
                    is Outcome.Success -> {
                        GpgNotifier.info(project, "Imported: " + outcome.value.describe())
                        panel?.refresh(outcome.value.fingerprints.firstOrNull())
                    }
                }
            },
        )
    }

    private fun clipboardText(): String? = runCatching {
        CopyPasteManagerEx.getInstance().getContents<String>(DataFlavor.stringFlavor)
    }.getOrNull()
}

// --- One certificate ----------------------------------------------------------------------------

class ExportCertificateAction : CertificateAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val key = certificate(e) ?: return
        val dialog = ExportDialog(e.project, key)
        if (!dialog.showAndGet()) return
        val kind = dialog.kind()
        val armor = dialog.armor()
        val project = e.project

        if (kind == ExportKind.SECRET || kind == ExportKind.SECRET_SUBKEYS) {
            val confirmed = confirm(
                project,
                "Export a Secret Key",
                "This writes the private half of ${key.displayName} to a file.\n" +
                    "Anyone with that file and its passphrase can act as you.\n\nContinue?",
                "Export",
            )
            if (!confirmed) return
        }

        val target = chooseSaveFile(
            project,
            "Export Certificate",
            defaultName(key, kind, armor),
        ) ?: return

        Background.runVisible(
            project = project,
            title = "Exporting " + key.displayName,
            cancellable = false,
            compute = {
                GpgService.getInstance().exportToFile(listOf(key.fingerprint), kind, target, armor)
            },
            onDone = { outcome ->
                when (outcome) {
                    is Outcome.Failure -> GpgNotifier.failure(project, "Export failed", outcome.error)
                    is Outcome.Success -> GpgNotifier.info(project, "Written to " + target.absolutePath)
                }
            },
        )
    }

    private fun defaultName(key: GpgKey, kind: ExportKind, armor: Boolean): String {
        val base = (key.email ?: key.shortKeyId).replace(Regex("[^A-Za-z0-9._@-]"), "_")
        return when (kind) {
            ExportKind.SSH -> "$base.pub"
            ExportKind.SECRET, ExportKind.SECRET_SUBKEYS ->
                base + "-secret." + if (armor) "asc" else "gpg"

            ExportKind.PUBLIC -> base + "." + if (armor) "asc" else "gpg"
        }
    }
}

class CopyCertificateAction : CertificateAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val key = certificate(e) ?: return
        val project = e.project
        Background.runVisible(
            project = project,
            title = "Exporting " + key.displayName,
            cancellable = false,
            compute = { GpgService.getInstance().exportArmored(listOf(key.fingerprint), ExportKind.PUBLIC) },
            onDone = { outcome ->
                when (outcome) {
                    is Outcome.Failure -> GpgNotifier.failure(project, "Export failed", outcome.error)
                    is Outcome.Success -> ArmoredTextDialog(
                        project,
                        "Certificate of " + key.displayName,
                        outcome.value,
                        "Fingerprint: " + key.readableFingerprint,
                    ).show()
                }
            },
        )
    }
}

class CopyFingerprintAction : CertificateAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val key = certificate(e) ?: return
        CopyPasteManagerEx.getInstance().setContents(StringSelection(key.fingerprint))
        GpgNotifier.info(e.project, "Fingerprint copied: " + key.readableFingerprint)
    }
}

class CertifyKeyAction : CertificateAction() {

    /** Certifying your own key is what gpg already did when it created it. */
    override fun isEnabledFor(key: GpgKey): Boolean = !key.hasSecret && !key.isRevoked

    override fun actionPerformed(e: AnActionEvent) {
        val key = certificate(e) ?: return
        val signingKeys = panel(e)?.currentKeyring()?.keys.orEmpty().filter { it.canCertify }
        if (signingKeys.isEmpty()) {
            Messages.showWarningDialog(
                e.project,
                "Certifying needs a secret key of your own that can certify. Generate one first.",
                "No Certifying Key",
            )
            return
        }
        val dialog = CertifyDialog(e.project, key, signingKeys)
        if (!dialog.showAndGet()) return
        val signer = dialog.signingKey()
        val local = dialog.isLocal()
        val userIds = dialog.selectedUserIds()

        runWrite(e, "Certifying " + key.displayName, "Certified " + key.displayName) {
            GpgService.getInstance().certify(key.fingerprint, signer, local, userIds)
        }
    }
}

class SetOwnerTrustAction : CertificateAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val key = certificate(e) ?: return
        val dialog = OwnerTrustDialog(e.project, key)
        if (!dialog.showAndGet()) return
        val trust = dialog.trust()
        runWrite(e, "Setting owner trust", "Owner trust of ${key.displayName} is now ${trust.title}") {
            GpgService.getInstance().setOwnerTrust(key.fingerprint, trust)
        }
    }
}

class ChangeExpiryAction : CertificateAction() {

    override fun isEnabledFor(key: GpgKey): Boolean = key.hasSecret && !key.isRevoked

    override fun actionPerformed(e: AnActionEvent) {
        val key = certificate(e) ?: return
        val dialog = ChangeExpiryDialog(e.project, key, e.getData(GpgDataKeys.SUBKEYS).orEmpty())
        if (!dialog.showAndGet()) return
        val expiry = dialog.expiryExpression()
        val subkeys = dialog.selectedSubkeys()

        runWrite(e, "Changing the expiry date", "Expiry of ${key.displayName} updated") {
            GpgService.getInstance().setExpiry(key.fingerprint, expiry, subkeys)
        }
    }
}

class AddUserIdAction : CertificateAction() {

    override fun isEnabledFor(key: GpgKey): Boolean = key.hasSecret && !key.isRevoked

    override fun actionPerformed(e: AnActionEvent) {
        val key = certificate(e) ?: return
        val dialog = AddUserIdDialog(e.project, key)
        if (!dialog.showAndGet()) return
        val userId = dialog.userId()
        runWrite(e, "Adding a user id", "Added $userId") {
            GpgService.getInstance().addUserId(key.fingerprint, userId)
        }
    }
}

class RevokeUserIdAction : CertificateAction() {

    override fun isEnabledFor(key: GpgKey): Boolean =
        key.hasSecret && key.userIds.count { !it.isAttribute } > 1

    override fun actionPerformed(e: AnActionEvent) {
        val key = certificate(e) ?: return
        val dialog = RevokeUserIdDialog(e.project, key)
        if (!dialog.showAndGet()) return
        val userId = dialog.userId()
        val confirmed = confirm(
            e.project,
            "Revoke a User ID",
            "Revoking \"$userId\" cannot be undone, and becomes public as soon as the " +
                "certificate is published.\n\nContinue?",
            "Revoke",
        )
        if (!confirmed) return

        runWrite(e, "Revoking a user id", "Revoked $userId") {
            GpgService.getInstance().revokeUserId(key.fingerprint, userId)
        }
    }
}

class AddSubkeyAction : CertificateAction() {

    override fun isEnabledFor(key: GpgKey): Boolean = key.hasSecret && !key.isRevoked

    override fun actionPerformed(e: AnActionEvent) {
        val key = certificate(e) ?: return
        val dialog = AddSubkeyDialog(e.project, key)
        if (!dialog.showAndGet()) return
        val algorithm = dialog.algorithm()
        val usage = dialog.usage()
        val expiry = dialog.expiryExpression()

        runWrite(e, "Adding a subkey", "Added a $usage subkey to " + key.displayName) {
            GpgService.getInstance().addSubkey(key.fingerprint, algorithm, usage, expiry)
        }
    }
}

class ChangePassphraseAction : CertificateAction() {

    override fun isEnabledFor(key: GpgKey): Boolean = key.hasSecret

    override fun actionPerformed(e: AnActionEvent) {
        val key = certificate(e) ?: return
        runWrite(e, "Changing the passphrase", "Passphrase of ${key.displayName} changed") {
            GpgService.getInstance().changePassphrase(key.fingerprint)
        }
    }
}

class SetDefaultSigningKeyAction : CertificateAction() {

    override fun isEnabledFor(key: GpgKey): Boolean = key.canSign

    override fun actionPerformed(e: AnActionEvent) {
        val key = certificate(e) ?: return
        GpgSettings.getInstance().defaultSigningKey = key.fingerprint
        GpgNotifier.info(e.project, key.displayName + " will be used for signing by default")
    }
}

// --- Keyservers ---------------------------------------------------------------------------------

class LookUpOnServerAction : GpgAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = GpgSettings.getInstance().isConfigured
    }

    override fun actionPerformed(e: AnActionEvent) {
        val dialog = KeyserverSearchDialog(e.project)
        if (!dialog.showAndGet()) return
        val keyIds = dialog.selectedKeyIds()
        if (keyIds.isEmpty()) return
        val project = e.project
        val panel = panel(e)

        Background.runVisible(
            project = project,
            title = "Receiving certificates",
            cancellable = false,
            compute = { GpgService.getInstance().receiveKeys(keyIds) },
            onDone = { outcome ->
                when (outcome) {
                    is Outcome.Failure ->
                        GpgNotifier.failure(project, "Receiving failed", outcome.error)

                    is Outcome.Success -> {
                        GpgNotifier.info(project, "Received: " + outcome.value.describe())
                        panel?.refresh(outcome.value.fingerprints.firstOrNull())
                    }
                }
            },
        )
    }
}

class SendToServerAction : CertificateAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val key = certificate(e) ?: return
        val keyserver = GpgSettings.getInstance().keyserver
        val confirmed = Messages.showYesNoDialog(
            e.project,
            "Publish ${key.displayName} to $keyserver?\n\n" +
                "Publishing is effectively permanent: keyservers do not forget, and a certificate " +
                "there carries the mail addresses in its user ids.",
            "Publish a Certificate",
            "Publish",
            Messages.getCancelButton(),
            Messages.getWarningIcon(),
        ) == Messages.YES
        if (!confirmed) return

        runWrite(e, "Publishing " + key.displayName, "Sent to $keyserver") {
            GpgService.getInstance().sendKeys(listOf(key.fingerprint))
        }
    }
}

class RefreshFromServerAction : CertificateAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val key = certificate(e) ?: return
        val project = e.project
        val panel = panel(e)
        Background.runVisible(
            project = project,
            title = "Refreshing " + key.displayName,
            cancellable = false,
            compute = { GpgService.getInstance().refreshFromKeyserver(listOf(key.fingerprint)) },
            onDone = { outcome ->
                when (outcome) {
                    is Outcome.Failure -> GpgNotifier.failure(project, "Refresh failed", outcome.error)
                    is Outcome.Success -> {
                        GpgNotifier.info(project, "Refreshed: " + outcome.value.describe())
                        panel?.refresh(key.fingerprint)
                    }
                }
            },
        )
    }
}

// --- Destructive --------------------------------------------------------------------------------

class RevokeCertificateAction : CertificateAction() {

    override fun isEnabledFor(key: GpgKey): Boolean = key.hasSecret && !key.isRevoked

    override fun actionPerformed(e: AnActionEvent) {
        val key = certificate(e) ?: return
        val confirmed = Messages.showYesNoDialog(
            e.project,
            "Revoke ${key.displayName}?\n\n" +
                "This applies the revocation certificate GnuPG wrote when the key was created. " +
                "It cannot be undone, and once the certificate is published everyone will see it " +
                "as revoked.",
            "Revoke a Certificate",
            "Revoke",
            Messages.getCancelButton(),
            Messages.getWarningIcon(),
        ) == Messages.YES
        if (!confirmed) return

        runWrite(e, "Revoking " + key.displayName, key.displayName + " is now revoked", fullRefresh = true) {
            GpgService.getInstance().revokeCertificate(key.fingerprint)
        }
    }
}

class DeleteCertificateAction : CertificateAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val key = certificate(e) ?: return
        val secretWarning = if (key.hasSecret) {
            "\n\nThis certificate holds a secret key. Deleting it destroys the only copy in this " +
                "keyring - anything encrypted to it becomes unreadable."
        } else {
            ""
        }
        val confirmed = Messages.showYesNoDialog(
            e.project,
            "Delete ${key.displayName} from the keyring?$secretWarning",
            "Delete a Certificate",
            "Delete",
            Messages.getCancelButton(),
            Messages.getWarningIcon(),
        ) == Messages.YES
        if (!confirmed) return

        runWrite(e, "Deleting " + key.displayName, "Deleted " + key.displayName, fullRefresh = true) {
            GpgService.getInstance().deleteKey(key.fingerprint, key.hasSecret)
        }
    }
}

// --- View ---------------------------------------------------------------------------------------

class ToggleOnlySecretKeysAction : ToggleAction("Only My Certificates") {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean = GpgSettings.getInstance().showOnlySecretKeys

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        GpgSettings.getInstance().showOnlySecretKeys = state
        e.getData(GpgDataKeys.PANEL)?.refilter()
    }
}

class ToggleShowInvalidAction : ToggleAction("Show Revoked and Expired") {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean =
        GpgSettings.getInstance().showInvalidCertificates

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        GpgSettings.getInstance().showInvalidCertificates = state
        e.getData(GpgDataKeys.PANEL)?.refilter()
    }
}

class OpenGpgSettingsAction : GpgAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = panel(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        panel(e)?.openSettings()
    }
}

/** The save dialog used by every action that writes a file. */
internal fun chooseSaveFile(
    project: Project?,
    title: String,
    defaultName: String,
    inDirectory: String? = null,
): File? {
    val descriptor = FileSaverDescriptor(title, "Choose where to write the file")
    val baseDir = inDirectory?.let { LocalFileSystem.getInstance().findFileByPath(it) }
    return FileChooserFactory.getInstance()
        .createSaveFileDialog(descriptor, project)
        .save(baseDir, defaultName)
        ?.file
}
