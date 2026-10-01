package com.opendevtools.vaultwarden.dialogs

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.opendevtools.vaultwarden.crypto.PassphraseOptions
import com.opendevtools.vaultwarden.crypto.PasswordGenerator
import com.opendevtools.vaultwarden.crypto.PasswordOptions
import com.opendevtools.vaultwarden.settings.VaultwardenSettings
import java.awt.Font
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JRadioButton

/**
 * The password generator. The chosen shape is written back to the settings on OK, so the next
 * password comes out the same way without re-configuring the dialog.
 */
class PasswordGeneratorDialog(project: Project?) : DialogWrapper(project) {

    private val settings = VaultwardenSettings.getInstance()

    private val passwordMode = JRadioButton("Password", true)
    private val passphraseMode = JRadioButton("Passphrase", false)

    private val lengthSpinner =
        JBIntSpinner(settings.generatorLength, PasswordGenerator.MIN_LENGTH, PasswordGenerator.MAX_LENGTH)
    private val uppercase = JBCheckBox("A-Z", settings.generatorUppercase)
    private val lowercase = JBCheckBox("a-z", settings.generatorLowercase)
    private val digits = JBCheckBox("0-9", settings.generatorDigits)
    private val special = JBCheckBox("!@#\$%^&*", settings.generatorSpecial)
    private val minDigits = JBIntSpinner(settings.generatorMinDigits, 0, 9)
    private val minSpecial = JBIntSpinner(settings.generatorMinSpecial, 0, 9)
    private val avoidAmbiguous = JBCheckBox("Avoid ambiguous characters", settings.generatorAvoidAmbiguous)

    private val words =
        JBIntSpinner(settings.generatorPassphraseWords, PasswordGenerator.MIN_WORDS, PasswordGenerator.MAX_WORDS)
    private val separator = JBTextField(settings.generatorPassphraseSeparator, 4)
    private val capitalize = JBCheckBox("Capitalize", settings.generatorPassphraseCapitalize)
    private val includeNumber = JBCheckBox("Include a number", settings.generatorPassphraseNumber)

    private val result = JBTextField().apply {
        isEditable = false
        font = JBUI.Fonts.create(Font.MONOSPACED, JBUI.Fonts.label().size + 2)
    }
    private val strengthLabel = JBLabel()

    private var generated: String = ""

    init {
        title = "Generate Password"
        setOKButtonText("Use This Password")
        ButtonGroup().apply {
            add(passwordMode)
            add(passphraseMode)
        }
        init()

        val regenerate = { _: java.awt.event.ActionEvent -> regenerate() }
        listOf(passwordMode, passphraseMode, uppercase, lowercase, digits, special, avoidAmbiguous,
            capitalize, includeNumber).forEach { it.addActionListener(regenerate) }
        listOf(lengthSpinner, minDigits, minSpecial, words).forEach {
            it.addChangeListener { regenerate() }
        }
        separator.addActionListener(regenerate)

        regenerate()
    }

    fun password(): String = generated

    override fun createCenterPanel(): JComponent = panel {
        row {
            cell(result).columns(40)
            cell(
                JButton(AllIcons.Actions.Refresh).apply {
                    toolTipText = "Generate another"
                    addActionListener { regenerate() }
                },
            )
        }
        row {
            cell(strengthLabel)
        }
        buttonsGroup {
            row {
                cell(passwordMode)
                cell(passphraseMode)
            }
        }
        group("Password") {
            row("Length:") { cell(lengthSpinner) }
            row("Include:") {
                cell(uppercase)
                cell(lowercase)
                cell(digits)
                cell(special)
            }
            row("Minimum digits:") { cell(minDigits) }
            row("Minimum special:") { cell(minSpecial) }
            row { cell(avoidAmbiguous).comment("Leaves out l, I, 1, O, 0 and similar look-alikes") }
        }
        group("Passphrase") {
            row("Words:") { cell(words) }
            row("Separator:") { cell(separator) }
            row {
                cell(capitalize)
                cell(includeNumber)
            }
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = result

    override fun doOKAction() {
        persistOptions()
        super.doOKAction()
    }

    private fun regenerate() {
        generated = try {
            if (passphraseMode.isSelected) {
                PasswordGenerator.generatePassphrase(passphraseOptions())
            } else {
                PasswordGenerator.generate(passwordOptions())
            }
        } catch (e: Exception) {
            ""
        }
        result.text = generated
        strengthLabel.text = if (generated.isEmpty()) {
            "Enable at least one character set"
        } else {
            "Strength: " + PasswordGenerator.strength(generated).title
        }
        isOKActionEnabled = generated.isNotEmpty()
    }

    private fun passwordOptions() = PasswordOptions(
        length = lengthSpinner.number,
        useUppercase = uppercase.isSelected,
        useLowercase = lowercase.isSelected,
        useDigits = digits.isSelected,
        useSpecial = special.isSelected,
        minDigits = minDigits.number,
        minSpecial = minSpecial.number,
        avoidAmbiguous = avoidAmbiguous.isSelected,
    )

    private fun passphraseOptions() = PassphraseOptions(
        words = words.number,
        separator = separator.text,
        capitalize = capitalize.isSelected,
        includeNumber = includeNumber.isSelected,
    )

    private fun persistOptions() {
        settings.generatorLength = lengthSpinner.number
        settings.generatorUppercase = uppercase.isSelected
        settings.generatorLowercase = lowercase.isSelected
        settings.generatorDigits = digits.isSelected
        settings.generatorSpecial = special.isSelected
        settings.generatorMinDigits = minDigits.number
        settings.generatorMinSpecial = minSpecial.number
        settings.generatorAvoidAmbiguous = avoidAmbiguous.isSelected
        settings.generatorPassphraseWords = words.number
        settings.generatorPassphraseSeparator = separator.text
        settings.generatorPassphraseCapitalize = capitalize.isSelected
        settings.generatorPassphraseNumber = includeNumber.isSelected
    }
}
