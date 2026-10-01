package com.opendevtools.forge.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.opendevtools.core.ui.listRenderer
import com.opendevtools.forge.model.ForgeAccount
import com.opendevtools.forge.model.ForgeKind
import com.opendevtools.forge.model.IssueState
import com.opendevtools.forge.service.ForgeService
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.ListSelectionModel

/**
 * The account table is the heart of this plugin's configuration: several forges, several
 * instances of the same forge, and a self-hosted one alongside a public one all coexist here.
 */
class ForgeConfigurable : BoundConfigurable("Git Forges") {

    private val settings = ForgeSettings.getInstance()

    /** Working copy; nothing is written until the user applies. */
    private val model = ListTableModel<ForgeAccount>(
        column<ForgeAccount>("Kind", 14) { it.kind.title },
        column<ForgeAccount>("Name", 20) { it.displayName },
        column<ForgeAccount>("Server", 34) { it.baseUrl },
        column<ForgeAccount>("User", 16) { it.username },
        column<ForgeAccount>("SSL", 6) { if (it.verifySsl) "verified" else "ignored" },
    ).apply { items = settings.accountList().toMutableList() }

    /** Tokens typed in this session, applied along with the accounts. */
    private val pendingTokens = mutableMapOf<String, String>()

    private val table = JBTable(model).apply {
        setShowGrid(false)
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        tableHeader.reorderingAllowed = false
        rowHeight = JBUI.scale(22)
        emptyText.text = "No accounts yet - add GitHub, GitLab, Gitea or Forgejo"
    }

    override fun createPanel() = panel {
        group("Accounts") {
            row {
                cell(accountToolbar()).align(AlignX.FILL)
            }
            row {
                comment(
                    "Each account keeps its token in the IDE password safe, never in " +
                        "git-forge-tools.xml. Self-hosted instances need the server URL only - " +
                        "the API path is added automatically.",
                )
            }
        }
        group("Repositories") {
            row("Show issues and pull requests:") {
                comboBox(IssueState.entries)
                    .bindItem(settings::defaultIssueState.toNullableProperty())
            }
            row {
                checkBox("Show forks").bindSelected(settings::showForks)
            }
            row {
                checkBox("Show private repositories").bindSelected(settings::showPrivate)
            }
            row {
                checkBox("Highlight the repository of the open project")
                    .bindSelected(settings::matchProjectRemote)
                    .comment("Matched against the git remote in the project's .git/config")
            }
            row {
                checkBox("Confirm destructive actions")
                    .bindSelected(settings::confirmDestructiveActions)
                    .comment("Merging and closing ask before running")
            }
        }
    }

    override fun isModified(): Boolean =
        super.isModified() ||
            model.items != settings.accountList() ||
            pendingTokens.isNotEmpty()

    override fun apply() {
        super.apply()
        settings.replaceAccounts(model.items)
        pendingTokens.forEach { (accountId, token) ->
            settings.account(accountId)?.let { ForgeCredentials.set(it.credentialKey, token) }
        }
        pendingTokens.clear()
        ForgeSettings.fireChanged()
    }

    override fun reset() {
        super.reset()
        model.items = settings.accountList().toMutableList()
        pendingTokens.clear()
    }

    private fun accountToolbar(): JComponent = ToolbarDecorator.createDecorator(table)
        .setAddAction { editAccount(null) }
        .setEditAction { table.selectedRow.takeIf { it >= 0 }?.let { editAccount(it) } }
        .setRemoveAction {
            table.selectedRow.takeIf { it >= 0 }?.let { row ->
                val index = table.convertRowIndexToModel(row)
                pendingTokens.remove(model.getItem(index).id)
                model.removeRow(index)
            }
        }
        .addExtraAction(testConnectionAction())
        .disableUpDownActions()
        .createPanel()

    private fun testConnectionAction() =
        object : com.intellij.openapi.actionSystem.AnAction(
            "Test Connection",
            "Check the server and the token of the selected account",
            com.intellij.icons.AllIcons.Actions.Execute,
        ) {
            override fun getActionUpdateThread() =
                com.intellij.openapi.actionSystem.ActionUpdateThread.EDT

            override fun update(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                e.presentation.isEnabled = table.selectedRow >= 0
            }

            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                val index = table.selectedRow.takeIf { it >= 0 } ?: return
                testConnection(model.getItem(table.convertRowIndexToModel(index)))
            }
        }

    private fun editAccount(rowIndex: Int?) {
        val existing = rowIndex?.let { model.getItem(table.convertRowIndexToModel(it)) }
        val currentToken = existing?.let {
            pendingTokens[it.id] ?: ForgeCredentials.get(it.credentialKey)
        }

        val dialog = AccountDialog(existing, currentToken.orEmpty())
        if (!dialog.showAndGet()) {
            dialog.wipe()
            return
        }
        val account = dialog.account()
        val token = dialog.token()
        dialog.wipe()

        if (token.isNotEmpty()) pendingTokens[account.id] = token
        if (existing == null) {
            model.addRow(account)
        } else {
            model.items = model.items.map { if (it.id == account.id) account else it }.toMutableList()
        }
    }

    private fun testConnection(account: ForgeAccount) {
        val token = pendingTokens[account.id] ?: ForgeCredentials.get(account.credentialKey)
        if (token.isNullOrBlank()) {
            Messages.showErrorDialog("No token stored for this account.", "Git Forges")
            return
        }
        try {
            val summary = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable<String, Exception> {
                    ForgeService.getInstance().describeConnection(account, token)
                },
                "Connecting to ${account.kind.title}",
                true,
                null,
            )
            // A successful test is also how the account learns its own user name.
            val user = summary.substringAfter(" as ").substringBefore('\n').trim()
            if (user.isNotEmpty()) {
                model.items = model.items
                    .map { if (it.id == account.id) it.copy(username = user) else it }
                    .toMutableList()
            }
            Messages.showInfoMessage(summary, "Git Forges")
        } catch (e: Exception) {
            Messages.showErrorDialog(e.message ?: e.toString(), "Git Forges")
        }
    }

    private fun <T> column(name: String, width: Int, value: (T) -> String?): ColumnInfo<T, String> =
        object : ColumnInfo<T, String>(name) {
            override fun valueOf(item: T): String = value(item).orEmpty()

            override fun getPreferredStringValue(): String = "x".repeat(width)
        }
}

/** Adds or edits one account. */
class AccountDialog(private val existing: ForgeAccount?, token: String) : DialogWrapper(true) {

    private val kindCombo = JComboBox(DefaultComboBoxModel(ForgeKind.entries.toTypedArray())).apply {
        renderer = listRenderer("") { it.title }
        selectedItem = existing?.kind ?: ForgeKind.GITHUB
    }
    private val nameField = JBTextField(existing?.displayName.orEmpty(), 24)
    private val urlField = JBTextField(existing?.baseUrl.orEmpty(), 36)
    private val tokenField = JBPasswordField().apply { text = token }
    private val verifyCheckBox = JBCheckBox("Verify SSL certificate", existing?.verifySsl ?: true)

    init {
        title = if (existing == null) "Add Account" else "Edit Account"
        setOKButtonText(if (existing == null) "Add" else "Save")
        init()
        kindCombo.addActionListener { applyKindDefaults() }
        if (existing == null) applyKindDefaults()
    }

    fun account(): ForgeAccount {
        val kind = kindCombo.selectedItem as ForgeKind
        return ForgeAccount(
            id = existing?.id ?: ForgeSettings.newAccountId(),
            kind = kind,
            displayName = nameField.text.trim().ifBlank { kind.title },
            baseUrl = urlField.text.trim().trimEnd('/'),
            verifySsl = verifyCheckBox.isSelected,
            username = existing?.username,
        )
    }

    fun token(): String = String(tokenField.password)

    fun wipe() {
        tokenField.text = ""
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Kind:") {
            cell(kindCombo)
        }
        row("Name:") {
            cell(nameField).comment("How this account is labelled in the tool window")
        }
        row("Server URL:") {
            cell(urlField).comment(serverHint())
        }
        row("Token:") {
            cell(tokenField).comment((kindCombo.selectedItem as ForgeKind).tokenHint)
        }
        row {
            cell(verifyCheckBox).comment("Turn off only for a self-signed certificate")
        }
    }

    override fun getPreferredFocusedComponent(): JComponent =
        if (urlField.text.isBlank()) urlField else tokenField

    override fun doValidate(): ValidationInfo? {
        val url = urlField.text.trim()
        return when {
            url.isBlank() -> ValidationInfo("Enter the server URL", urlField)
            !url.startsWith("http") ->
                ValidationInfo("The URL must start with http:// or https://", urlField)

            tokenField.password.isEmpty() -> ValidationInfo("Enter an access token", tokenField)
            else -> null
        }
    }

    /** Pre-fills the public host for a hosted forge and leaves it blank for a self-hosted one. */
    private fun applyKindDefaults() {
        val kind = kindCombo.selectedItem as ForgeKind
        if (urlField.text.isBlank() || ForgeKind.entries.any { it.defaultBaseUrl == urlField.text }) {
            urlField.text = kind.defaultBaseUrl
        }
        if (nameField.text.isBlank()) nameField.text = kind.title
    }

    private fun serverHint(): String = when (kindCombo.selectedItem as ForgeKind) {
        ForgeKind.GITHUB -> "https://api.github.com"
        ForgeKind.GITHUB_ENTERPRISE -> "The server root, for example https://github.example.com"
        ForgeKind.GITLAB -> "https://gitlab.com or your own instance"
        ForgeKind.GITEA, ForgeKind.FORGEJO -> "The server root, for example https://git.example.com"
    }
}
