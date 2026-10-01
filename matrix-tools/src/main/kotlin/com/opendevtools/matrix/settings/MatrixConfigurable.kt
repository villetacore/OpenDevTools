package com.opendevtools.matrix.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import com.opendevtools.matrix.model.RoomGrouping
import com.opendevtools.matrix.service.MatrixService
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JRadioButton

class MatrixConfigurable : BoundConfigurable("Matrix") {

    private val settings = MatrixSettings.getInstance()

    override fun createPanel() = panel {
        group("Account") {
            row("Homeserver:") {
                textField()
                    .bindText(settings::homeserverUrl)
                    .columns(36)
                    .comment("For example https://matrix.org or your own Synapse or Conduit server")
            }
            row("Signed in as:") {
                label(settings.userId.ifBlank { "not signed in" })
            }
            row {
                checkBox("Verify SSL certificate")
                    .bindSelected(settings::verifySsl)
                    .comment("Turn off only for a self-signed certificate")
            }
            row {
                button("Sign In...") { signIn() }
                button("Test Connection") { testConnection() }
                button("Sign Out") { signOut() }
            }
            row {
                comment(
                    "Signing in creates a device on the homeserver and stores its access token in " +
                        "the IDE password safe. The password itself is never stored.",
                )
            }
        }
        group("Rooms") {
            row("Order rooms by:") {
                comboBox(RoomGrouping.entries).bindItem(settings::grouping.toNullableProperty())
            }
            row {
                checkBox("List end-to-end encrypted rooms")
                    .bindSelected(settings::showEncryptedRooms)
                    .comment("Their messages cannot be read here; see the plugin description")
            }
            row {
                checkBox("Mark a room read when it is opened").bindSelected(settings::markReadOnOpen)
            }
            row("Messages per page:") {
                intTextField(10..200)
                    .bindIntText(settings::messagePageSize)
                    .comment("Used by the initial sync and by \"Load earlier messages\"")
            }
        }
        group("Notifications") {
            row {
                checkBox("Notify when I am mentioned").bindSelected(settings::notifyOnMention)
            }
            row {
                checkBox("Notify on every message")
                    .bindSelected(settings::notifyOnAnyMessage)
                    .comment("Noisy on busy rooms; the room list already shows unread counts")
            }
        }
        group("Advanced") {
            row("Sync timeout:") {
                intTextField(5..120)
                    .bindIntText(settings::syncTimeoutSeconds)
                    .comment(
                        "Seconds the sync request waits on the server before returning empty. " +
                            "Longer is cheaper; shorter reconnects faster on a flaky link.",
                    )
            }
        }
    }

    override fun apply() {
        super.apply()
        MatrixSettings.fireStateChanged()
    }

    private fun signIn() {
        super.apply()
        val dialog = SignInDialog(settings.homeserverUrl, settings.verifySsl)
        if (!dialog.showAndGet()) {
            dialog.wipe()
            return
        }
        val homeserver = dialog.homeserver()
        val verifySsl = dialog.verifySsl()
        val useToken = dialog.usesToken()
        val user = dialog.user()
        val secret = dialog.secret()
        dialog.wipe()

        try {
            val userId = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable<String, Exception> {
                    val service = MatrixService.getInstance()
                    if (useToken) {
                        service.signInWithToken(homeserver, String(secret), verifySsl)
                    } else {
                        service.signIn(homeserver, user, secret, verifySsl)
                    }
                },
                "Signing in to Matrix",
                true,
                null,
            )
            Messages.showInfoMessage("Signed in as $userId.", "Matrix")
            reset()
            MatrixSettings.fireStateChanged()
        } catch (e: Exception) {
            Messages.showErrorDialog(e.message ?: e.toString(), "Matrix")
        } finally {
            secret.fill(' ')
        }
    }

    private fun signOut() {
        val confirmed = Messages.showYesNoDialog(
            "Sign out and invalidate this device's access token?",
            "Matrix",
            Messages.getQuestionIcon(),
        ) == Messages.YES
        if (!confirmed) return

        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            { MatrixService.getInstance().signOut() },
            "Signing out of Matrix",
            false,
            null,
        )
        reset()
        MatrixSettings.fireStateChanged()
    }

    private fun testConnection() {
        super.apply()
        try {
            val summary = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable<String, Exception> { MatrixService.getInstance().testConnection() },
                "Connecting to the homeserver",
                true,
                null,
            )
            Messages.showInfoMessage(summary, "Matrix")
        } catch (e: Exception) {
            Messages.showErrorDialog(e.message ?: e.toString(), "Matrix")
        }
    }
}

/**
 * Sign-in dialog. Two ways in: a password, which the plugin exchanges for a device token and then
 * forgets, or an access token created elsewhere, for accounts behind SSO where the password flow
 * is not available.
 */
class SignInDialog(homeserver: String, verifySsl: Boolean) : DialogWrapper(true) {

    private val homeserverField = JBTextField(homeserver, 32)
    private val passwordMode = JRadioButton("User name and password", true)
    private val tokenMode = JRadioButton("Access token", false)
    private val userField = JBTextField(24)
    private val secretField = JBPasswordField()
    private val verifyCheckBox = JBCheckBox("Verify SSL certificate", verifySsl)

    init {
        title = "Sign In to Matrix"
        setOKButtonText("Sign In")
        ButtonGroup().apply {
            add(passwordMode)
            add(tokenMode)
        }
        init()
        val update = { _: java.awt.event.ActionEvent -> userField.isEnabled = passwordMode.isSelected }
        passwordMode.addActionListener(update)
        tokenMode.addActionListener(update)
    }

    fun homeserver(): String = homeserverField.text.trim()

    fun usesToken(): Boolean = tokenMode.isSelected

    fun user(): String = userField.text.trim()

    fun secret(): CharArray = secretField.password

    fun verifySsl(): Boolean = verifyCheckBox.isSelected

    fun wipe() {
        secretField.text = ""
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Homeserver:") { cell(homeserverField) }
        buttonsGroup {
            row {
                cell(passwordMode)
                cell(tokenMode)
            }
        }
        row("User:") {
            cell(userField).comment("Local part or full id, for example alex or @alex:example.org")
        }
        row("Password or token:") { cell(secretField).columns(32) }
        row { cell(verifyCheckBox) }
        row {
            comment(
                "An access token is what to use when the account signs in through an identity " +
                    "provider: create one in Element under Settings | Help &amp; About.",
            )
        }
    }

    override fun getPreferredFocusedComponent(): JComponent =
        if (userField.isEnabled) userField else secretField

    override fun doValidate(): ValidationInfo? = when {
        homeserverField.text.isBlank() -> ValidationInfo("Enter the homeserver URL", homeserverField)
        !homeserverField.text.trim().startsWith("http") ->
            ValidationInfo("The URL must start with http:// or https://", homeserverField)

        passwordMode.isSelected && userField.text.isBlank() ->
            ValidationInfo("Enter the user name", userField)

        secretField.password.isEmpty() ->
            ValidationInfo(
                if (tokenMode.isSelected) "Enter the access token" else "Enter the password",
                secretField,
            )

        else -> null
    }
}
