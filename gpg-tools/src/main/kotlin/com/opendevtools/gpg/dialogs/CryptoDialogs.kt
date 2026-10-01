package com.opendevtools.gpg.dialogs

import com.intellij.icons.AllIcons
import com.intellij.ide.CopyPasteManagerEx
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.opendevtools.core.format.Formats
import com.opendevtools.core.ui.listRenderer
import com.opendevtools.gpg.model.GpgKey
import com.opendevtools.gpg.model.KeyserverHit
import com.opendevtools.gpg.model.SignatureStatus
import com.opendevtools.gpg.model.VerificationResult
import com.opendevtools.gpg.service.GpgService
import com.opendevtools.gpg.settings.GpgSettings
import java.awt.datatransfer.StringSelection
import javax.swing.JComponent
import javax.swing.ListSelectionModel

/**
 * Chooses recipients, and optionally a signature, for an encryption.
 *
 * Only certificates that can actually receive a message are offered: a sign-only key in the list
 * would produce a gpg error at the point of no return instead of an empty row here.
 */
class EncryptDialog(
    project: Project?,
    keys: List<GpgKey>,
    preselected: Collection<String> = emptyList(),
) : DialogWrapper(project) {

    private val settings = GpgSettings.getInstance()

    private val recipients = CertificateChooser(
        keys.filter { it.canEncrypt },
        multiSelect = true,
        emptyMessage = "No certificate in this keyring can receive encrypted data",
    )

    private val signingKeys = keys.filter { it.canSign }

    private val signBox = JBCheckBox("Sign as well", false)

    private val signerBox = ComboBox(javax.swing.DefaultComboBoxModel(signingKeys.toTypedArray())).apply {
        renderer = listRenderer { it.displayName + "  " + it.shortKeyId }
        isEnabled = false
        settings.defaultSigningKey.takeIf { it.isNotBlank() }?.let { fingerprint ->
            signingKeys.firstOrNull { it.fingerprint == fingerprint }?.let { item = it }
        }
    }

    private val armorBox = JBCheckBox("ASCII armor", settings.armorByDefault)

    private val untrustedBox = JBCheckBox("Encrypt to certificates you have not certified", true)

    init {
        title = "Encrypt"
        setOKButtonText("Encrypt")
        signBox.addActionListener { signerBox.isEnabled = signBox.isSelected }
        recipients.selectAll(
            // Encrypting to yourself as well is what keeps the result readable to the sender.
            preselected + listOfNotNull(settings.defaultSigningKey.takeIf { settings.encryptToSelf }),
        )
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { label("Encrypt to:") }
        row { cell(recipients).align(Align.FILL) }.resizableRow()
        row { cell(signBox) }
        row("Sign with:") { cell(signerBox) }
        row { cell(armorBox) }
        row {
            cell(untrustedBox).comment(
                "GnuPG refuses an uncertified recipient unless this is on. Turning it off means " +
                    "only certificates you have certified can be used.",
            )
        }
    }

    override fun doValidate(): ValidationInfo? = when {
        recipients.selection.isEmpty() -> ValidationInfo("Select at least one recipient")
        signBox.isSelected && signerBox.item == null ->
            ValidationInfo("No certificate of yours can sign")

        else -> null
    }

    fun recipientFingerprints(): List<String> = recipients.selection.map { it.fingerprint }

    fun signingKey(): String? = if (signBox.isSelected) signerBox.item?.fingerprint else null

    fun armor(): Boolean = armorBox.isSelected

    fun allowUntrusted(): Boolean = untrustedBox.isSelected
}

/** Chooses the key and the shape of a signature. */
class SignDialog(
    project: Project?,
    signingKeys: List<GpgKey>,
    /** Editor selections are clear-signed; files get a detached or an attached signature. */
    private val textMode: Boolean,
) : DialogWrapper(project) {

    private val settings = GpgSettings.getInstance()

    private val signerBox = ComboBox(javax.swing.DefaultComboBoxModel(signingKeys.toTypedArray())).apply {
        renderer = listRenderer { it.displayName + "  " + it.shortKeyId }
        settings.defaultSigningKey.takeIf { it.isNotBlank() }?.let { fingerprint ->
            signingKeys.firstOrNull { it.fingerprint == fingerprint }?.let { item = it }
        }
    }

    private val detachedBox = JBCheckBox("Detached signature (a separate .sig file)", true)

    private val clearSignBox = JBCheckBox("Clear signature (the text stays readable)", true)

    private val armorBox = JBCheckBox("ASCII armor", settings.armorByDefault)

    init {
        title = "Sign"
        setOKButtonText("Sign")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Sign with:") { cell(signerBox) }
        if (textMode) {
            row { cell(clearSignBox) }
        } else {
            row { cell(detachedBox) }
            row { cell(armorBox) }
        }
        row { comment("gpg-agent will ask for the key's passphrase in its own dialog.") }
    }

    override fun doValidate(): ValidationInfo? =
        if (signerBox.item == null) ValidationInfo("No certificate of yours can sign") else null

    fun signingKey(): String? = signerBox.item?.fingerprint

    fun detached(): Boolean = detachedBox.isSelected

    fun clearSign(): Boolean = clearSignBox.isSelected

    fun armor(): Boolean = armorBox.isSelected
}

/** Searches a keyserver and imports what the user picks. */
class KeyserverSearchDialog(private val project: Project?) : DialogWrapper(project) {

    private val queryField = JBTextField().apply {
        emptyText.text = "a mail address, a name or a key id"
    }

    private val model = ListTableModel<KeyserverHit>(
        column<KeyserverHit>("User ID") { it.display },
        column<KeyserverHit>("Key ID") { it.keyId },
        column<KeyserverHit>("Algorithm") { it.algorithmTitle },
        column<KeyserverHit>("Created") { Formats.date(it.created) },
        column<KeyserverHit>("Status") { if (it.revoked) "revoked" else "" },
    )

    private val table = JBTable(model).apply {
        setShowGrid(false)
        tableHeader.reorderingAllowed = false
        setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
        emptyText.text = "Enter a search and press Search"
        visibleRowCount = 10
    }

    init {
        title = "Look Up Certificates on " + GpgSettings.getInstance().keyserver
        setOKButtonText("Import Selected")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Search for:") {
            cell(queryField).columns(36)
            button("Search") { search() }
        }
        row {
            cell(ScrollPaneFactory.createScrollPane(table, true)).align(Align.FILL)
        }.resizableRow()
        row {
            comment(
                "A certificate from a keyserver is not trustworthy just because it was found. " +
                    "Check its fingerprint with the owner before you certify or rely on it.",
            )
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = queryField

    override fun doValidate(): ValidationInfo? =
        if (table.selectedRowCount == 0) ValidationInfo("Select the certificates to import") else null

    fun selectedKeyIds(): List<String> =
        table.selectedRows.map { model.getItem(table.convertRowIndexToModel(it)).keyId }

    private fun search() {
        val query = queryField.text.trim()
        if (query.isEmpty()) return
        val hits = runCatching {
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable { GpgService.getInstance().searchKeyserver(query) },
                "Searching the Keyserver",
                true,
                project,
            )
        }
        hits.fold(
            onSuccess = {
                model.items = it
                table.emptyText.text = if (it.isEmpty()) "Nothing found for \"$query\"" else ""
            },
            onFailure = {
                model.items = emptyList()
                table.emptyText.text = it.message ?: "The search failed"
            },
        )
    }

    private fun <T> column(title: String, value: (T) -> String): ColumnInfo<T, String> =
        object : ColumnInfo<T, String>(title) {
            override fun valueOf(item: T): String = value(item)
        }
}

/**
 * Shows what came out of a verification.
 *
 * A dialog rather than a balloon because "who signed this, and does that mean anything" is the
 * whole answer, and it does not fit on one line.
 */
class VerificationDialog(project: Project?, private val result: VerificationResult) :
    DialogWrapper(project) {

    init {
        title = "Signature Check"
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            label(
                when (result.overall) {
                    SignatureStatus.VALID -> "The signature is valid."
                    SignatureStatus.VALID_UNTRUSTED ->
                        "The signature is intact, but the signing key is not certified by you."

                    else -> result.overall.title
                },
            )
        }
        result.signatures.forEach { signature ->
            group(signature.status.title) {
                row("Signed by:") { label(signature.signerDisplay) }
                row("Key ID:") { label(signature.keyId.ifBlank { "-" }) }
                signature.fingerprint?.let { row("Fingerprint:") { label(it.chunked(4).joinToString(" ")) } }
                if (signature.created > 0) {
                    row("Signed at:") { label(Formats.timestamp(signature.created)) }
                }
                signature.detail.takeIf { it.isNotBlank() }?.let { row { comment(it) } }
            }
        }
        if (result.signatures.none { it.status == SignatureStatus.VALID }) {
            row {
                comment(
                    "An intact signature only proves the content matches the key that made it. " +
                        "It becomes a statement about a person once you have certified that key.",
                )
            }
        }
    }

    override fun createActions() = arrayOf(okAction)
}

/** Read-only text with a copy button; used for exports and for encrypted or signed output. */
class ArmoredTextDialog(
    project: Project?,
    dialogTitle: String,
    private val text: String,
    private val note: String? = null,
) : DialogWrapper(project) {

    private val area = JBTextArea(text).apply {
        isEditable = false
        lineWrap = false
        rows = 18
        columns = 68
        border = JBUI.Borders.empty(4)
    }

    init {
        title = dialogTitle
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            cell(ScrollPaneFactory.createScrollPane(area, true)).align(Align.FILL)
        }.resizableRow()
        note?.let { row { comment(it) } }
        row {
            button("Copy to Clipboard") {
                CopyPasteManagerEx.getInstance().setContents(StringSelection(text))
            }.applyToComponent { icon = AllIcons.Actions.Copy }
        }
    }

    override fun createActions() = arrayOf(okAction)
}
