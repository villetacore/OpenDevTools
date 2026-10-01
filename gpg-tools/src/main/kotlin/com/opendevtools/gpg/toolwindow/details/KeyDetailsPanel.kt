package com.opendevtools.gpg.toolwindow.details

import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.opendevtools.core.format.Formats
import com.opendevtools.core.ui.PropertiesTable
import com.opendevtools.core.ui.Property
import com.opendevtools.gpg.model.GpgKey
import com.opendevtools.gpg.model.GpgSignature
import com.opendevtools.gpg.model.GpgSubkey
import com.opendevtools.gpg.model.GpgUserId
import com.opendevtools.gpg.model.KeyCapability
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * The right half of the tool window: everything about the selected certificate.
 *
 * Split into a summary, its identities and its subkeys because those are three different questions
 * - "is this key any good", "who does it claim to be", "which part of it can do what" - and a
 * single flat sheet answers none of them well.
 */
class KeyDetailsPanel : JPanel(BorderLayout()) {

    private val summary = PropertiesTable("Select a certificate")

    private val userIdModel = ListTableModel<GpgUserId>(
        textColumn<GpgUserId>("User ID") { it.display },
        textColumn<GpgUserId>("Validity") { it.validity.title },
        textColumn<GpgUserId>("Created") { Formats.date(it.created) },
        textColumn<GpgUserId>("Certified by") {
            it.certifications.joinToString(", ") { signature -> signature.signerDisplay }
                .ifEmpty { "-" }
        },
    )

    private val userIdTable = table(userIdModel, "This certificate has no user ids")

    private val subkeyModel = ListTableModel<GpgSubkey>(
        textColumn<GpgSubkey>("Key ID") { it.shortKeyId },
        textColumn<GpgSubkey>("Algorithm") { it.algorithmTitle },
        textColumn<GpgSubkey>("Capabilities") { KeyCapability.describe(it.capabilities) },
        textColumn<GpgSubkey>("Created") { Formats.date(it.created) },
        textColumn<GpgSubkey>("Expires") { if (it.expires <= 0) "never" else Formats.date(it.expires) },
        textColumn<GpgSubkey>("Secret") {
            when {
                it.cardSerial != null -> "on card " + it.cardSerial
                it.hasSecret -> "in keyring"
                else -> "-"
            }
        },
        textColumn<GpgSubkey>("Status") {
            when {
                it.isRevoked -> "revoked"
                it.isExpired -> "expired"
                else -> it.validity.title
            }
        },
    )

    private val subkeyTable = table(subkeyModel, "This certificate has no subkeys")

    private val certificationModel = ListTableModel<GpgSignature>(
        textColumn<GpgSignature>("Signer") { it.signerDisplay },
        textColumn<GpgSignature>("Key ID") { it.keyId },
        textColumn<GpgSignature>("Kind") { it.classTitle },
        textColumn<GpgSignature>("Created") { Formats.date(it.created) },
        textColumn<GpgSignature>("Scope") { if (it.exportable) "exportable" else "this keyring only" },
    )

    private val certificationTable = table(
        certificationModel,
        "No certifications - turn on \"Load certifications\" in the settings to read them",
    )

    private val tabs = JBTabbedPane().apply {
        addTab("Summary", ScrollPaneFactory.createScrollPane(summary, true))
        addTab("User IDs", ScrollPaneFactory.createScrollPane(userIdTable, true))
        addTab("Subkeys", ScrollPaneFactory.createScrollPane(subkeyTable, true))
        addTab("Certifications", ScrollPaneFactory.createScrollPane(certificationTable, true))
    }

    private var current: GpgKey? = null

    init {
        border = JBUI.Borders.empty()
        add(tabs, BorderLayout.CENTER)
        clear()
    }

    fun selectedKey(): GpgKey? = current

    fun selectedUserId(): GpgUserId? =
        userIdTable.selectedRow.takeIf { it >= 0 }
            ?.let { userIdModel.getItem(userIdTable.convertRowIndexToModel(it)) }

    fun selectedSubkeys(): List<GpgSubkey> =
        selectedModelRows(subkeyTable).map { subkeyModel.getItem(it) }

    fun clear() {
        current = null
        summary.clear()
        userIdModel.items = emptyList()
        subkeyModel.items = emptyList()
        certificationModel.items = emptyList()
    }

    fun show(key: GpgKey) {
        current = key
        summary.setProperties(properties(key))
        userIdModel.items = key.userIds
        subkeyModel.items = key.subkeys
        certificationModel.items = key.userIds.flatMap { it.certifications }
    }

    private fun properties(key: GpgKey): List<Property> = buildList {
        add(Property("Name", key.primaryUserId?.name ?: "-"))
        Property.of("E-mail", key.email)?.let { add(it) }
        add(Property("Fingerprint", key.readableFingerprint))
        add(Property("Key ID", key.keyId))
        add(Property("Algorithm", key.algorithmTitle))
        add(Property("Created", Formats.timestamp(key.created)))
        add(Property("Expires", if (key.expires <= 0) "never" else Formats.timestamp(key.expires)))
        add(Property("Validity", key.validity.title))
        add(Property("Owner trust", key.ownerTrust.title))
        add(Property("This key can", KeyCapability.describe(key.capabilities)))
        add(Property("Certificate can", KeyCapability.describe(key.wholeCapabilities)))
        add(
            Property(
                "Secret key",
                when {
                    key.onCard -> "on a smartcard"
                    key.hasSecret -> "in this keyring"
                    else -> "not available"
                },
            ),
        )
        add(Property("User IDs", key.userIds.size.toString()))
        add(Property("Subkeys", key.subkeys.size.toString()))

        // Says in one line what the toolbar will and will not let the user do with this key.
        val usable = buildList {
            if (key.canSign) add("sign")
            if (key.canEncrypt) add("encrypt to")
            if (key.canCertify) add("certify with")
        }
        add(Property("Usable to", usable.joinToString(", ").ifEmpty { "nothing - see validity above" }))
    }

    private fun <T> table(model: ListTableModel<T>, emptyMessage: String): JBTable {
        // `emptyText` on the table shadows this parameter inside the apply block below.
        val message = emptyMessage
        return JBTable(model).apply {
            setShowGrid(false)
            tableHeader.reorderingAllowed = false
            setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
            emptyText.text = message
        }
    }

    private fun selectedModelRows(table: JBTable): List<Int> =
        table.selectedRows.map { table.convertRowIndexToModel(it) }

    private fun <T> textColumn(title: String, value: (T) -> String): ColumnInfo<T, String> =
        object : ColumnInfo<T, String>(title) {
            override fun valueOf(item: T): String = value(item)
        }
}
