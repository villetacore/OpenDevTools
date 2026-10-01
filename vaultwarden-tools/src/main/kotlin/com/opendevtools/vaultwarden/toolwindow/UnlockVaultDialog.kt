package com.opendevtools.vaultwarden.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/**
 * Asks for the master password, which is the only thing that can derive the vault keys -
 * signing in through an identity provider does not replace it. The password is returned as a
 * char array, wiped by the caller and never stored.
 */
class UnlockVaultDialog(project: Project?, private val serverUrl: String) : DialogWrapper(project) {

    private val passwordField = JBPasswordField()

    init {
        title = "Unlock Vault"
        setOKButtonText("Unlock")
        init()
    }

    fun password(): CharArray = passwordField.password

    /** getPassword() hands out a copy, so clearing the document is what drops the chars. */
    fun wipe() {
        passwordField.text = ""
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Server:") {
            label(serverUrl.ifBlank { "not configured" })
        }
        row("Master password:") {
            cell(passwordField).columns(30)
        }
        row {
            comment("The master password never leaves this machine; it only derives the vault key.")
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = passwordField

    override fun doValidate(): ValidationInfo? =
        if (passwordField.password.isEmpty()) {
            ValidationInfo("Enter the master password", passwordField)
        } else {
            null
        }
}
