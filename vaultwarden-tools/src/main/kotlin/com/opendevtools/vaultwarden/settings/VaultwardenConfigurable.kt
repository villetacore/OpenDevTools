package com.opendevtools.vaultwarden.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import com.opendevtools.vaultwarden.model.VaultGrouping
import com.opendevtools.vaultwarden.service.VaultwardenService

class VaultwardenConfigurable : BoundConfigurable("Vaultwarden") {

    private val settings = VaultwardenSettings.getInstance()
    private var clientSecret: String = VaultwardenCredentials.get(settings.credentialKey()).orEmpty()

    override fun createPanel() = panel {
        group("Server") {
            row("Server URL:") {
                textField()
                    .bindText(settings::serverUrl)
                    .columns(40)
                    .comment("Your Vaultwarden instance, for example https://vault.example.com")
            }
            row {
                checkBox("Verify SSL certificate")
                    .bindSelected(settings::verifySsl)
                    .comment("Turn off only for a self-signed certificate")
            }
        }
        group("Personal API key") {
            row("Client ID:") {
                textField()
                    .bindText(settings::clientId)
                    .columns(40)
                    .comment("Web vault: Settings | Security | Keys | View API Key - the user.<uuid> value")
            }
            row("Client secret:") {
                passwordField()
                    .bindText(::clientSecret)
                    .columns(40)
                    .comment("Stored in the IDE password safe")
            }
            row {
                button("Test Connection") { testConnection() }
            }
            row {
                comment(
                    "An API key is used because interactive login goes through your identity provider. " +
                        "Unlocking still needs the master password: it derives the vault key, " +
                        "and single sign-on cannot replace it.",
                )
            }
        }
        group("Tool Window") {
            row("Group items by:") {
                comboBox(VaultGrouping.entries)
                    .bindItem(settings::grouping.toNullableProperty())
            }
            row {
                checkBox("Show favorites first").bindSelected(settings::showFavoritesFirst)
            }
            row {
                checkBox("Confirm destructive actions")
                    .bindSelected(settings::confirmDestructiveActions)
                    .comment("Deleting an item, a folder or a Send asks before running")
            }
        }
        group("Security") {
            row("Clear the clipboard after:") {
                intTextField(0..600)
                    .bindIntText(settings::clipboardClearSeconds)
                    .comment("Seconds; 0 keeps copied secrets in the clipboard")
            }
            row("Lock the vault after:") {
                intTextField(0..480)
                    .bindIntText(settings::autoLockMinutes)
                    .comment("Minutes of inactivity; 0 disables auto-lock")
            }
        }
    }

    override fun apply() {
        super.apply()
        VaultwardenCredentials.set(settings.credentialKey(), clientSecret)
        VaultwardenSettings.fireStateChanged()
    }

    override fun reset() {
        clientSecret = VaultwardenCredentials.get(settings.credentialKey()).orEmpty()
        super.reset()
    }

    private fun testConnection() {
        apply()
        try {
            val summary = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable<String, Exception> { VaultwardenService.getInstance().testConnection() },
                "Connecting to Vaultwarden",
                true,
                null,
            )
            Messages.showInfoMessage(summary, "Vaultwarden")
        } catch (e: Exception) {
            Messages.showErrorDialog(e.message ?: e.toString(), "Vaultwarden")
        }
    }
}
