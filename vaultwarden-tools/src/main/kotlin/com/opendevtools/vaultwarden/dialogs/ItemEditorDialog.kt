package com.opendevtools.vaultwarden.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.opendevtools.core.ui.listRenderer
import com.opendevtools.vaultwarden.model.CardDetails
import com.opendevtools.vaultwarden.model.IdentityDetails
import com.opendevtools.vaultwarden.model.LoginDetails
import com.opendevtools.vaultwarden.model.Reprompt
import com.opendevtools.vaultwarden.model.SshKeyDetails
import com.opendevtools.vaultwarden.model.UriMatchType
import com.opendevtools.vaultwarden.model.VaultField
import com.opendevtools.vaultwarden.model.VaultFieldType
import com.opendevtools.vaultwarden.model.VaultFolder
import com.opendevtools.vaultwarden.model.VaultItem
import com.opendevtools.vaultwarden.model.VaultItemType
import com.opendevtools.vaultwarden.model.VaultUri
import java.util.UUID
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent

/**
 * Creates or edits a vault item. Which section is shown follows the item type, exactly as in the
 * web vault; the type itself is fixed once an item exists, because the server cannot convert one
 * kind of item into another.
 */
class ItemEditorDialog(
    private val project: Project?,
    private val original: VaultItem?,
    folders: List<VaultFolder>,
    defaultFolderId: String?,
    defaultType: VaultItemType,
) : DialogWrapper(project) {

    private val isNew = original == null

    private val typeCombo = JComboBox(DefaultComboBoxModel(VaultItemType.entries.toTypedArray())).apply {
        renderer = listRenderer("") { it.singular }
        selectedItem = original?.type ?: defaultType
        isEnabled = isNew
    }
    private val nameField = JBTextField(original?.name.orEmpty())
    private val folderCombo = JComboBox<VaultFolder>().apply {
        renderer = listRenderer("") { it.name }
        model = DefaultComboBoxModel(folders.toTypedArray())
        selectedItem = folders.firstOrNull { it.id == (original?.folderId ?: defaultFolderId) }
            ?: folders.firstOrNull()
    }
    private val favoriteCheckBox = JBCheckBox("Favorite", original?.favorite == true)
    private val repromptCheckBox = JBCheckBox(
        "Ask for the master password before revealing",
        original?.reprompt == Reprompt.PASSWORD,
    )
    private val notesArea = JBTextArea(5, 40).apply { text = original?.notes.orEmpty() }

    // --- Login ---------------------------------------------------------------------------------
    private val usernameField = JBTextField(original?.login?.username.orEmpty())
    private val passwordField = JBPasswordField().apply { text = original?.login?.password.orEmpty() }
    private val showPasswordCheckBox = JBCheckBox("Show", false)
    private val totpField = JBTextField(original?.login?.totp.orEmpty())

    private val uriModel = ListTableModel<UriRow>(
        column<UriRow>("URI") { it.uri },
        column<UriRow>("Match") { it.match.title },
    ).apply {
        items = original?.login?.uris.orEmpty().map { UriRow(it.uri, it.match) }.toMutableList()
    }
    private val uriTable = table(uriModel, "No URIs")

    // --- Card ----------------------------------------------------------------------------------
    private val cardholderField = JBTextField(original?.card?.cardholderName.orEmpty())
    private val brandField = JBTextField(original?.card?.brand.orEmpty())
    private val cardNumberField = JBPasswordField().apply { text = original?.card?.number.orEmpty() }
    private val expMonthField = JBTextField(original?.card?.expMonth.orEmpty(), 4)
    private val expYearField = JBTextField(original?.card?.expYear.orEmpty(), 6)
    private val cardCodeField = JBPasswordField().apply { text = original?.card?.code.orEmpty() }

    // --- Identity -------------------------------------------------------------------------------
    private val idTitleField = JBTextField(original?.identity?.title.orEmpty(), 8)
    private val idFirstNameField = JBTextField(original?.identity?.firstName.orEmpty())
    private val idMiddleNameField = JBTextField(original?.identity?.middleName.orEmpty())
    private val idLastNameField = JBTextField(original?.identity?.lastName.orEmpty())
    private val idUsernameField = JBTextField(original?.identity?.username.orEmpty())
    private val idCompanyField = JBTextField(original?.identity?.company.orEmpty())
    private val idEmailField = JBTextField(original?.identity?.email.orEmpty())
    private val idPhoneField = JBTextField(original?.identity?.phone.orEmpty())
    private val idSsnField = JBPasswordField().apply { text = original?.identity?.ssn.orEmpty() }
    private val idPassportField = JBTextField(original?.identity?.passportNumber.orEmpty())
    private val idLicenseField = JBTextField(original?.identity?.licenseNumber.orEmpty())
    private val idAddress1Field = JBTextField(original?.identity?.address1.orEmpty())
    private val idAddress2Field = JBTextField(original?.identity?.address2.orEmpty())
    private val idCityField = JBTextField(original?.identity?.city.orEmpty())
    private val idStateField = JBTextField(original?.identity?.state.orEmpty())
    private val idPostalField = JBTextField(original?.identity?.postalCode.orEmpty())
    private val idCountryField = JBTextField(original?.identity?.country.orEmpty())

    // --- SSH key --------------------------------------------------------------------------------
    private val sshPrivateArea = JBTextArea(4, 40).apply { text = original?.sshKey?.privateKey.orEmpty() }
    private val sshPublicArea = JBTextArea(3, 40).apply { text = original?.sshKey?.publicKey.orEmpty() }
    private val sshFingerprintField = JBTextField(original?.sshKey?.fingerprint.orEmpty())

    // --- Custom fields ---------------------------------------------------------------------------
    private val fieldModel = ListTableModel<FieldRow>(
        column<FieldRow>("Name") { it.name },
        column<FieldRow>("Value") { if (it.type == VaultFieldType.HIDDEN) "••••••" else it.value },
        column<FieldRow>("Type") { it.type.title },
    ).apply {
        items = original?.fields.orEmpty().map { FieldRow(it.name, it.value, it.type) }.toMutableList()
    }
    private val fieldTable = table(fieldModel, "No custom fields")

    /**
     * The type-specific sections are separate panels rather than rows of one big panel, because
     * showing and hiding a whole panel is the only way to switch sections without rebuilding the
     * dialog's layout.
     */
    private val loginPanel: DialogPanel = panel {
        group("Login") {
            row("Username:") { cell(usernameField).columns(36) }
            row("Password:") {
                cell(passwordField).columns(28)
                cell(showPasswordCheckBox)
                cell(JButton("Generate").apply { addActionListener { generatePassword() } })
            }
            row("Authenticator key:") {
                cell(totpField)
                    .columns(36)
                    .comment("A base32 secret or a full otpauth:// URI")
            }
            row {
                cell(uriToolbar()).align(AlignX.FILL)
            }
        }
    }

    private val cardPanel: DialogPanel = panel {
        group("Card") {
            row("Cardholder:") { cell(cardholderField).columns(36) }
            row("Brand:") { cell(brandField).columns(20) }
            row("Number:") { cell(cardNumberField).columns(30) }
            row("Expires:") {
                cell(expMonthField)
                cell(expYearField)
            }
            row("Security code:") { cell(cardCodeField).columns(10) }
        }
    }

    private val identityPanel: DialogPanel = panel {
        group("Identity") {
            row("Title:") { cell(idTitleField) }
            row("First name:") { cell(idFirstNameField).columns(30) }
            row("Middle name:") { cell(idMiddleNameField).columns(30) }
            row("Last name:") { cell(idLastNameField).columns(30) }
            row("Username:") { cell(idUsernameField).columns(30) }
            row("Company:") { cell(idCompanyField).columns(30) }
            row("Email:") { cell(idEmailField).columns(30) }
            row("Phone:") { cell(idPhoneField).columns(30) }
            row("Social security number:") { cell(idSsnField).columns(24) }
            row("Passport number:") { cell(idPassportField).columns(24) }
            row("License number:") { cell(idLicenseField).columns(24) }
            row("Address line 1:") { cell(idAddress1Field).columns(36) }
            row("Address line 2:") { cell(idAddress2Field).columns(36) }
            row("City:") { cell(idCityField).columns(24) }
            row("State:") { cell(idStateField).columns(24) }
            row("Postal code:") { cell(idPostalField).columns(12) }
            row("Country:") { cell(idCountryField).columns(24) }
        }
    }

    private val sshPanel: DialogPanel = panel {
        group("SSH Key") {
            row("Private key:") { cell(sshPrivateArea).columns(36) }
            row("Public key:") { cell(sshPublicArea).columns(36) }
            row("Fingerprint:") { cell(sshFingerprintField).columns(36) }
        }
    }

    class UriRow(var uri: String, var match: UriMatchType)
    class FieldRow(var name: String, var value: String, var type: VaultFieldType)

    init {
        title = if (isNew) "New Item" else "Edit ${original?.name}"
        setOKButtonText(if (isNew) "Create" else "Save")
        init()

        typeCombo.addActionListener { updateSectionVisibility() }
        showPasswordCheckBox.addActionListener {
            passwordField.echoChar = if (showPasswordCheckBox.isSelected) 0.toChar() else '•'
        }
        updateSectionVisibility()
    }

    /** The edited item. For a new item the id is a placeholder the server replaces. */
    fun item(): VaultItem {
        val type = typeCombo.selectedItem as VaultItemType
        return VaultItem(
            id = original?.id ?: UUID.randomUUID().toString(),
            name = nameField.text.trim(),
            type = type,
            folderId = (folderCombo.selectedItem as? VaultFolder)?.id,
            organizationId = original?.organizationId,
            collectionIds = original?.collectionIds.orEmpty(),
            notes = notesArea.text.takeIf { it.isNotBlank() },
            fields = fieldModel.items.map { VaultField(it.name, it.value, it.type) },
            favorite = favoriteCheckBox.isSelected,
            reprompt = if (repromptCheckBox.isSelected) Reprompt.PASSWORD else Reprompt.NONE,
            revisionDate = original?.revisionDate,
            creationDate = original?.creationDate,
            deletedDate = original?.deletedDate,
            login = if (type == VaultItemType.LOGIN) loginDetails() else null,
            card = if (type == VaultItemType.CARD) cardDetails() else null,
            identity = if (type == VaultItemType.IDENTITY) identityDetails() else null,
            sshKey = if (type == VaultItemType.SSH_KEY) sshKeyDetails() else null,
            attachments = original?.attachments.orEmpty(),
            passwordHistory = original?.passwordHistory.orEmpty(),
        )
    }

    /** The password as it was before the edit, so the service can record it in the history. */
    fun previousPassword(): String? = original?.login?.password

    override fun createCenterPanel(): JComponent = panel {
        row("Type:") { cell(typeCombo) }
        row("Name:") { cell(nameField).columns(36) }
        row("Folder:") { cell(folderCombo) }
        row {
            cell(favoriteCheckBox)
            cell(repromptCheckBox)
        }
        row { cell(loginPanel).align(AlignX.FILL) }
        row { cell(cardPanel).align(AlignX.FILL) }
        row { cell(identityPanel).align(AlignX.FILL) }
        row { cell(sshPanel).align(AlignX.FILL) }
        group("Custom Fields") {
            row { cell(fieldToolbar()).align(AlignX.FILL) }
        }
        group("Notes") {
            row { cell(notesArea).columns(36) }
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun doValidate(): ValidationInfo? = when {
        nameField.text.isBlank() -> ValidationInfo("Enter a name", nameField)
        fieldModel.items.any { it.name.isBlank() } ->
            ValidationInfo("A custom field needs a name", fieldTable)

        else -> null
    }

    private fun updateSectionVisibility() {
        val type = typeCombo.selectedItem as VaultItemType
        loginPanel.isVisible = type == VaultItemType.LOGIN
        cardPanel.isVisible = type == VaultItemType.CARD
        identityPanel.isVisible = type == VaultItemType.IDENTITY
        sshPanel.isVisible = type == VaultItemType.SSH_KEY
        // A secure note has no section of its own; the shared name and notes fields are enough.
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    // --- Detail builders ----------------------------------------------------------------------------

    private fun loginDetails() = LoginDetails(
        username = usernameField.text.trim().takeIf { it.isNotEmpty() },
        password = String(passwordField.password).takeIf { it.isNotEmpty() },
        totp = totpField.text.trim().takeIf { it.isNotEmpty() },
        uris = uriModel.items.filter { it.uri.isNotBlank() }.map { VaultUri(it.uri.trim(), it.match) },
        passwordRevisionDate = original?.login?.passwordRevisionDate,
    )

    private fun cardDetails() = CardDetails(
        cardholderName = cardholderField.text.trim().takeIf { it.isNotEmpty() },
        brand = brandField.text.trim().takeIf { it.isNotEmpty() },
        number = String(cardNumberField.password).takeIf { it.isNotEmpty() },
        expMonth = expMonthField.text.trim().takeIf { it.isNotEmpty() },
        expYear = expYearField.text.trim().takeIf { it.isNotEmpty() },
        code = String(cardCodeField.password).takeIf { it.isNotEmpty() },
    )

    private fun identityDetails() = IdentityDetails(
        title = idTitleField.text.trim().takeIf { it.isNotEmpty() },
        firstName = idFirstNameField.text.trim().takeIf { it.isNotEmpty() },
        middleName = idMiddleNameField.text.trim().takeIf { it.isNotEmpty() },
        lastName = idLastNameField.text.trim().takeIf { it.isNotEmpty() },
        username = idUsernameField.text.trim().takeIf { it.isNotEmpty() },
        company = idCompanyField.text.trim().takeIf { it.isNotEmpty() },
        ssn = String(idSsnField.password).takeIf { it.isNotEmpty() },
        passportNumber = idPassportField.text.trim().takeIf { it.isNotEmpty() },
        licenseNumber = idLicenseField.text.trim().takeIf { it.isNotEmpty() },
        email = idEmailField.text.trim().takeIf { it.isNotEmpty() },
        phone = idPhoneField.text.trim().takeIf { it.isNotEmpty() },
        address1 = idAddress1Field.text.trim().takeIf { it.isNotEmpty() },
        address2 = idAddress2Field.text.trim().takeIf { it.isNotEmpty() },
        address3 = null,
        city = idCityField.text.trim().takeIf { it.isNotEmpty() },
        state = idStateField.text.trim().takeIf { it.isNotEmpty() },
        postalCode = idPostalField.text.trim().takeIf { it.isNotEmpty() },
        country = idCountryField.text.trim().takeIf { it.isNotEmpty() },
    )

    private fun sshKeyDetails() = SshKeyDetails(
        privateKey = sshPrivateArea.text.trim().takeIf { it.isNotEmpty() },
        publicKey = sshPublicArea.text.trim().takeIf { it.isNotEmpty() },
        fingerprint = sshFingerprintField.text.trim().takeIf { it.isNotEmpty() },
    )

    // --- Sub-tables ----------------------------------------------------------------------------------

    private fun uriToolbar(): JComponent = ToolbarDecorator.createDecorator(uriTable)
        .setAddAction { editUri(null) }
        .setEditAction { uriTable.selectedRow.takeIf { it >= 0 }?.let { editUri(it) } }
        .setRemoveAction {
            uriTable.selectedRow.takeIf { it >= 0 }?.let { row ->
                uriModel.removeRow(uriTable.convertRowIndexToModel(row))
            }
        }
        .disableUpDownActions()
        .createPanel()

    private fun editUri(rowIndex: Int?) {
        val existing = rowIndex?.let { uriModel.getItem(uriTable.convertRowIndexToModel(it)) }
        val dialog = UriEditorDialog(
            project,
            existing?.uri.orEmpty(),
            existing?.match ?: UriMatchType.DEFAULT,
        )
        if (!dialog.showAndGet()) return
        if (existing == null) {
            uriModel.addRow(UriRow(dialog.uri(), dialog.match()))
        } else {
            existing.uri = dialog.uri()
            existing.match = dialog.match()
            uriModel.fireTableDataChanged()
        }
    }

    private fun fieldToolbar(): JComponent = ToolbarDecorator.createDecorator(fieldTable)
        .setAddAction { editField(null) }
        .setEditAction { fieldTable.selectedRow.takeIf { it >= 0 }?.let { editField(it) } }
        .setRemoveAction {
            fieldTable.selectedRow.takeIf { it >= 0 }?.let { row ->
                fieldModel.removeRow(fieldTable.convertRowIndexToModel(row))
            }
        }
        .disableUpDownActions()
        .createPanel()

    private fun editField(rowIndex: Int?) {
        val existing = rowIndex?.let { fieldModel.getItem(fieldTable.convertRowIndexToModel(it)) }
        val dialog = CustomFieldDialog(
            project,
            existing?.name.orEmpty(),
            existing?.value.orEmpty(),
            existing?.type ?: VaultFieldType.TEXT,
        )
        if (!dialog.showAndGet()) return
        if (existing == null) {
            fieldModel.addRow(FieldRow(dialog.fieldName(), dialog.fieldValue(), dialog.fieldType()))
        } else {
            existing.name = dialog.fieldName()
            existing.value = dialog.fieldValue()
            existing.type = dialog.fieldType()
            fieldModel.fireTableDataChanged()
        }
    }

    private fun generatePassword() {
        val dialog = PasswordGeneratorDialog(project)
        if (dialog.showAndGet()) passwordField.text = dialog.password()
    }

    private fun <T> table(model: ListTableModel<T>, empty: String) = JBTable(model).apply {
        setShowGrid(false)
        tableHeader.reorderingAllowed = false
        rowHeight = JBUI.scale(20)
        emptyText.text = empty
        preferredScrollableViewportSize = JBUI.size(400, 80)
    }

    private fun <T> column(name: String, value: (T) -> String): ColumnInfo<T, String> =
        object : ColumnInfo<T, String>(name) {
            override fun valueOf(item: T): String = value(item)
        }
}

/** Small editor for one URI row of a login item. */
class UriEditorDialog(
    project: Project?,
    uri: String,
    match: UriMatchType,
) : DialogWrapper(project) {

    private val uriField = JBTextField(uri, 36)
    private val matchCombo = JComboBox(DefaultComboBoxModel(UriMatchType.entries.toTypedArray())).apply {
        renderer = listRenderer("") { it.title }
        selectedItem = match
    }

    init {
        title = "URI"
        init()
    }

    fun uri(): String = uriField.text.trim()

    fun match(): UriMatchType = matchCombo.selectedItem as UriMatchType

    override fun createCenterPanel(): JComponent = panel {
        row("URI:") { cell(uriField) }
        row("Match detection:") {
            cell(matchCombo).comment("How the browser extension decides that a page matches")
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = uriField

    override fun doValidate(): ValidationInfo? =
        if (uriField.text.isBlank()) ValidationInfo("Enter a URI", uriField) else null
}

/** Small editor for one custom field. */
class CustomFieldDialog(
    project: Project?,
    name: String,
    value: String,
    type: VaultFieldType,
) : DialogWrapper(project) {

    private val nameField = JBTextField(name, 24)
    private val valueField = JBTextField(value, 36)
    private val typeCombo = JComboBox(
        DefaultComboBoxModel(
            // Linked fields point at another field of the same item; the plugin only displays them.
            VaultFieldType.entries.filter { it != VaultFieldType.LINKED }.toTypedArray(),
        ),
    ).apply {
        renderer = listRenderer("") { it.title }
        selectedItem = if (type == VaultFieldType.LINKED) VaultFieldType.TEXT else type
    }

    init {
        title = "Custom Field"
        init()
    }

    fun fieldName(): String = nameField.text.trim()

    fun fieldValue(): String = valueField.text

    fun fieldType(): VaultFieldType = typeCombo.selectedItem as VaultFieldType

    override fun createCenterPanel(): JComponent = panel {
        row("Name:") { cell(nameField) }
        row("Value:") { cell(valueField) }
        row("Type:") { cell(typeCombo) }
    }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun doValidate(): ValidationInfo? =
        if (nameField.text.isBlank()) ValidationInfo("Enter a field name", nameField) else null
}
