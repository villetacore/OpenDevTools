package com.opendevtools.proxmox.toolwindow.details

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTabbedPane
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.event.ChangeListener

/**
 * The right-hand pane. Every tab exists for the lifetime of the tool window, but only the ones
 * that can describe the current selection are attached, and only the visible one loads data -
 * a guest with ten tabs would otherwise fire ten requests on every click in the tree.
 */
class ProxmoxDetailsPanel(project: Project, parentDisposable: Disposable) : JPanel(BorderLayout()) {

    val summaryTab = SummaryTab(project)
    val configTab = ConfigTab(project)
    val snapshotsTab = SnapshotsTab(project)
    val backupsTab = BackupsTab(project)
    val networkTab = NetworkTab(project)
    val firewallTab = FirewallTab(project)
    val statisticsTab = StatisticsTab(project)
    val tasksTab = TasksTab(project)
    val contentTab = StorageContentTab(project)
    val servicesTab = NodeServicesTab(project)
    val disksTab = NodeDisksTab(project)
    val updatesTab = NodeUpdatesTab(project)
    val replicationTab = ReplicationTab(project)
    val syslogTab = SyslogTab(project)
    val haTab = HaTab(project)
    val backupJobsTab = BackupJobsTab(project)
    val accessTab = AccessTab(project)
    val clusterFirewallTab = ClusterFirewallTab(project)

    private val allTabs: List<DetailTab> = listOf(
        summaryTab,
        configTab,
        snapshotsTab,
        backupsTab,
        networkTab,
        firewallTab,
        statisticsTab,
        tasksTab,
        contentTab,
        servicesTab,
        disksTab,
        updatesTab,
        replicationTab,
        syslogTab,
        haTab,
        backupJobsTab,
        clusterFirewallTab,
        accessTab,
    )

    private val tabbedPane = JBTabbedPane()
    private var target: DetailTarget = DetailTarget.None
    private var updatingTabs = false

    private val selectionListener = ChangeListener { loadVisibleTab() }

    init {
        add(tabbedPane, BorderLayout.CENTER)
        tabbedPane.addChangeListener(selectionListener)
        com.intellij.openapi.util.Disposer.register(parentDisposable) {
            tabbedPane.removeChangeListener(selectionListener)
        }
        show(DetailTarget.None)
    }

    fun show(target: DetailTarget) {
        this.target = target
        rebuildTabs(target)
        loadVisibleTab()
    }

    /** Forces the visible tab to re-fetch, used after an action changed something server-side. */
    fun reloadVisible() {
        allTabs.forEach { it.resetLoadState() }
        loadVisibleTab()
    }

    fun currentTarget(): DetailTarget = target

    fun selectTab(tab: DetailTab) {
        val index = tabbedPane.indexOfComponent(tab)
        if (index >= 0) tabbedPane.selectedIndex = index
    }

    private fun rebuildTabs(target: DetailTarget) {
        val wanted = allTabs.filter { it.supports(target) }
        val current = (0 until tabbedPane.tabCount).map { tabbedPane.getComponentAt(it) }
        if (current == wanted) return

        val previousTitle = tabbedPane.selectedIndex
            .takeIf { it >= 0 }
            ?.let { tabbedPane.getTitleAt(it) }

        updatingTabs = true
        try {
            tabbedPane.removeAll()
            wanted.forEach { tabbedPane.addTab(it.title, it.icon, it) }
            // Keep the user on the same tab across selections where that tab still exists.
            val restored = wanted.indexOfFirst { it.title == previousTitle }
            tabbedPane.selectedIndex = if (restored >= 0) restored else 0
        } finally {
            updatingTabs = false
        }
    }

    private fun loadVisibleTab() {
        if (updatingTabs) return
        val index = tabbedPane.selectedIndex
        if (index < 0) return
        (tabbedPane.getComponentAt(index) as? DetailTab)?.show(target)
    }
}
