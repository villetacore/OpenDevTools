package com.opendevtools.gpg.settings

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.opendevtools.gpg.service.GpgLocator

/**
 * Settings for the plugin.
 *
 * There is nothing secret on this page and no password safe entry behind it: the plugin never
 * holds a passphrase, so the only things worth configuring are where gpg is and how it should be
 * called.
 */
class GpgConfigurable : BoundConfigurable("GPG") {

    private val settings = GpgSettings.getInstance()

    private val executableField = JBTextField(settings.executablePath).apply {
        emptyText.text = GpgLocator.detect() ?: "gpg was not found on this machine"
    }

    private val homeField = JBTextField(settings.gnupgHome).apply {
        emptyText.text = "the default keyring of that gpg"
    }

    override fun createPanel() = panel {
        group("GnuPG") {
            row("gpg executable:") {
                cell(executableField).columns(44)
                    .comment("Leave empty to use whichever gpg is on PATH or in the usual install location")
                button("Browse...") { browse() }
            }
            row("GNUPGHOME:") {
                cell(homeField).columns(44)
                    .comment("An alternative keyring directory; leave empty for the default one")
                button("Browse...") { browseHome() }
            }
            row {
                button("Detect") { detect() }
                button("Test") { test() }
            }
            row {
                comment(
                    "Passphrases are asked for by gpg-agent through its own pinentry dialog. " +
                        "This plugin never sees a passphrase or a private key.",
                )
            }
        }

        group("Keys") {
            row("Keyserver:") {
                textField().bindText(settings::keyserver).columns(44)
                    .comment("Used by search, receive, send and refresh")
            }
            row("Default signing key:") {
                textField().bindText(settings::defaultSigningKey).columns(44)
                    .comment(
                        "Fingerprint pre-selected in the signing and encryption dialogs. " +
                            "\"Set as Default Signing Key\" in the tool window fills this in.",
                    )
            }
            row {
                checkBox("Encrypt to the default signing key as well")
                    .bindSelected(settings::encryptToSelf)
                    .comment("Without this, files you encrypt cannot be opened again by you")
            }
            row {
                checkBox("Certify keys locally only")
                    .bindSelected(settings::localCertificationsOnly)
                    .comment("A local certification stays in this keyring and is never exported")
            }
        }

        group("Tool Window") {
            row {
                checkBox("ASCII armor by default").bindSelected(settings::armorByDefault)
                    .comment("Text output that can be pasted or mailed, rather than binary")
            }
            row {
                checkBox("Show only certificates with a secret key")
                    .bindSelected(settings::showOnlySecretKeys)
            }
            row {
                checkBox("Show revoked and expired certificates")
                    .bindSelected(settings::showInvalidCertificates)
            }
            row {
                checkBox("Load certifications")
                    .bindSelected(settings::loadSignatures)
                    .comment(
                        "Fills the Certifications tab by listing signatures, which is noticeably " +
                            "slower on a large keyring",
                    )
            }
            row {
                checkBox("Confirm before deleting or revoking")
                    .bindSelected(settings::confirmDestructiveActions)
            }
        }
    }

    override fun isModified(): Boolean =
        super.isModified() ||
            executableField.text != settings.executablePath ||
            homeField.text != settings.gnupgHome

    override fun apply() {
        super.apply()
        settings.executablePath = executableField.text.trim()
        settings.gnupgHome = homeField.text.trim()
        GpgSettings.fireChanged()
    }

    override fun reset() {
        super.reset()
        executableField.text = settings.executablePath
        homeField.text = settings.gnupgHome
    }

    private fun browse() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
        FileChooser.chooseFile(descriptor, null, null)?.let { executableField.text = it.path }
    }

    private fun browseHome() {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
        FileChooser.chooseFile(descriptor, null, null)?.let { homeField.text = it.path }
    }

    private fun detect() {
        val found = GpgLocator.candidates()
        if (found.isEmpty()) {
            Messages.showWarningDialog(
                "No gpg executable was found. Install GnuPG - Gpg4win on Windows, gnupg from your " +
                    "package manager elsewhere - and try again.",
                "GPG Not Found",
            )
            return
        }
        executableField.text = found.first()
        val others = found.drop(1)
        Messages.showInfoMessage(
            buildString {
                append("Using ").append(found.first())
                if (others.isNotEmpty()) {
                    append("\n\nAlso found:\n").append(others.joinToString("\n"))
                }
            },
            "GPG Found",
        )
    }

    /** Runs the configured gpg, which is the only way to tell a working one from a stale path. */
    private fun test() {
        val executable = executableField.text.trim().ifEmpty { GpgLocator.detect() }
        if (executable.isNullOrBlank()) {
            Messages.showErrorDialog("No gpg executable is set and none was found.", "GPG")
            return
        }
        val home = homeField.text.trim().takeIf { it.isNotEmpty() }
        val description = runCatching {
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable { GpgLocator.describe(executable, home) },
                "Running gpg --version",
                true,
                null,
            )
        }
        description.fold(
            onSuccess = { Messages.showInfoMessage(it, "GPG") },
            onFailure = { Messages.showErrorDialog(it.message ?: it.toString(), "GPG") },
        )
    }
}
