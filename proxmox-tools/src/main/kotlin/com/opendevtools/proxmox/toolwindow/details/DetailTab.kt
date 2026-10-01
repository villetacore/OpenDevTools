package com.opendevtools.proxmox.toolwindow.details

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.project.Project
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.opendevtools.core.async.Background
import com.opendevtools.core.async.Outcome
import com.opendevtools.proxmox.model.ClusterSnapshot
import com.opendevtools.proxmox.model.ProxmoxGuest
import com.opendevtools.proxmox.model.ProxmoxNode
import com.opendevtools.proxmox.model.ProxmoxStorage
import java.awt.BorderLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/** What the details pane is currently describing. */
sealed class DetailTarget {

    data object None : DetailTarget()

    class Datacenter(val snapshot: ClusterSnapshot) : DetailTarget()

    class Node(val node: ProxmoxNode) : DetailTarget()

    class Guest(val guest: ProxmoxGuest) : DetailTarget()

    class Storage(val storage: ProxmoxStorage) : DetailTarget()

    /** Identity of the selected object, used to skip a reload when nothing actually changed. */
    val identity: String
        get() = when (this) {
            is None -> "none"
            is Datacenter -> "datacenter"
            is Node -> node.key
            is Guest -> guest.key
            is Storage -> storage.key
        }
}

/**
 * One tab of the details pane. A tab declares which targets it can describe and loads its own
 * data in the background, so switching selection never blocks the EDT and a slow tab (statistics,
 * syslog) does not hold up the rest of the pane.
 */
abstract class DetailTab(
    protected val project: Project,
    val title: String,
    val icon: Icon? = null,
) : JPanel(BorderLayout()) {

    /** The target this tab currently shows, so a repeated selection does not refetch. */
    private var loadedIdentity: String? = null

    abstract fun supports(target: DetailTarget): Boolean

    protected abstract fun loadData(target: DetailTarget)

    fun show(target: DetailTarget, force: Boolean = false) {
        if (!force && loadedIdentity == target.identity) return
        loadedIdentity = target.identity
        loadData(target)
    }

    /** Drops the memo of what was loaded, so the next [show] refetches. */
    fun resetLoadState() {
        loadedIdentity = null
    }

    /** Runs [compute] off the EDT and hands the result to [onSuccess]; failures land in [onError]. */
    protected fun <T> load(compute: () -> T, onError: (String) -> Unit, onSuccess: (T) -> Unit) {
        Background.run(compute) { outcome ->
            when (outcome) {
                is Outcome.Success -> onSuccess(outcome.value)
                is Outcome.Failure -> onError(outcome.message)
            }
        }
    }

    protected fun toolbar(groupId: String): JComponent? {
        val group = ActionManager.getInstance().getAction(groupId) as? ActionGroup ?: return null
        val toolbar: ActionToolbar =
            ActionManager.getInstance().createActionToolbar("ProxmoxDetails.$groupId", group, true)
        toolbar.targetComponent = this
        return toolbar.component
    }
}

/** A [DetailTab] whose body is a single table; the shared plumbing lives here. */
abstract class TableDetailTab<T>(
    project: Project,
    title: String,
    icon: Icon? = null,
    private val emptyText: String = "Nothing to show",
) : DetailTab(project, title, icon) {

    // Captured before the table exists: inside the JBTable builder `emptyText` would resolve to
    // the table's own StatusText rather than to this constructor parameter.
    private val emptyTextValue: String = emptyText

    protected val tableModel: ListTableModel<T> = ListTableModel(*columns())

    protected val table: JBTable = JBTable(tableModel).apply {
        setShowGrid(false)
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        tableHeader.reorderingAllowed = false
        rowHeight = JBUI.scale(20)
        this.emptyText.text = emptyTextValue
    }

    /** Exposed so a subclass can place the table inside a splitter instead of filling the tab. */
    protected val tableScrollPane: JComponent = ScrollPaneFactory.createScrollPane(table, true)

    init {
        add(tableScrollPane, BorderLayout.CENTER)
    }

    /**
     * Called from the base constructor, so an implementation must not read subclass state -
     * it may only describe the columns. Every tab here builds them with [column].
     */
    protected abstract fun columns(): Array<ColumnInfo<T, *>>

    protected fun setRows(rows: List<T>, empty: String = emptyText) {
        table.emptyText.text = empty
        tableModel.items = rows.toMutableList()
    }

    protected fun showError(message: String) {
        table.emptyText.text = message
        tableModel.items = mutableListOf()
    }

    protected fun showLoading() {
        table.emptyText.text = "Loading..."
    }

    fun selectedRow(): T? = table.selectedRow
        .takeIf { it >= 0 }
        ?.let { tableModel.getItem(table.convertRowIndexToModel(it)) }

    /** A read-only text column; the overwhelmingly common case in these tables. */
    protected fun column(name: String, width: Int = -1, value: (T) -> String?): ColumnInfo<T, String> =
        object : ColumnInfo<T, String>(name) {
            override fun valueOf(item: T): String = value(item).orEmpty()

            override fun getPreferredStringValue(): String? =
                if (width > 0) "x".repeat(width) else null
        }
}
