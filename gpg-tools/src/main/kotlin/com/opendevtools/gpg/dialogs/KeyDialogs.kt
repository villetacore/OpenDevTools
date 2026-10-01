package com.opendevtools.gpg.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.columns
import com.opendevtools.core.format.Formats
import com.opendevtools.core.ui.listRenderer
import com.opendevtools.gpg.model.ExpiryPreset
import com.opendevtools.gpg.model.ExportKind
import com.opendevtools.gpg.model.GpgKey
import com.opendevtools.gpg.model.GpgSubkey
import com.opendevtools.gpg.model.GpgUserId
import com.opendevtools.gpg.model.KeyAlgorithm
import com.opendevtools.gpg.model.KeyGenSpec
import com.opendevtools.gpg.model.OwnerTrust
import com.opendevtools.gpg.settings.GpgSettings
import com.intellij.openapi.ui.ComboBox
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent

/** Collects everything `--quick-generate-key` needs. */
class GenerateKeyDialog(project: Project?) : DialogWrapper(project) {

    private val nameField = JBTextField()
    private val emailField = JBTextField()
    private val commentField = JBTextField()
    private val algorithmBox = ComboBox(DefaultComboBoxModel(KeyAlgorithm.entries.toTypedArray()))
    private val expiry = ExpiryFields()

    init {
        title = "Generate a New Key Pair"
        algorithmBox.renderer = listRenderer { it.title }
        setOKButtonText("Generate")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Name:") { cell(nameField).columns(36) }
        row("E-mail:") { cell(emailField).columns(36) }
        row("Comment:") {
            cell(commentField).columns(36)
                .comment("Optional; it becomes part of the user id and cannot be changed later")
        }
        row("Algorithm:") { cell(algorithmBox) }
        expiry.addTo(this)
        row {
            comment(
                "gpg-agent will ask for a passphrase for the new key in its own dialog. " +
                    "Choose one - a key without a passphrase is readable by anything that can " +
                    "read the file.",
            )
        }
    }

    override fun doValidate(): ValidationInfo? = when {
        nameField.text.isBlank() -> ValidationInfo("A name is required", nameField)

        emailField.text.isNotBlank() && !emailField.text.contains('@') ->
            ValidationInfo("That does not look like a mail address", emailField)

        // A user id is `Name (comment) <mail>`, so a name may not contain its own delimiters.
        nameField.text.any { it in "<>()" } ->
            ValidationInfo("A name cannot contain < > ( or )", nameField)

        else -> expiry.validate()
    }

    fun spec(): KeyGenSpec = KeyGenSpec(
        name = nameField.text.trim(),
        email = emailField.text.trim(),
        comment = commentField.text.trim(),
        algorithm = algorithmBox.item ?: KeyAlgorithm.ED25519,
        expiry = expiry.expression(),
    )
}

/** Moves the expiry date of a primary key and, where the user asks, of its subkeys. */
class ChangeExpiryDialog(
    project: Project?,
    private val key: GpgKey,
    preselectedSubkeys: List<GpgSubkey>,
) : DialogWrapper(project) {

    private val expiry = ExpiryFields()
    private val subkeyBoxes = key.subkeys.map { subkey ->
        subkey to JBCheckBox(
            subkey.shortKeyId + "  " + subkey.algorithmTitle + "  " +
                if (subkey.expires <= 0) "never expires" else "until " + Formats.date(subkey.expires),
            subkey in preselectedSubkeys,
        )
    }

    init {
        title = "Change Expiry of " + key.displayName
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { label("Certificate: " + key.displayName + "  " + key.shortKeyId) }
        row {
            label(
                "Currently " +
                    if (key.expires <= 0) "never expires" else "expires " + Formats.date(key.expires),
            )
        }
        expiry.addTo(this)
        if (subkeyBoxes.isNotEmpty()) {
            group("Also change these subkeys") {
                subkeyBoxes.forEach { (_, box) -> row { cell(box) } }
            }
            row {
                comment(
                    "Subkeys keep their own expiry dates. A subkey left unticked keeps the date " +
                        "it has, even after the primary key moves.",
                )
            }
        }
    }

    override fun doValidate(): ValidationInfo? = expiry.validate()

    fun expiryExpression(): String = expiry.expression()

    fun selectedSubkeys(): List<String> =
        subkeyBoxes.filter { it.second.isSelected }.map { it.first.fingerprint }
}

/** Sets owner trust, which is what makes the web of trust work at all. */
class OwnerTrustDialog(project: Project?, private val key: GpgKey) : DialogWrapper(project) {

    private val box = ComboBox(DefaultComboBoxModel(OwnerTrust.entries.toTypedArray())).apply {
        renderer = listRenderer { it.title }
        item = key.ownerTrust
    }

    init {
        title = "Owner Trust"
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { label("How much do you trust " + key.displayName + " to certify other keys?") }
        row("Trust:") { cell(box) }
        row { comment(OwnerTrust.entries.joinToString("<br>") { "<b>" + it.title + "</b> - " + it.hint }) }
        row {
            comment(
                "This says nothing about whether the key is genuine - that is what certifying it " +
                    "does. It says whether you would believe this key's own certifications of others.",
            )
        }
    }

    fun trust(): OwnerTrust = box.item ?: OwnerTrust.UNKNOWN
}

/** Certifies someone else's certificate: the act of saying "this key really is theirs". */
class CertifyDialog(
    project: Project?,
    private val target: GpgKey,
    signingKeys: List<GpgKey>,
) : DialogWrapper(project) {

    private val signerBox = ComboBox(DefaultComboBoxModel(signingKeys.toTypedArray())).apply {
        renderer = listRenderer { it.displayName + "  " + it.shortKeyId }
        GpgSettings.getInstance().defaultSigningKey.takeIf { it.isNotBlank() }?.let { fingerprint ->
            signingKeys.firstOrNull { it.fingerprint == fingerprint }?.let { item = it }
        }
    }

    private val userIdBoxes = target.userIds.filterNot { it.isAttribute }.map { userId ->
        userId to JBCheckBox(userId.uid, true)
    }

    private val localBox = JBCheckBox(
        "Local certification only (never exported)",
        GpgSettings.getInstance().localCertificationsOnly,
    )

    init {
        title = "Certify " + target.displayName
        setOKButtonText("Certify")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { label("Fingerprint: " + target.readableFingerprint) }
        row {
            comment(
                "Check this fingerprint against one the owner gave you in person, over the phone " +
                    "or by another channel. Certifying a key you have not verified is what makes " +
                    "the web of trust useless.",
            )
        }
        group("Identities to certify") {
            userIdBoxes.forEach { (_, box) -> row { cell(box) } }
        }
        row("Certify with:") { cell(signerBox) }
        row { cell(localBox) }
    }

    override fun doValidate(): ValidationInfo? = when {
        signerBox.item == null -> ValidationInfo("No certificate of yours can certify others")
        userIdBoxes.none { it.second.isSelected } -> ValidationInfo("Select at least one identity")
        else -> null
    }

    fun signingKey(): String? = signerBox.item?.fingerprint

    fun isLocal(): Boolean = localBox.isSelected

    /** Empty means "all of them", which is what gpg does when no user id is named. */
    fun selectedUserIds(): List<String> =
        if (userIdBoxes.all { it.second.isSelected }) {
            emptyList()
        } else {
            userIdBoxes.filter { it.second.isSelected }.map { it.first.uid }
        }
}

/** Adds an identity to one of the user's own certificates. */
class AddUserIdDialog(project: Project?, private val key: GpgKey) : DialogWrapper(project) {

    private val nameField = JBTextField()
    private val emailField = JBTextField()
    private val commentField = JBTextField()

    init {
        title = "Add a User ID to " + key.displayName
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Name:") { cell(nameField).columns(36) }
        row("E-mail:") { cell(emailField).columns(36) }
        row("Comment:") { cell(commentField).columns(36) }
        row {
            comment(
                "A user id cannot be edited once it exists; the way to correct one is to add a " +
                    "new identity and revoke the old one.",
            )
        }
    }

    override fun doValidate(): ValidationInfo? = when {
        nameField.text.isBlank() -> ValidationInfo("A name is required", nameField)

        nameField.text.any { it in "<>()" } ->
            ValidationInfo("A name cannot contain < > ( or )", nameField)

        else -> null
    }

    fun userId(): String = KeyGenSpec(
        name = nameField.text,
        email = emailField.text,
        comment = commentField.text,
        algorithm = KeyAlgorithm.ED25519,
        expiry = "0",
    ).userId
}

/** Adds a subkey, which is how a certificate gains an ability it was not created with. */
class AddSubkeyDialog(project: Project?, private val key: GpgKey) : DialogWrapper(project) {

    private val algorithmBox = ComboBox(DefaultComboBoxModel(KeyAlgorithm.entries.toTypedArray())).apply {
        renderer = listRenderer { it.title }
    }

    private val usageBox = ComboBox(DefaultComboBoxModel(USAGES.keys.toTypedArray()))
    private val expiry = ExpiryFields()

    init {
        title = "Add a Subkey to " + key.displayName
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Algorithm:") { cell(algorithmBox) }
        row("Usage:") {
            cell(usageBox).comment("Not every algorithm supports every usage; gpg will say so")
        }
        expiry.addTo(this)
    }

    override fun doValidate(): ValidationInfo? = expiry.validate()

    fun algorithm(): String = (algorithmBox.item ?: KeyAlgorithm.ED25519).spec

    fun usage(): String = USAGES[usageBox.item] ?: "encrypt"

    fun expiryExpression(): String = expiry.expression()

    private companion object {
        val USAGES = linkedMapOf(
            "Encryption" to "encrypt",
            "Signing" to "sign",
            "Authentication" to "auth",
        )
    }
}

/** Chooses what an export should contain and whether it is text or binary. */
class ExportDialog(project: Project?, private val key: GpgKey) : DialogWrapper(project) {

    private val kinds = buildList {
        add(ExportKind.PUBLIC)
        if (key.hasSecret) {
            add(ExportKind.SECRET)
            add(ExportKind.SECRET_SUBKEYS)
            add(ExportKind.SSH)
        }
    }

    private val kindBox = ComboBox(DefaultComboBoxModel(kinds.toTypedArray())).apply {
        renderer = listRenderer { it.title }
    }

    private val armorBox = JBCheckBox("ASCII armor", GpgSettings.getInstance().armorByDefault)

    init {
        title = "Export " + key.displayName
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Export:") { cell(kindBox) }
        row { cell(armorBox).comment("Text that can be pasted or mailed, rather than binary") }
        row {
            comment(
                "Exporting a secret key writes the private half to a file. Anyone who gets that " +
                    "file and the passphrase becomes you.",
            )
        }
    }

    fun kind(): ExportKind = kindBox.item ?: ExportKind.PUBLIC

    fun armor(): Boolean = armorBox.isSelected || kind() == ExportKind.SSH
}

/** Picks the identity to revoke, since a certificate usually has more than one. */
class RevokeUserIdDialog(project: Project?, key: GpgKey) : DialogWrapper(project) {

    private val box = ComboBox(
        DefaultComboBoxModel(key.userIds.filterNot { it.isAttribute }.toTypedArray()),
    ).apply { renderer = listRenderer<GpgUserId> { it.uid } }

    init {
        title = "Revoke a User ID"
        setOKButtonText("Revoke")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Identity:") { cell(box) }
        row {
            comment(
                "Revoking an identity is permanent and, once the certificate is published, public. " +
                    "The identity stays on the key; it is marked as no longer valid.",
            )
        }
    }

    override fun doValidate(): ValidationInfo? =
        if (box.item == null) ValidationInfo("This certificate has no identity to revoke") else null

    fun userId(): String = box.item?.uid.orEmpty()
}

/**
 * The expiry row shared by the dialogs that set one.
 *
 * GnuPG takes an expression rather than a date - `2y`, `18m`, `0` for never - and this keeps the
 * presets and the free-text field agreeing on what gets sent.
 */
class ExpiryFields {

    private val presetBox = ComboBox(DefaultComboBoxModel(ExpiryPreset.entries.toTypedArray())).apply {
        renderer = listRenderer { it.title }
        item = ExpiryPreset.TWO_YEARS
    }

    private val customField = JBTextField().apply {
        emptyText.text = "for example 18m, 90d, or 2030-01-01"
        isEnabled = false
    }

    init {
        presetBox.addActionListener {
            customField.isEnabled = presetBox.item == ExpiryPreset.CUSTOM
        }
    }

    fun addTo(builder: com.intellij.ui.dsl.builder.Panel) {
        builder.row("Valid for:") { cell(presetBox) }
        builder.row("Custom:") {
            cell(customField).columns(24)
                .comment("A GnuPG expiry expression, or an ISO date")
        }
    }

    fun validate(): ValidationInfo? {
        if (presetBox.item != ExpiryPreset.CUSTOM) return null
        val text = customField.text.trim()
        return when {
            text.isEmpty() -> ValidationInfo("Enter an expiry, or choose a preset", customField)
            !EXPRESSION.matches(text) -> ValidationInfo(
                "Use a count with d, w, m or y - such as 18m - or an ISO date",
                customField,
            )

            else -> null
        }
    }

    fun expression(): String = when (val preset = presetBox.item) {
        null, ExpiryPreset.CUSTOM -> customField.text.trim().ifEmpty { "0" }
        else -> preset.expression
    }

    private companion object {
        /** `2y`, `90d`, a bare day count, or an ISO date; the four forms gpg accepts. */
        val EXPRESSION = Regex("""^(\d+[dwmy]?|\d{4}-\d{2}-\d{2}|\d{8}T\d{6})$""")
    }
}
