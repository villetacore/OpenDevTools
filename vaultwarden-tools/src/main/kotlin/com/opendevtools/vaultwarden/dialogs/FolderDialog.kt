package com.opendevtools.vaultwarden.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.opendevtools.core.ui.listRenderer
import com.opendevtools.vaultwarden.model.VaultFolder
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent

/** Creates or renames a folder. */
class FolderDialog(
    project: Project?,
    private val existingNames: Set<String>,
    currentName: String = "",
) : DialogWrapper(project) {

    private val nameField = JBTextField(currentName, 30)
    private val originalName = currentName

    init {
        title = if (currentName.isBlank()) "New Folder" else "Rename Folder"
        setOKButtonText(if (currentName.isBlank()) "Create" else "Rename")
        init()
    }

    fun folderName(): String = nameField.text.trim()

    override fun createCenterPanel(): JComponent = panel {
        row("Name:") {
            cell(nameField).comment("Nested folders are written with a slash, for example Work/Servers")
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun doValidate(): ValidationInfo? {
        val name = folderName()
        return when {
            name.isBlank() -> ValidationInfo("Enter a folder name", nameField)
            name != originalName && name in existingNames ->
                ValidationInfo("A folder with this name already exists", nameField)

            else -> null
        }
    }
}

/** Picks a target folder, used by "Move to Folder" for one or several items. */
class MoveToFolderDialog(
    project: Project?,
    folders: List<VaultFolder>,
    private val itemCount: Int,
) : DialogWrapper(project) {

    private val folderCombo = JComboBox<VaultFolder>().apply {
        renderer = listRenderer("") { it.name }
        model = DefaultComboBoxModel(folders.toTypedArray())
    }

    init {
        title = "Move to Folder"
        setOKButtonText("Move")
        init()
    }

    fun targetFolderId(): String? = (folderCombo.selectedItem as? VaultFolder)?.id

    override fun createCenterPanel(): JComponent = panel {
        row {
            label(if (itemCount == 1) "Move 1 item to:" else "Move $itemCount items to:")
        }
        row("Folder:") { cell(folderCombo) }
    }
}
