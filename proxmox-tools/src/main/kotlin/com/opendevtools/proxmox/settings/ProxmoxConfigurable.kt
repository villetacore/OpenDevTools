package com.opendevtools.proxmox.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import com.opendevtools.proxmox.model.TreeGrouping
import com.opendevtools.proxmox.service.ProxmoxService

class ProxmoxConfigurable : BoundConfigurable("Proxmox") {

    private val settings = ProxmoxSettings.getInstance()
    private var secret: String = ProxmoxCredentials.get(settings.credentialKey()).orEmpty()

    override fun createPanel() = panel {
        group("Server") {
            row("Host:") {
                textField()
                    .bindText(settings::host)
                    .columns(30)
                    .comment("Host name or IP address of the Proxmox VE server")
            }
            row("Port:") {
                intTextField(1..65535).bindIntText(settings::port)
            }
            row {
                checkBox("Verify SSL certificate")
                    .bindSelected(settings::verifySsl)
                    .comment("Turn off for the self-signed certificate a fresh Proxmox install ships with")
            }
        }
        group("Authentication") {
            buttonsGroup {
                row { radioButton("API token", ProxmoxAuthType.API_TOKEN) }
                row { radioButton("User name and password", ProxmoxAuthType.PASSWORD) }
            }.bind(settings::authType)
            row("User:") {
                textField()
                    .bindText(settings::user)
                    .columns(30)
                    .comment("Including the realm, for example root@pam")
            }
            row("Token name:") {
                textField()
                    .bindText(settings::tokenName)
                    .columns(30)
                    .comment("Token ID without the user part; ignored for password authentication")
            }
            row("Secret:") {
                passwordField()
                    .bindText(::secret)
                    .columns(30)
                    .comment("Token value or password - stored in the IDE password safe")
            }
            row {
                button("Test Connection") { testConnection() }
            }
        }
        group("Tool Window") {
            row("Group guests by:") {
                comboBox(TreeGrouping.entries)
                    .bindItem(settings::grouping.toNullableProperty())
                    .comment("The same three views the web UI offers, plus a flat list")
            }
            row("Double click opens:") {
                comboBox(ConsolePreference.entries)
                    .bindItem(settings::consolePreference.toNullableProperty())
            }
            row("Auto-refresh every:") {
                intTextField(0..3600)
                    .bindIntText(settings::autoRefreshSeconds)
                    .comment("Seconds; 0 disables automatic refresh")
            }
            row("Task history size:") {
                intTextField(20..2000)
                    .bindIntText(settings::taskHistoryLimit)
                    .comment("How many task rows the Tasks tab requests")
            }
            row {
                checkBox("Show templates").bindSelected(settings::showTemplates)
            }
            row {
                checkBox("Show storages in the tree").bindSelected(settings::showStoragesInTree)
            }
            row {
                checkBox("Confirm destructive actions")
                    .bindSelected(settings::confirmDestructiveActions)
                    .comment("Stop, reboot, rollback, restore and delete ask before running")
            }
        }
    }

    override fun apply() {
        super.apply()
        ProxmoxCredentials.set(settings.credentialKey(), secret)
        ProxmoxService.getInstance().invalidate()
        ProxmoxSettings.fireChanged()
    }

    override fun reset() {
        secret = ProxmoxCredentials.get(settings.credentialKey()).orEmpty()
        super.reset()
    }

    private fun testConnection() {
        apply()
        val config = settings.toConfig(secret)
        if (!config.isComplete) {
            Messages.showErrorDialog("Fill in host, user and secret first.", "Proxmox Connection")
            return
        }
        try {
            val summary = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable<String, Exception> {
                    ProxmoxService.getInstance().describeConnection(config)
                },
                "Testing Proxmox Connection",
                true,
                null,
            )
            Messages.showInfoMessage(summary, "Proxmox Connection")
        } catch (e: Exception) {
            Messages.showErrorDialog(e.message ?: e.toString(), "Proxmox Connection")
        }
    }
}
