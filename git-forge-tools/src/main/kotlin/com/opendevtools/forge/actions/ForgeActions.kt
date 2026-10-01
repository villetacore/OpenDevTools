package com.opendevtools.forge.actions

import com.intellij.ide.BrowserUtil
import com.intellij.ide.CopyPasteManagerEx
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.opendevtools.core.async.Background
import com.opendevtools.core.async.Outcome
import com.opendevtools.forge.dialogs.CommentDialog
import com.opendevtools.forge.dialogs.MergeDialog
import com.opendevtools.forge.dialogs.NewIssueDialog
import com.opendevtools.forge.dialogs.NewPullRequestDialog
import com.opendevtools.forge.model.ForgeRepository
import com.opendevtools.forge.model.IssueState
import com.opendevtools.forge.service.ForgeService
import com.opendevtools.forge.service.GitRemoteDetector
import com.opendevtools.forge.settings.ForgeSettings
import com.opendevtools.forge.toolwindow.ForgeDataKeys
import com.opendevtools.forge.toolwindow.ForgePanel
import com.opendevtools.forge.util.ForgeNotifier
import java.awt.datatransfer.StringSelection
import javax.swing.Icon

/** Base for every forge action: resolves the panel and reports failures the same way. */
abstract class ForgeAction(
    text: String? = null,
    description: String? = null,
    icon: Icon? = null,
) : AnAction(text, description, icon) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    protected fun panel(e: AnActionEvent): ForgePanel? = e.getData(ForgeDataKeys.PANEL)

    protected fun repository(e: AnActionEvent): ForgeRepository? = e.getData(ForgeDataKeys.REPOSITORY)

    protected fun confirm(project: Project?, title: String, message: String, okText: String): Boolean {
        if (!ForgeSettings.getInstance().confirmDestructiveActions) return true
        return Messages.showYesNoDialog(
            project,
            message,
            title,
            okText,
            Messages.getCancelButton(),
            Messages.getQuestionIcon(),
        ) == Messages.YES
    }

    /** Runs a write and reloads the selected repository's tabs afterwards. */
    protected fun runWrite(
        e: AnActionEvent,
        title: String,
        successMessage: String,
        work: () -> Unit,
    ) {
        val project = e.project
        val panel = panel(e)
        Background.runVisible(
            project = project,
            title = title,
            cancellable = false,
            compute = { work() },
            onDone = { outcome ->
                when (outcome) {
                    is Outcome.Failure -> ForgeNotifier.failure(project, title, outcome.error)
                    is Outcome.Success -> {
                        ForgeNotifier.info(project, successMessage)
                        panel?.reloadSelected()
                    }
                }
            },
        )
    }
}

// --- Navigation ---------------------------------------------------------------------------------

class RefreshForgesAction : ForgeAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = panel(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        ForgeService.getInstance().invalidate()
        panel(e)?.refresh()
    }
}

class OpenForgeSettingsAction : ForgeAction() {

    override fun actionPerformed(e: AnActionEvent) {
        panel(e)?.openSettings()
    }
}

/** Jumps to the repository the open project's git remote points at. */
class GoToProjectRepositoryAction : ForgeAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = panel(e)?.projectRepository() != null
        e.presentation.description = panel(e)?.projectRepository()
            ?.let { "Select ${it.fullName}, the repository of this project" }
            ?: "The project's git remote does not match any configured account"
    }

    override fun actionPerformed(e: AnActionEvent) {
        if (panel(e)?.selectProjectRepository() != true) {
            ForgeNotifier.warn(e.project, "No configured account holds this project's remote")
        }
    }
}

/** Opens whatever is selected in the browser: the repository, or a row of one of the tabs. */
class OpenInBrowserAction : ForgeAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = targetUrl(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        targetUrl(e)?.let { BrowserUtil.browse(it) }
    }

    /** The most specific selection wins: a row of a tab before the repository itself. */
    private fun targetUrl(e: AnActionEvent): String? = listOfNotNull(
        e.getData(ForgeDataKeys.ISSUE)?.webUrl,
        e.getData(ForgeDataKeys.PULL_REQUEST)?.webUrl,
        e.getData(ForgeDataKeys.PIPELINE)?.webUrl,
        e.getData(ForgeDataKeys.RELEASE)?.webUrl,
        repository(e)?.webUrl,
    ).firstOrNull { it.isNotBlank() }
}

class CopyCloneUrlAction : ForgeAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = repository(e)?.cloneUrl != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val url = repository(e)?.cloneUrl ?: return
        CopyPasteManagerEx.getInstance().setContents(StringSelection(url))
        ForgeNotifier.info(e.project, "Copied $url")
    }
}

class CopySshUrlAction : ForgeAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = repository(e)?.sshUrl != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val url = repository(e)?.sshUrl ?: return
        CopyPasteManagerEx.getInstance().setContents(StringSelection(url))
        ForgeNotifier.info(e.project, "Copied $url")
    }
}

/** Reports what the project's `.git/config` says, which is what the matching is based on. */
class ShowProjectRemotesAction : ForgeAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val remotes = GitRemoteDetector.remotes(project)
        val message = if (remotes.isEmpty()) {
            "No git remotes found in this project."
        } else {
            remotes.joinToString("\n") { "${it.name}: ${it.url}" }
        }
        Messages.showInfoMessage(project, message, "Project Git Remotes")
    }
}

// --- Issues ---------------------------------------------------------------------------------------

class NewIssueAction : ForgeAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = repository(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val repository = repository(e) ?: return
        val dialog = NewIssueDialog(e.project, repository)
        if (!dialog.showAndGet()) return
        val issue = dialog.issue()

        runWrite(e, "Creating the issue", "Issue created in ${repository.fullName}") {
            ForgeService.getInstance().clientFor(repository).createIssue(repository, issue)
        }
    }
}

class CommentOnIssueAction : ForgeAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = repository(e) != null &&
            (e.getData(ForgeDataKeys.ISSUE) != null || e.getData(ForgeDataKeys.PULL_REQUEST) != null)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val repository = repository(e) ?: return
        val number = e.getData(ForgeDataKeys.ISSUE)?.number
            ?: e.getData(ForgeDataKeys.PULL_REQUEST)?.number
            ?: return

        val dialog = CommentDialog(e.project, "#$number")
        if (!dialog.showAndGet()) return
        val body = dialog.body()

        runWrite(e, "Posting the comment", "Comment posted on #$number") {
            ForgeService.getInstance().clientFor(repository).comment(repository, number, body)
        }
    }
}

class ToggleIssueStateAction : ForgeAction() {

    override fun update(e: AnActionEvent) {
        val issue = e.getData(ForgeDataKeys.ISSUE)
        e.presentation.isEnabled = issue != null && repository(e) != null
        e.presentation.text = if (issue?.state == IssueState.OPEN) "Close Issue" else "Reopen Issue"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val repository = repository(e) ?: return
        val issue = e.getData(ForgeDataKeys.ISSUE) ?: return
        val reopening = issue.state != IssueState.OPEN

        if (!reopening &&
            !confirm(e.project, "Close Issue", "Close #${issue.number} \"${issue.title}\"?", "Close")
        ) {
            return
        }

        runWrite(
            e,
            if (reopening) "Reopening the issue" else "Closing the issue",
            "#${issue.number} " + if (reopening) "reopened" else "closed",
        ) {
            ForgeService.getInstance().clientFor(repository)
                .setIssueState(repository, issue.number, reopening)
        }
    }
}

// --- Pull requests -----------------------------------------------------------------------------------

class NewPullRequestAction : ForgeAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = repository(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val repository = repository(e) ?: return
        val branches = panel(e)?.currentDetails()?.branches.orEmpty()
        if (branches.isEmpty()) {
            ForgeNotifier.warn(e.project, "No branches loaded yet for ${repository.fullName}")
            return
        }
        // The branch the project is on is the one a person almost always means as the source.
        val suggested = branches.firstOrNull { !it.isDefault }?.name

        val dialog = NewPullRequestDialog(e.project, repository, branches, suggested)
        if (!dialog.showAndGet()) return
        val request = dialog.request()

        runWrite(e, "Creating the pull request", "Pull request created in ${repository.fullName}") {
            ForgeService.getInstance().clientFor(repository).createPullRequest(repository, request)
        }
    }
}

class MergePullRequestAction : ForgeAction() {

    override fun update(e: AnActionEvent) {
        val pullRequest = e.getData(ForgeDataKeys.PULL_REQUEST)
        e.presentation.isEnabled = repository(e) != null &&
            pullRequest != null &&
            pullRequest.state == IssueState.OPEN &&
            !pullRequest.merged
    }

    override fun actionPerformed(e: AnActionEvent) {
        val repository = repository(e) ?: return
        val pullRequest = e.getData(ForgeDataKeys.PULL_REQUEST) ?: return
        val client = runCatching { ForgeService.getInstance().clientFor(repository) }.getOrNull() ?: return

        if (pullRequest.mergeable == false) {
            ForgeNotifier.warn(
                e.project,
                "#${pullRequest.number} reports conflicts; resolve them before merging",
            )
            return
        }

        val dialog = MergeDialog(
            e.project,
            client.supportedMergeMethods(),
            "Merge #${pullRequest.number} \"${pullRequest.title}\" " +
                "into ${pullRequest.targetBranch ?: "the target branch"}?",
        )
        if (!dialog.showAndGet()) return
        val method = dialog.method()

        runWrite(e, "Merging #${pullRequest.number}", "#${pullRequest.number} merged") {
            client.mergePullRequest(repository, pullRequest.number, method)
        }
    }
}

// --- View toggles --------------------------------------------------------------------------------------

class ToggleShowForksAction : ToggleAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun isSelected(e: AnActionEvent): Boolean = ForgeSettings.getInstance().showForks

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        ForgeSettings.getInstance().showForks = state
        ForgeSettings.fireChanged()
    }
}

class ToggleShowPrivateAction : ToggleAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun isSelected(e: AnActionEvent): Boolean = ForgeSettings.getInstance().showPrivate

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        ForgeSettings.getInstance().showPrivate = state
        ForgeSettings.fireChanged()
    }
}

/** One entry of the state filter; the choice is a persistent setting. */
class SelectIssueStateAction(private val state: IssueState) :
    ToggleAction(state.title, "Show ${state.title.lowercase()} issues and pull requests", null) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun isSelected(e: AnActionEvent): Boolean =
        ForgeSettings.getInstance().defaultIssueState == state

    override fun setSelected(e: AnActionEvent, selected: Boolean) {
        if (!selected) return
        ForgeSettings.getInstance().defaultIssueState = state
        ForgeSettings.fireChanged()
    }
}

class IssueStateActionGroup : DefaultActionGroup(
    "Show",
    IssueState.entries.map { SelectIssueStateAction(it) },
) {
    init {
        isPopup = true
        templatePresentation.icon = com.intellij.icons.AllIcons.Actions.Show
    }
}

/** Shows the description and the discussion of the selected issue or pull request. */
class ShowCommentsAction : ForgeAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = repository(e) != null && subject(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val repository = repository(e) ?: return
        val (number, title, body) = subject(e) ?: return
        val project = e.project

        Background.runVisible(
            project = project,
            title = "Loading the discussion on #$number",
            cancellable = false,
            compute = { ForgeService.getInstance().clientFor(repository).comments(repository, number) },
            onDone = { outcome ->
                when (outcome) {
                    is Outcome.Failure ->
                        ForgeNotifier.failure(project, "Could not load the comments", outcome.error)

                    is Outcome.Success ->
                        com.opendevtools.forge.dialogs.CommentsDialog(
                            project,
                            "#$number  $title",
                            body,
                            outcome.value,
                        ).show()
                }
            },
        )
    }

    /** The issue or the pull request, whichever the selection points at. */
    private fun subject(e: AnActionEvent): Triple<Int, String, String?>? {
        e.getData(ForgeDataKeys.ISSUE)?.let { return Triple(it.number, it.title, it.body) }
        e.getData(ForgeDataKeys.PULL_REQUEST)?.let { return Triple(it.number, it.title, it.body) }
        return null
    }
}
