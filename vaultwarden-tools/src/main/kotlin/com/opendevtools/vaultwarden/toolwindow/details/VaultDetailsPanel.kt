package com.opendevtools.vaultwarden.toolwindow.details

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBTabbedPane
import com.opendevtools.core.format.Formats
import com.opendevtools.core.ui.Property
import com.opendevtools.core.ui.PropertiesTable
import com.opendevtools.vaultwarden.VaultwardenIcons
import com.opendevtools.vaultwarden.model.VaultItem
import com.opendevtools.vaultwarden.model.VaultItemType
import com.opendevtools.vaultwarden.model.VaultSend
import com.opendevtools.vaultwarden.model.VaultSnapshot
import com.opendevtools.vaultwarden.service.VaultReports
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * The details of the selected item. Secret values are masked until the user turns on
 * "Show Secrets", and the mask is applied by the table rather than by the strings themselves,
 * so a copy action still hands out the real value.
 */
class VaultDetailsPanel : JPanel(BorderLayout()) {

    private val overview = PropertiesTable("Select a vault item")
    private val fieldsTable = PropertiesTable("This item has no custom fields")
    private val attachmentsTable = PropertiesTable("This item has no attachments")
    private val historyTable = PropertiesTable("No previous passwords recorded")

    private val tabs = JBTabbedPane()

    private var current: VaultItem? = null
    private var snapshot: VaultSnapshot? = null
    private var showSecrets: Boolean = false

    init {
        tabs.addTab("Item", VaultwardenIcons.Login, overview)
        tabs.addTab("Fields", AllIcons.Nodes.DataTables, fieldsTable)
        tabs.addTab("Attachments", VaultwardenIcons.Attachment, attachmentsTable)
        tabs.addTab("History", AllIcons.Vcs.History, historyTable)
        add(tabs, BorderLayout.CENTER)
    }

    fun setShowSecrets(value: Boolean) {
        showSecrets = value
        listOf(overview, fieldsTable, attachmentsTable, historyTable)
            .forEach { it.setShowSecrets(value) }
    }

    fun setSnapshot(value: VaultSnapshot?) {
        snapshot = value
    }

    fun showNothing() {
        current = null
        listOf(overview, fieldsTable, attachmentsTable, historyTable).forEach { it.clear() }
    }

    fun show(item: VaultItem) {
        current = item
        overview.setProperties(overviewRows(item))
        fieldsTable.setProperties(
            item.fields.map { Property(it.name, it.value, secret = it.hidden) },
        )
        attachmentsTable.setProperties(
            item.attachments.map {
                Property(it.fileName, it.sizeName ?: Formats.bytes(it.size))
            },
        )
        historyTable.setProperties(
            item.passwordHistory.map {
                Property(Formats.isoTimestamp(it.lastUsedDate), it.password, secret = true)
            },
        )
        // Tab titles carry the counts, so the interesting tab is obvious without clicking through.
        tabs.setTitleAt(1, badge("Fields", item.fields.size))
        tabs.setTitleAt(2, badge("Attachments", item.attachments.size))
        tabs.setTitleAt(3, badge("History", item.passwordHistory.size))
    }

    fun showSend(send: VaultSend, serverUrl: String) {
        current = null
        overview.setProperties(sendRows(send, serverUrl))
        fieldsTable.clear()
        attachmentsTable.clear()
        historyTable.clear()
        tabs.selectedIndex = 0
    }

    fun currentItem(): VaultItem? = current

    private fun badge(title: String, count: Int): String =
        if (count == 0) title else "$title ($count)"

    private fun overviewRows(item: VaultItem): List<Property> = buildList {
        add(Property("Name", item.name))
        add(Property("Type", item.type.singular))

        when (item.type) {
            VaultItemType.LOGIN -> addLoginRows(item)
            VaultItemType.CARD -> addCardRows(item)
            VaultItemType.IDENTITY -> addIdentityRows(item)
            VaultItemType.SSH_KEY -> addSshKeyRows(item)
            VaultItemType.SECURE_NOTE -> Unit
        }

        item.notes?.takeIf { it.isNotBlank() }?.let { add(Property("Notes", it)) }

        val folderName = snapshot?.folder(item.folderId)?.name
        add(Property("Folder", folderName ?: "No Folder"))
        snapshot?.organization(item.organizationId)?.let { add(Property("Organization", it.name)) }
        if (item.favorite) add(Property("Favorite", "yes"))
        if (item.reprompt.code != 0) add(Property("Master password re-prompt", "yes"))
        if (item.readOnly) add(Property("Access", "read-only"))
        if (item.isDeleted) add(Property("Deleted", Formats.isoTimestamp(item.deletedDate)))
        item.creationDate?.let { add(Property("Created", Formats.isoTimestamp(it))) }
        item.revisionDate?.let { add(Property("Updated", Formats.isoTimestamp(it))) }
    }

    private fun MutableList<Property>.addLoginRows(item: VaultItem) {
        item.username?.takeIf { it.isNotBlank() }?.let { add(Property("Username", it)) }
        item.password?.takeIf { it.isNotBlank() }?.let { password ->
            add(Property("Password", password, secret = true))
            add(
                Property(
                    "Password strength",
                    com.opendevtools.vaultwarden.crypto.PasswordGenerator.strength(password).title,
                ),
            )
            snapshot?.let { loaded ->
                val reuse = VaultReports.reuseCount(loaded, item)
                if (reuse > 0) add(Property("Password reuse", "also used by $reuse other item(s)"))
            }
        }
        item.login?.passwordRevisionDate?.let {
            add(Property("Password changed", Formats.isoTimestamp(it)))
        }
        if (item.hasTotp) add(Property("One-time password", "configured"))
        item.login?.uris.orEmpty().forEachIndexed { index, uri ->
            val label = if (item.uris.size == 1) "URI" else "URI ${index + 1}"
            val match = uri.match.takeIf { it.code != null }?.let { " (${it.title})" }.orEmpty()
            add(Property(label, uri.uri + match))
        }
    }

    private fun MutableList<Property>.addCardRows(item: VaultItem) {
        val card = item.card ?: return
        card.cardholderName?.let { add(Property("Cardholder", it)) }
        card.brand?.let { add(Property("Brand", it)) }
        card.number?.let { add(Property("Number", it, secret = true)) }
        card.expiry?.let { add(Property("Expires", it)) }
        card.code?.let { add(Property("Security code", it, secret = true)) }
    }

    private fun MutableList<Property>.addIdentityRows(item: VaultItem) {
        val identity = item.identity ?: return
        identity.fullName?.let { add(Property("Name", it)) }
        identity.username?.let { add(Property("Username", it)) }
        identity.company?.let { add(Property("Company", it)) }
        identity.email?.let { add(Property("Email", it)) }
        identity.phone?.let { add(Property("Phone", it)) }
        identity.fullAddress?.let { add(Property("Address", it)) }
        identity.ssn?.let { add(Property("Social security number", it, secret = true)) }
        identity.passportNumber?.let { add(Property("Passport number", it, secret = true)) }
        identity.licenseNumber?.let { add(Property("License number", it, secret = true)) }
    }

    private fun MutableList<Property>.addSshKeyRows(item: VaultItem) {
        val sshKey = item.sshKey ?: return
        sshKey.fingerprint?.let { add(Property("Fingerprint", it)) }
        sshKey.publicKey?.let { add(Property("Public key", it)) }
        sshKey.privateKey?.let { add(Property("Private key", it, secret = true)) }
    }

    private fun sendRows(send: VaultSend, serverUrl: String): List<Property> = buildList {
        add(Property("Send", send.name))
        add(Property("Type", send.type.title))
        send.text?.let { add(Property("Text", it, secret = send.hideText)) }
        send.fileName?.let { add(Property("File", it + " (" + Formats.bytes(send.fileSize) + ")")) }
        send.notes?.let { add(Property("Notes", it)) }
        add(
            Property(
                "Views",
                send.maxAccessCount?.let { "${send.accessCount} of $it" } ?: send.accessCount.toString(),
            ),
        )
        send.expirationDate?.let { add(Property("Expires", Formats.isoTimestamp(it))) }
        send.deletionDate?.let { add(Property("Deletes", Formats.isoTimestamp(it))) }
        add(Property("Password protected", if (send.passwordProtected) "yes" else "no"))
        add(Property("Disabled", if (send.disabled) "yes" else "no"))
        // The key lives in the URL fragment, so the link itself is the secret.
        send.shareUrl(serverUrl)?.let { add(Property("Share link", it, secret = true)) }
    }
}
