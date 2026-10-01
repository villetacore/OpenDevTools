package com.opendevtools.forge.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.opendevtools.core.async.Background
import com.opendevtools.core.async.Outcome
import com.opendevtools.core.ui.TreeState
import com.opendevtools.forge.model.ForgeRepository
import com.opendevtools.forge.model.RepositoryDetails
import com.opendevtools.forge.service.ForgeService
import com.opendevtools.forge.service.ForgeSnapshot
import com.opendevtools.forge.service.GitRemoteDetector
import com.opendevtools.forge.settings.ForgeConfigurable
import com.opendevtools.forge.settings.ForgeSettings
import com.opendevtools.forge.settings.ForgeSettingsListener
import com.opendevtools.forge.toolwindow.details.ForgeDetailsPanel
import com.opendevtools.forge.util.ForgeNotifier
import java.awt.BorderLayout
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * The git forge tool window: configured accounts and their repositories on one side, the selected
 * repository's issues, pull requests, CI, releases and branches on the other.
 */
class ForgePanel(private val project: Project, parentDisposable: Disposable) :
    SimpleToolWindowPanel(true, true), UiDataProvider, Disposable {

    private val root = DefaultMutableTreeNode(ForgeTreeItem.MessageItem("Git forges"))
    private val treeModel = DefaultTreeModel(root)

    private val tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = ForgeTreeCellRenderer()
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
    }

    private val searchField = SearchTextField().apply {
        textEditor.emptyText.text = "Filter repositories"
        border = JBUI.Borders.empty(2)
    }

    private val details = ForgeDetailsPanel(project)
    private val loading = AtomicBoolean(false)

    private var snapshot: ForgeSnapshot = ForgeSnapshot.EMPTY

    /** The repository the open project's git remote points at, if any of the accounts has it. */
    private var projectRepository: ForgeRepository? = null

    @Volatile
    private var disposed = false

    init {
        Disposer.register(parentDisposable, this)

        val group = ActionManager.getInstance().getAction("GitForgeTools.Toolbar") as ActionGroup
        val toolbar = ActionManager.getInstance().createActionToolbar("GitForgeToolWindow", group, true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)

        val treeArea = JPanel(BorderLayout()).apply {
            add(searchField, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER)
        }
        setContent(
            OnePixelSplitter(false, 0.34f).apply {
                firstComponent = treeArea
                secondComponent = details
            },
        )

        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = rebuildTree()
        })
        tree.addTreeSelectionListener { onSelectionChanged() }
        PopupHandler.installPopupMenu(tree, "GitForgeTools.RepositoryMenu", "ForgeRepositoryPopup")

        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(
                ForgeSettings.TOPIC,
                ForgeSettingsListener {
                    ForgeService.getInstance().invalidate()
                    refresh()
                },
            )

        tree.emptyText.text = "Loading..."
        refresh()
    }

    override fun dispose() {
        disposed = true
    }

    override fun uiDataSnapshot(sink: DataSink) {
        sink.set(ForgeDataKeys.PANEL, this)
        selectedRepository()?.let { sink.set(ForgeDataKeys.REPOSITORY, it) }
        details.selectedIssue()?.let { sink.set(ForgeDataKeys.ISSUE, it) }
        details.selectedPullRequest()?.let { sink.set(ForgeDataKeys.PULL_REQUEST, it) }
        details.selectedPipeline()?.let { sink.set(ForgeDataKeys.PIPELINE, it) }
        details.selectedRelease()?.let { sink.set(ForgeDataKeys.RELEASE, it) }
        details.selectedBranch()?.let { sink.set(ForgeDataKeys.BRANCH, it) }
    }

    // --- Selection ---------------------------------------------------------------------------------

    fun selectedRepository(): ForgeRepository? = selectedItem<ForgeTreeItem.RepositoryItem>()?.repository

    fun currentDetails(): RepositoryDetails? = details.currentDetails()

    fun projectRepository(): ForgeRepository? = projectRepository

    private inline fun <reified T : ForgeTreeItem> selectedItem(): T? =
        (tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? T

    // --- Refresh ------------------------------------------------------------------------------------

    fun refresh() {
        if (disposed || !loading.compareAndSet(false, true)) return
        tree.setPaintBusy(true)

        Background.run(
            {
                val loaded = ForgeService.getInstance().loadAll()
                val remotes = GitRemoteDetector.remotes(project)
                loaded to GitRemoteDetector.matchingRepository(remotes, loaded.allRepositories)
            },
        ) { outcome ->
            loading.set(false)
            if (disposed) return@run
            tree.setPaintBusy(false)
            when (outcome) {
                is Outcome.Failure -> showError(outcome.message)
                is Outcome.Success -> {
                    snapshot = outcome.value.first
                    projectRepository = outcome.value.second
                        .takeIf { ForgeSettings.getInstance().matchProjectRemote }
                    rebuildTree()
                }
            }
        }
    }

    private fun rebuildTree() {
        val accounts = ForgeSettings.getInstance().accountList()
        if (accounts.isEmpty()) {
            root.removeAllChildren()
            treeModel.reload()
            details.showNothing()
            tree.emptyText
                .setText("No git accounts configured")
                .appendLine("Add an account", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                    openSettings()
                }
            return
        }

        val state = TreeState.capture(tree)
        ForgeTreeBuilder.build(root, accounts, snapshot, searchField.text, projectRepository?.key)
        treeModel.reload()

        tree.emptyText.text = when {
            snapshot.allRepositories.isEmpty() -> "No repositories visible to these accounts"
            searchField.text.isNotBlank() -> "Nothing matches the filter"
            else -> ""
        }

        if (state.isEmpty) {
            TreeState.expandLevels(tree, 1)
            // Land on the project's own repository when there is one; that is what the user wants.
            projectRepository?.let { TreeState.selectByKey(tree, it.key) }
        } else {
            TreeState.restore(tree, state)
        }

        snapshot.failures.forEach { (accountId, message) ->
            val account = ForgeSettings.getInstance().account(accountId)
            ForgeNotifier.warn(project, "${account?.label ?: accountId}: $message")
        }
    }

    private fun showError(message: String) {
        root.removeAllChildren()
        treeModel.reload()
        details.showNothing()
        tree.emptyText
            .setText(message)
            .appendLine("Open settings", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { openSettings() }
    }

    private fun onSelectionChanged() {
        val repository = selectedRepository()
        if (repository == null) {
            details.showNothing()
            return
        }
        details.showLoading(repository)
        val state = ForgeSettings.getInstance().defaultIssueState

        Background.run({ ForgeService.getInstance().loadDetails(repository, state) }) { outcome ->
            if (disposed) return@run
            // The user may have moved on while this was in flight.
            if (selectedRepository()?.key != repository.key) return@run
            when (outcome) {
                is Outcome.Failure ->
                    ForgeNotifier.failure(project, "Could not load ${repository.fullName}", outcome.error)

                is Outcome.Success -> details.show(outcome.value)
            }
        }
    }

    /** Reloads the selected repository's tabs without reloading the whole account list. */
    fun reloadSelected() {
        onSelectionChanged()
    }

    fun openSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, ForgeConfigurable::class.java)
    }

    /** Selects the repository matching the open project's remote, if it was found. */
    fun selectProjectRepository(): Boolean {
        val repository = projectRepository ?: return false
        return TreeState.selectByKey(tree, repository.key)
    }
}
