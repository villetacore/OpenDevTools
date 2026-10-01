package com.opendevtools.vaultwarden.toolwindow

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
import com.intellij.ui.DoubleClickListener
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
import com.opendevtools.vaultwarden.actions.VaultItemEditing
import com.opendevtools.vaultwarden.model.VaultFolder
import com.opendevtools.vaultwarden.model.VaultItem
import com.opendevtools.vaultwarden.model.VaultSend
import com.opendevtools.vaultwarden.model.VaultSnapshot
import com.opendevtools.vaultwarden.service.VaultwardenService
import com.opendevtools.vaultwarden.settings.VaultStateListener
import com.opendevtools.vaultwarden.settings.VaultwardenConfigurable
import com.opendevtools.vaultwarden.settings.VaultwardenSettings
import com.opendevtools.vaultwarden.toolwindow.details.VaultDetailsPanel
import com.opendevtools.vaultwarden.toolwindow.tree.VaultFilter
import com.opendevtools.vaultwarden.toolwindow.tree.VaultTreeBuilder
import com.opendevtools.vaultwarden.toolwindow.tree.VaultTreeCellRenderer
import com.opendevtools.vaultwarden.toolwindow.tree.VaultTreeItem
import com.opendevtools.vaultwarden.util.VaultwardenNotifier
import java.awt.BorderLayout
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * The Vaultwarden tool window: the vault tree on one side and the item details on the other.
 * The panel owns the refresh cycle and publishes the selection through [VaultDataKeys].
 */
class VaultwardenPanel(private val project: Project, parentDisposable: Disposable) :
    SimpleToolWindowPanel(true, true), UiDataProvider, Disposable {

    private val root = DefaultMutableTreeNode(VaultTreeItem.MessageNode("Vault"))
    private val treeModel = DefaultTreeModel(root)

    private val tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = VaultTreeCellRenderer()
        selectionModel.selectionMode = TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
    }

    private val searchField = SearchTextField().apply {
        textEditor.emptyText.text = "Search the vault"
        border = JBUI.Borders.empty(2)
    }

    private val details = VaultDetailsPanel()
    private val loading = AtomicBoolean(false)

    private var snapshot: VaultSnapshot? = null

    @Volatile
    private var disposed = false

    var showSecrets: Boolean = false
        set(value) {
            field = value
            details.setShowSecrets(value)
        }

    /** Toolbar toggle: show only the items marked as favorites. */
    var favoritesOnly: Boolean = false
        set(value) {
            field = value
            rebuildTree()
        }

    init {
        Disposer.register(parentDisposable, this)

        val group = ActionManager.getInstance().getAction("VaultwardenTools.Toolbar") as ActionGroup
        val toolbar = ActionManager.getInstance().createActionToolbar("VaultwardenToolWindow", group, true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)

        val treeArea = JPanel(BorderLayout()).apply {
            add(searchField, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER)
        }
        setContent(
            OnePixelSplitter(true, 0.5f).apply {
                firstComponent = treeArea
                secondComponent = details
            },
        )

        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = rebuildTree()
        })
        tree.addTreeSelectionListener { updateDetails() }
        PopupHandler.installPopupMenu(tree, "VaultwardenTools.ContextMenu", "VaultwardenToolWindowPopup")
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean = editSelectedItem()
        }.installOn(tree)

        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(VaultwardenSettings.TOPIC, VaultStateListener { refresh(reload = false) })

        refresh(reload = false)
    }

    override fun dispose() {
        disposed = true
    }

    override fun uiDataSnapshot(sink: DataSink) {
        sink.set(VaultDataKeys.PANEL, this)
        sink.set(VaultDataKeys.ITEMS, selectedItems())
        selectedFolder()?.let { sink.set(VaultDataKeys.FOLDER, it) }
        selectedSend()?.let { sink.set(VaultDataKeys.SEND, it) }
    }

    // --- Selection -------------------------------------------------------------------------------

    fun selectedItems(): List<VaultItem> = selectedNodes()
        .filterIsInstance<VaultTreeItem.ItemNode>()
        .map { it.item }

    fun selectedSend(): VaultSend? = selectedNodes()
        .filterIsInstance<VaultTreeItem.SendNode>()
        .map { it.send }
        .singleOrNull()

    /** The folder branch itself, or the folder the single selected item belongs to. */
    fun selectedFolder(): VaultFolder? {
        selectedNodes().filterIsInstance<VaultTreeItem.FolderNode>().singleOrNull()
            ?.let { return it.folder }
        val item = selectedItems().singleOrNull() ?: return null
        return snapshot?.folder(item.folderId)
    }

    fun currentSnapshot(): VaultSnapshot? = snapshot

    private fun selectedNodes(): List<VaultTreeItem> =
        tree.selectionPaths.orEmpty().mapNotNull {
            (it.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? VaultTreeItem
        }

    // --- Refresh ---------------------------------------------------------------------------------

    /** Reloads the vault; [reload] controls whether the server is asked for fresh data. */
    fun refresh(reload: Boolean = true) {
        if (disposed || !loading.compareAndSet(false, true)) return
        tree.setPaintBusy(true)
        val service = VaultwardenService.getInstance()

        Background.run(
            {
                val configured = service.apiConfig().isComplete
                when {
                    !configured -> LoadResult(configured = false, snapshot = null)
                    !service.isUnlocked -> LoadResult(configured = true, snapshot = null)
                    reload -> LoadResult(true, service.sync())
                    else -> LoadResult(true, service.cachedSnapshot() ?: service.sync())
                }
            },
        ) { outcome ->
            loading.set(false)
            if (disposed) return@run
            tree.setPaintBusy(false)
            when (outcome) {
                is Outcome.Failure -> render(configured = true, loaded = null, error = outcome.message)
                is Outcome.Success ->
                    render(outcome.value.configured, outcome.value.snapshot, error = null)
            }
        }
    }

    private class LoadResult(val configured: Boolean, val snapshot: VaultSnapshot?)

    private fun render(configured: Boolean, loaded: VaultSnapshot?, error: String?) {
        snapshot = loaded
        details.setSnapshot(loaded)
        if (loaded == null) {
            root.removeAllChildren()
            treeModel.reload()
            details.showNothing()
        }
        when {
            error != null -> emptyText(error, "Open Vaultwarden settings") { openSettings() }

            !configured ->
                emptyText("Vaultwarden connection is not configured", "Set the server and API key") {
                    openSettings()
                }

            !VaultwardenService.getInstance().isUnlocked ->
                emptyText("The vault is locked", "Unlock") { unlockInteractive() }

            loaded != null -> rebuildTree()
        }
    }

    private fun emptyText(message: String, linkText: String, action: () -> Unit) {
        tree.emptyText
            .setText(message)
            .appendLine(linkText, SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { action() }
    }

    private fun rebuildTree() {
        val current = snapshot ?: return
        val settings = VaultwardenSettings.getInstance()
        val state = TreeState.capture(tree)

        VaultTreeBuilder.build(
            root,
            current,
            settings.grouping,
            VaultFilter(query = searchField.text, favoritesOnly = favoritesOnly),
            settings.showFavoritesFirst,
        )
        treeModel.reload()

        tree.emptyText.text = when {
            current.items.isEmpty() && current.deletedItems.isEmpty() -> "The vault is empty"
            searchField.text.isNotBlank() -> "Nothing matches the search"
            else -> ""
        }

        if (state.isEmpty) TreeState.expandLevels(tree, 1) else TreeState.restore(tree, state)
        updateDetails()
    }

    private fun updateDetails() {
        val send = selectedSend()
        val item = selectedItems().singleOrNull()
        when {
            send != null -> details.showSend(send, VaultwardenSettings.getInstance().serverUrl)
            item != null -> details.show(item)
            else -> details.showNothing()
        }
    }

    // --- Commands ---------------------------------------------------------------------------------

    fun openSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, VaultwardenConfigurable::class.java)
    }

    /** Asks for the master password and derives the vault keys. */
    fun unlockInteractive() {
        if (disposed) return
        val settings = VaultwardenSettings.getInstance()
        val dialog = UnlockVaultDialog(project, settings.serverUrl)
        if (!dialog.showAndGet()) {
            dialog.wipe()
            return
        }
        val password = dialog.password()
        dialog.wipe()

        Background.runVisible(
            project = project,
            title = "Unlocking the Vaultwarden vault",
            cancellable = false,
            compute = {
                try {
                    VaultwardenService.getInstance().unlock(password)
                } finally {
                    password.fill(0.toChar())
                }
            },
            onDone = { outcome ->
                when (outcome) {
                    is Outcome.Success -> VaultwardenNotifier.info(project, "Vault unlocked")
                    is Outcome.Failure -> VaultwardenNotifier.error(project, outcome.message)
                }
                VaultwardenSettings.fireStateChanged()
            },
        )
    }

    /** Opens the editor for the item under the cursor; the tree's double click lands here. */
    fun editSelectedItem(): Boolean {
        val item = selectedItems().singleOrNull() ?: return false
        VaultItemEditing.edit(project, this, item)
        return true
    }

    /** Brings an item into view after it was created or edited. */
    fun selectItem(item: VaultItem) {
        TreeState.selectByKey(tree, item.key)
    }
}
