package com.opendevtools.gpg.toolwindow

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
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.opendevtools.core.async.Background
import com.opendevtools.core.async.Outcome
import com.opendevtools.core.ui.TreeState
import com.opendevtools.gpg.model.GpgKey
import com.opendevtools.gpg.model.GpgKeyring
import com.opendevtools.gpg.model.GpgSubkey
import com.opendevtools.gpg.model.GpgUserId
import com.opendevtools.gpg.service.GpgService
import com.opendevtools.gpg.settings.GpgConfigurable
import com.opendevtools.gpg.settings.GpgSettings
import com.opendevtools.gpg.settings.GpgSettingsListener
import com.opendevtools.gpg.toolwindow.details.KeyDetailsPanel
import com.opendevtools.gpg.util.GpgNotifier
import java.awt.BorderLayout
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * The GPG tool window: the keyring on one side, the selected certificate on the other.
 *
 * The tree is rebuilt from scratch on every refresh, which is simple and fast enough for a keyring
 * of any realistic size; what survives that is the expansion and the selection, restored through
 * the shared [TreeState] helper so a refresh never moves the ground under the user.
 */
class GpgPanel(private val project: Project, parentDisposable: Disposable) :
    SimpleToolWindowPanel(true, true), UiDataProvider, Disposable {

    private val root = DefaultMutableTreeNode(GpgTreeItem.Message("Certificates"))
    private val treeModel = DefaultTreeModel(root)

    private val tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = GpgTreeCellRenderer()
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
    }

    private val searchField = SearchTextField().apply {
        textEditor.emptyText.text = "Filter by name, address or fingerprint"
        border = JBUI.Borders.empty(2)
    }

    private val details = KeyDetailsPanel()
    private val loading = AtomicBoolean(false)

    private var keyring: GpgKeyring = GpgKeyring.EMPTY

    @Volatile
    private var disposed = false

    init {
        Disposer.register(parentDisposable, this)

        val group = ActionManager.getInstance().getAction("GpgTools.Toolbar") as ActionGroup
        val toolbar = ActionManager.getInstance().createActionToolbar("GpgToolWindow", group, true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)

        val treeArea = JPanel(BorderLayout()).apply {
            add(searchField, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER)
        }
        setContent(
            OnePixelSplitter(false, 0.4f).apply {
                firstComponent = treeArea
                secondComponent = details
            },
        )

        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = rebuildTree()
        })
        tree.addTreeSelectionListener { onSelectionChanged() }
        PopupHandler.installPopupMenu(tree, "GpgTools.CertificateMenu", "GpgCertificatePopup")

        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(GpgSettings.TOPIC, GpgSettingsListener { refresh() })

        tree.emptyText.text = "Loading the keyring..."
        refresh()
    }

    override fun dispose() {
        disposed = true
    }

    override fun uiDataSnapshot(sink: DataSink) {
        sink.set(GpgDataKeys.PANEL, this)
        selectedCertificate()?.let { sink.set(GpgDataKeys.CERTIFICATE, it) }
        details.selectedUserId()?.let { sink.set(GpgDataKeys.USER_ID, it) }
        sink.set(GpgDataKeys.SUBKEYS, details.selectedSubkeys())
    }

    // --- Selection --------------------------------------------------------------------------------

    fun selectedCertificate(): GpgKey? =
        ((tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject
            as? GpgTreeItem.Certificate)?.certificate

    fun selectedUserId(): GpgUserId? = details.selectedUserId()

    fun selectedSubkeys(): List<GpgSubkey> = details.selectedSubkeys()

    fun currentKeyring(): GpgKeyring = keyring

    fun openSettings() =
        ShowSettingsUtil.getInstance().showSettingsDialog(project, GpgConfigurable::class.java)

    // --- Loading ----------------------------------------------------------------------------------

    /** Reloads the keyring. Concurrent refreshes are dropped rather than queued. */
    fun refresh(selectFingerprint: String? = null) {
        if (!loading.compareAndSet(false, true)) return
        val settings = GpgSettings.getInstance()
        if (!settings.isConfigured) {
            loading.set(false)
            keyring = GpgKeyring.EMPTY
            rebuildTree()
            tree.emptyText.text = "GnuPG was not found - set the path in Settings | Tools | GPG"
            return
        }

        Background.run(
            compute = { GpgService.getInstance().loadKeyring() },
            onDone = { outcome ->
                loading.set(false)
                if (disposed) return@run
                when (outcome) {
                    is Outcome.Failure -> {
                        keyring = GpgKeyring.EMPTY
                        rebuildTree()
                        tree.emptyText.text = outcome.message
                        GpgNotifier.failure(project, "Reading the keyring failed", outcome.error)
                    }

                    is Outcome.Success -> {
                        keyring = outcome.value
                        rebuildTree()
                        tree.emptyText.text = "This keyring holds no certificates"
                        selectFingerprint?.let { TreeState.selectByKey(tree, "key:$it") }
                    }
                }
            },
        )
    }

    /**
     * Redraws the tree from the keyring already in hand.
     *
     * A filter toggle changes what is shown, not what is known, so it must not spend a pair of gpg
     * processes re-reading a keyring that has not changed.
     */
    fun refilter() = rebuildTree()

    /** Re-reads only the selected certificate, which is what a single edit needs. */
    fun reloadSelected() {
        val fingerprint = selectedCertificate()?.fingerprint ?: return refresh()
        Background.run(
            compute = { GpgService.getInstance().reloadKey(fingerprint) },
            onDone = { outcome ->
                if (disposed) return@run
                when (outcome) {
                    is Outcome.Failure -> refresh()
                    is Outcome.Success -> {
                        val updated = outcome.value
                        // A key that no longer reads back was deleted or revoked away; a full
                        // refresh is the only honest answer, since the tree row is now a lie.
                        if (updated == null) {
                            refresh()
                        } else {
                            keyring = GpgKeyring(
                                keyring.keys.map { if (it.fingerprint == fingerprint) updated else it },
                            )
                            rebuildTree()
                            TreeState.selectByKey(tree, "key:$fingerprint")
                        }
                    }
                }
            },
        )
    }

    // --- Tree -------------------------------------------------------------------------------------

    private fun visibleKeys(): List<GpgKey> {
        val settings = GpgSettings.getInstance()
        val query = searchField.text
        return keyring.keys
            .filter { !settings.showOnlySecretKeys || it.hasSecret }
            .filter {
                settings.showInvalidCertificates || !(it.isRevoked || it.isExpired || it.disabled)
            }
            .filter { it.matches(query) }
    }

    private fun rebuildTree() {
        val snapshot = TreeState.capture(tree)
        root.removeAllChildren()

        val visible = visibleKeys()
        val (own, others) = visible.partition { it.hasSecret }

        if (visible.isEmpty()) {
            val message = when {
                keyring.keys.isEmpty() -> "No certificates in this keyring"
                else -> "Nothing matches the current filter"
            }
            root.add(DefaultMutableTreeNode(GpgTreeItem.Message(message)))
        } else {
            addGroup("My Certificates", own)
            addGroup("Other Certificates", others)
        }

        treeModel.reload()
        // Both groups open by default; the previous expansion and selection then win over that.
        TreeState.expandLevels(tree, 1)
        TreeState.restore(tree, snapshot)
        onSelectionChanged()
    }

    private fun addGroup(title: String, keys: List<GpgKey>) {
        // An empty group is a heading with nothing under it, which reads as a bug.
        if (keys.isEmpty()) return
        val node = DefaultMutableTreeNode(GpgTreeItem.Group(title, keys.size))
        keys.forEach { node.add(DefaultMutableTreeNode(GpgTreeItem.Certificate(it))) }
        root.add(node)
    }

    private fun onSelectionChanged() {
        val selected = selectedCertificate()
        if (selected == null) details.clear() else details.show(selected)
    }
}
