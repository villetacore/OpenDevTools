package com.opendevtools.proxmox.toolwindow

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
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.opendevtools.core.async.Background
import com.opendevtools.core.async.Outcome
import com.opendevtools.core.ui.TreeState
import com.opendevtools.proxmox.model.ClusterSnapshot
import com.opendevtools.proxmox.model.ProxmoxGuest
import com.opendevtools.proxmox.model.ProxmoxNode
import com.opendevtools.proxmox.model.ProxmoxStorage
import com.opendevtools.proxmox.model.TreeGrouping
import com.opendevtools.proxmox.service.ProxmoxService
import com.opendevtools.proxmox.settings.ProxmoxConfigurable
import com.opendevtools.proxmox.settings.ProxmoxSettings
import com.opendevtools.proxmox.settings.ProxmoxSettingsListener
import com.opendevtools.proxmox.toolwindow.details.DetailTarget
import com.opendevtools.proxmox.toolwindow.details.ProxmoxDetailsPanel
import com.opendevtools.proxmox.toolwindow.tree.ProxmoxTreeBuilder
import com.opendevtools.proxmox.toolwindow.tree.ProxmoxTreeCellRenderer
import com.opendevtools.proxmox.toolwindow.tree.ProxmoxTreeItem
import com.opendevtools.proxmox.toolwindow.tree.TreeFilter
import java.awt.BorderLayout
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * The Proxmox tool window: a tree of the cluster on one side and the details tabs on the other.
 * The panel owns the refresh cycle and publishes the selection through [ProxmoxDataKeys], which
 * is the only channel the actions use.
 */
class ProxmoxPanel(private val project: Project, parentDisposable: Disposable) :
    SimpleToolWindowPanel(true, true), UiDataProvider, Disposable {

    private val root = DefaultMutableTreeNode(ProxmoxTreeItem.MessageItem("Proxmox"))
    private val treeModel = DefaultTreeModel(root)

    private val tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = ProxmoxTreeCellRenderer()
        selectionModel.selectionMode = TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
    }

    private val searchField = SearchTextField().apply {
        textEditor.emptyText.text = "Filter guests"
        border = JBUI.Borders.empty(2)
    }

    private val details = ProxmoxDetailsPanel(project, this)
    private val refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val loading = AtomicBoolean(false)

    private var snapshot: ClusterSnapshot? = null

    /** Toggled from the toolbar; shows only guests that are currently running. */
    var runningOnly: Boolean = false
        set(value) {
            field = value
            rebuildTree()
        }

    @Volatile
    private var disposed = false

    init {
        Disposer.register(parentDisposable, this)

        val toolbarGroup = ActionManager.getInstance().getAction("ProxmoxTools.Toolbar") as ActionGroup
        val toolbar = ActionManager.getInstance().createActionToolbar("ProxmoxToolWindow", toolbarGroup, true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)

        val treeArea = JPanel(BorderLayout()).apply {
            add(searchField, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER)
        }
        setContent(
            OnePixelSplitter(true, 0.45f).apply {
                firstComponent = treeArea
                secondComponent = details
            },
        )

        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = rebuildTree()
        })
        tree.addTreeSelectionListener { updateDetails() }
        PopupHandler.installPopupMenu(tree, "ProxmoxTools.ContextMenu", "ProxmoxToolWindowPopup")
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean = openConsoleForSelection()
        }.installOn(tree)

        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(
                ProxmoxSettings.TOPIC,
                ProxmoxSettingsListener {
                    ProxmoxService.getInstance().invalidate()
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
        sink.set(ProxmoxDataKeys.PANEL, this)
        sink.set(ProxmoxDataKeys.GUESTS, selectedGuests())
        sink.set(ProxmoxDataKeys.NODES, selectedNodes())
        sink.set(ProxmoxDataKeys.STORAGES, selectedStorages())
        details.snapshotsTab.selectedSnapshot()?.let { sink.set(ProxmoxDataKeys.SNAPSHOT, it) }
        (details.backupsTab.selectedBackup() ?: details.contentTab.selectedVolume())
            ?.let { sink.set(ProxmoxDataKeys.VOLUME, it) }
        details.tasksTab.selectedTask()?.let { sink.set(ProxmoxDataKeys.TASK, it) }
        details.firewallTab.selectedRule()?.let { sink.set(ProxmoxDataKeys.FIREWALL_RULE, it) }
        details.servicesTab.selectedService()?.let { sink.set(ProxmoxDataKeys.SERVICE, it) }
    }

    // --- Selection ---------------------------------------------------------------------------

    fun selectedGuests(): List<ProxmoxGuest> =
        selectedItems().filterIsInstance<ProxmoxTreeItem.GuestItem>().map { it.guest }

    fun selectedNodes(): List<ProxmoxNode> =
        selectedItems().filterIsInstance<ProxmoxTreeItem.NodeItem>().map { it.node }

    fun selectedStorages(): List<ProxmoxStorage> =
        selectedItems().filterIsInstance<ProxmoxTreeItem.StorageItem>().map { it.storage }

    fun currentSnapshot(): ClusterSnapshot? = snapshot

    private fun selectedItems(): List<ProxmoxTreeItem> =
        tree.selectionPaths.orEmpty().mapNotNull {
            (it.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? ProxmoxTreeItem
        }

    // --- Refresh -----------------------------------------------------------------------------

    fun refresh() {
        if (disposed || !loading.compareAndSet(false, true)) return
        tree.setPaintBusy(true)
        Background.run(
            {
                val configured = ProxmoxSettings.getInstance().toConfig().isComplete
                if (configured) ProxmoxService.getInstance().loadCluster() else null
            },
        ) { outcome ->
            loading.set(false)
            if (disposed) return@run
            tree.setPaintBusy(false)
            when (outcome) {
                is Outcome.Failure -> showError(outcome.message)
                is Outcome.Success -> {
                    val loaded = outcome.value
                    if (loaded == null) showNotConfigured() else populate(loaded)
                }
            }
            scheduleAutoRefresh()
        }
    }

    /** Reloads the tree and forces the visible details tab to re-fetch as well. */
    fun refreshAll() {
        details.reloadVisible()
        refresh()
    }

    private fun populate(loaded: ClusterSnapshot) {
        snapshot = loaded
        rebuildTree(firstLoad = root.childCount == 0)
    }

    private fun rebuildTree(firstLoad: Boolean = false) {
        val loaded = snapshot ?: return
        val settings = ProxmoxSettings.getInstance()
        val state = TreeState.capture(tree)

        ProxmoxTreeBuilder.build(
            root,
            loaded,
            settings.grouping,
            TreeFilter(
                query = searchField.text,
                showTemplates = settings.showTemplates,
                showStorages = settings.showStoragesInTree,
                runningOnly = runningOnly,
            ),
        )
        treeModel.reload()

        tree.emptyText.text = when {
            loaded.nodes.isEmpty() -> "No nodes reported by the server"
            searchField.text.isNotBlank() -> "Nothing matches the filter"
            else -> ""
        }

        if (firstLoad || state.isEmpty) {
            // Node and its guest groups expanded is the layout a person expects on first sight.
            TreeState.expandLevels(tree, if (settings.grouping == TreeGrouping.FLAT) 1 else 2)
        } else {
            TreeState.restore(tree, state)
        }
        updateDetails()
    }

    private fun updateDetails() {
        val single = selectedItems().singleOrNull()
        details.show(
            when (single) {
                is ProxmoxTreeItem.NodeItem -> DetailTarget.Node(single.node)
                is ProxmoxTreeItem.GuestItem -> DetailTarget.Guest(single.guest)
                is ProxmoxTreeItem.StorageItem -> DetailTarget.Storage(single.storage)
                else -> snapshot?.let { DetailTarget.Datacenter(it) } ?: DetailTarget.None
            },
        )
    }

    private fun showError(message: String) {
        snapshot = null
        root.removeAllChildren()
        treeModel.reload()
        details.show(DetailTarget.None)
        tree.emptyText
            .setText(message)
            .appendLine("Open Proxmox settings", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                openSettings()
            }
    }

    private fun showNotConfigured() {
        snapshot = null
        root.removeAllChildren()
        treeModel.reload()
        details.show(DetailTarget.None)
        tree.emptyText
            .setText("Proxmox connection is not configured")
            .appendLine("Configure server and credentials", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                openSettings()
            }
    }

    fun openSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, ProxmoxConfigurable::class.java)
    }

    private fun scheduleAutoRefresh() {
        refreshAlarm.cancelAllRequests()
        val seconds = ProxmoxSettings.getInstance().autoRefreshSeconds
        if (seconds <= 0 || disposed) return
        refreshAlarm.addRequest({ refresh() }, seconds * 1000L)
    }

    private fun openConsoleForSelection(): Boolean {
        val guests = selectedGuests()
        val nodes = selectedNodes()
        if (guests.isEmpty() && nodes.isEmpty()) return false
        ProxmoxConsoleLauncher.open(project, guests, nodes)
        return true
    }
}
