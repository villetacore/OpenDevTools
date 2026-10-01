package com.opendevtools.forge.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.opendevtools.core.ui.listRenderer
import com.opendevtools.forge.model.ForgeBranch
import com.opendevtools.forge.model.ForgeRepository
import com.opendevtools.forge.model.MergeMethod
import com.opendevtools.forge.model.NewIssue
import com.opendevtools.forge.model.NewPullRequest
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent

/** Creates an issue on the selected repository. */
class NewIssueDialog(project: Project?, private val repository: ForgeRepository) :
    DialogWrapper(project) {

    private val titleField = JBTextField(48)
    private val bodyArea = JBTextArea(10, 48)
    private val labelsField = JBTextField(32)

    init {
        title = "New Issue in ${repository.fullName}"
        setOKButtonText("Create")
        init()
    }

    fun issue(): NewIssue = NewIssue(
        title = titleField.text.trim(),
        body = bodyArea.text.takeIf { it.isNotBlank() },
        labels = labelsField.text.split(',').map { it.trim() }.filter { it.isNotEmpty() },
    )

    override fun createCenterPanel(): JComponent = panel {
        row("Repository:") { label(repository.fullName) }
        row("Title:") { cell(titleField) }
        row("Description:") { cell(bodyArea).columns(48) }
        row("Labels:") {
            cell(labelsField).comment("Comma separated; ignored by forges that reject unknown labels")
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = titleField

    override fun doValidate(): ValidationInfo? =
        if (titleField.text.isBlank()) ValidationInfo("Enter a title", titleField) else null
}

/** Opens a pull or merge request between two branches of the selected repository. */
class NewPullRequestDialog(
    project: Project?,
    private val repository: ForgeRepository,
    branches: List<ForgeBranch>,
    suggestedSource: String?,
) : DialogWrapper(project) {

    private val titleField = JBTextField(48)
    private val bodyArea = JBTextArea(8, 48)
    private val draftCheckBox = JBCheckBox("Open as a draft", false)

    private val sourceCombo = JComboBox(DefaultComboBoxModel(branches.map { it.name }.toTypedArray()))
        .apply { suggestedSource?.let { selectedItem = it } }

    private val targetCombo = JComboBox(DefaultComboBoxModel(branches.map { it.name }.toTypedArray()))
        .apply {
            selectedItem = repository.defaultBranch
                ?: branches.firstOrNull { it.isDefault }?.name
                ?: branches.firstOrNull()?.name
        }

    init {
        title = "New Pull Request in ${repository.fullName}"
        setOKButtonText("Create")
        init()
    }

    fun request(): NewPullRequest = NewPullRequest(
        title = titleField.text.trim(),
        body = bodyArea.text.takeIf { it.isNotBlank() },
        sourceBranch = sourceCombo.selectedItem as String,
        targetBranch = targetCombo.selectedItem as String,
        draft = draftCheckBox.isSelected,
    )

    override fun createCenterPanel(): JComponent = panel {
        row("Repository:") { label(repository.fullName) }
        row("From:") { cell(sourceCombo) }
        row("Into:") { cell(targetCombo) }
        row("Title:") { cell(titleField) }
        row("Description:") { cell(bodyArea).columns(48) }
        row { cell(draftCheckBox).comment("GitLab marks a draft with a title prefix instead") }
    }

    override fun getPreferredFocusedComponent(): JComponent = titleField

    override fun doValidate(): ValidationInfo? = when {
        titleField.text.isBlank() -> ValidationInfo("Enter a title", titleField)
        sourceCombo.selectedItem == null -> ValidationInfo("Choose the source branch", sourceCombo)
        sourceCombo.selectedItem == targetCombo.selectedItem ->
            ValidationInfo("The source and the target branch must differ", targetCombo)

        else -> null
    }
}

/** Picks how a pull request is merged, offering only what that forge supports. */
class MergeDialog(project: Project?, methods: List<MergeMethod>, private val summary: String) :
    DialogWrapper(project) {

    private val methodCombo = JComboBox(DefaultComboBoxModel(methods.toTypedArray())).apply {
        renderer = listRenderer("") { it.title }
    }

    init {
        title = "Merge"
        setOKButtonText("Merge")
        init()
    }

    fun method(): MergeMethod = methodCombo.selectedItem as MergeMethod

    override fun createCenterPanel(): JComponent = panel {
        row { label(summary) }
        row("Method:") { cell(methodCombo) }
    }
}

/** Adds a comment to an issue or a pull request. */
class CommentDialog(project: Project?, private val subject: String) : DialogWrapper(project) {

    private val bodyArea = JBTextArea(10, 48)

    init {
        title = "Comment on $subject"
        setOKButtonText("Comment")
        init()
    }

    fun body(): String = bodyArea.text.trim()

    override fun createCenterPanel(): JComponent = panel {
        row { cell(bodyArea).columns(48) }
    }

    override fun getPreferredFocusedComponent(): JComponent = bodyArea

    override fun doValidate(): ValidationInfo? =
        if (bodyArea.text.isBlank()) ValidationInfo("Write something first", bodyArea) else null
}
