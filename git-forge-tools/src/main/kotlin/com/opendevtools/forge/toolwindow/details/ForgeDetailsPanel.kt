package com.opendevtools.forge.toolwindow.details

import com.intellij.openapi.project.Project
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.opendevtools.core.format.Formats
import com.opendevtools.core.ui.Property
import com.opendevtools.core.ui.PropertiesTable
import com.opendevtools.forge.ForgeIcons
import com.opendevtools.forge.model.ForgeBranch
import com.opendevtools.forge.model.ForgeIssue
import com.opendevtools.forge.model.ForgePipeline
import com.opendevtools.forge.model.ForgePullRequest
import com.opendevtools.forge.model.ForgeRelease
import com.opendevtools.forge.model.ForgeRepository
import com.opendevtools.forge.model.RepositoryDetails
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * The right-hand pane: one tab per kind of thing a repository holds.
 *
 * Everything is loaded in one go by the service and handed here, so switching tabs is instant and
 * a repository costs one round of requests rather than one per tab.
 */
class ForgeDetailsPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val summary = PropertiesTable("Select a repository")

    private val issues = table<ForgeIssue>(
        "No issues",
        column("#", 6) { it.number.toString() },
        column("Title", 44) { it.title },
        column("State", 8) { it.state.title },
        column("Author", 14) { it.author },
        column("Labels", 20) { it.labels.joinToString(", ") { label -> label.name } },
        column("Comments", 9) { it.comments.toString() },
        column("Updated", 18) { Formats.isoTimestamp(it.updatedAt) },
    )

    private val pullRequests = table<ForgePullRequest>(
        "No pull requests",
        column("#", 6) { it.number.toString() },
        column("Title", 40) { it.title },
        column("Status", 10) { it.statusLabel },
        column("Author", 14) { it.author },
        column("From", 18) { it.sourceBranch },
        column("Into", 18) { it.targetBranch },
        column("Updated", 18) { Formats.isoTimestamp(it.updatedAt) },
    )

    private val pipelines = table<ForgePipeline>(
        "No CI runs, or CI is not enabled on this server",
        column("Status", 10) { it.status.title },
        column("Run", 36) { it.name },
        column("Branch", 20) { it.branch },
        column("Commit", 10) { it.commitSha },
        column("Started", 18) { Formats.isoTimestamp(it.startedAt) },
    )

    private val releases = table<ForgeRelease>(
        "No releases",
        column("Tag", 16) { it.tag },
        column("Name", 32) { it.name },
        column("Kind", 12) {
            when {
                it.draft -> "draft"
                it.prerelease -> "prerelease"
                else -> "release"
            }
        },
        column("Published", 18) { Formats.isoTimestamp(it.publishedAt) },
    )

    private val branches = table<ForgeBranch>(
        "No branches",
        column("Branch", 32) { it.name },
        column("Default", 8) { if (it.isDefault) "yes" else "" },
        column("Protected", 10) { if (it.protected) "yes" else "" },
        column("Commit", 10) { it.commitSha },
    )

    private val tabs = JBTabbedPane()

    private var details: RepositoryDetails? = null

    init {
        tabs.addTab("Summary", ForgeIcons.Repository, summary)
        tabs.addTab("Issues", ForgeIcons.Issue, scroll(issues.table))
        tabs.addTab("Pull requests", ForgeIcons.PullRequest, scroll(pullRequests.table))
        tabs.addTab("CI", ForgeIcons.Pipeline, scroll(pipelines.table))
        tabs.addTab("Releases", ForgeIcons.Release, scroll(releases.table))
        tabs.addTab("Branches", ForgeIcons.Branch, scroll(branches.table))
        add(tabs, BorderLayout.CENTER)

        PopupHandler.installPopupMenu(issues.table, "GitForgeTools.IssueMenu", "ForgeIssuePopup")
        PopupHandler.installPopupMenu(
            pullRequests.table,
            "GitForgeTools.PullRequestMenu",
            "ForgePullRequestPopup",
        )
        PopupHandler.installPopupMenu(pipelines.table, "GitForgeTools.OpenMenu", "ForgePipelinePopup")
        PopupHandler.installPopupMenu(releases.table, "GitForgeTools.OpenMenu", "ForgeReleasePopup")
    }

    fun showNothing() {
        details = null
        summary.clear()
        listOf(issues, pullRequests, pipelines, releases, branches).forEach { it.clear() }
        retitle(0, 0, 0, 0, 0)
    }

    fun showLoading(repository: ForgeRepository) {
        summary.setProperties(summaryRows(repository))
        listOf(issues, pullRequests, pipelines, releases, branches).forEach { it.loading() }
    }

    fun show(loaded: RepositoryDetails) {
        details = loaded
        summary.setProperties(summaryRows(loaded.repository))
        issues.setRows(loaded.issues)
        pullRequests.setRows(loaded.pullRequests)
        pipelines.setRows(loaded.pipelines)
        releases.setRows(loaded.releases)
        branches.setRows(loaded.branches)
        retitle(
            loaded.issues.size,
            loaded.pullRequests.size,
            loaded.pipelines.size,
            loaded.releases.size,
            loaded.branches.size,
        )
    }

    fun currentDetails(): RepositoryDetails? = details

    fun selectedIssue(): ForgeIssue? = issues.selected()

    fun selectedPullRequest(): ForgePullRequest? = pullRequests.selected()

    fun selectedPipeline(): ForgePipeline? = pipelines.selected()

    fun selectedRelease(): ForgeRelease? = releases.selected()

    fun selectedBranch(): ForgeBranch? = branches.selected()

    private fun retitle(issues: Int, pulls: Int, runs: Int, releases: Int, branches: Int) {
        tabs.setTitleAt(1, badge("Issues", issues))
        tabs.setTitleAt(2, badge("Pull requests", pulls))
        tabs.setTitleAt(3, badge("CI", runs))
        tabs.setTitleAt(4, badge("Releases", releases))
        tabs.setTitleAt(5, badge("Branches", branches))
    }

    private fun badge(title: String, count: Int): String = if (count == 0) title else "$title ($count)"

    private fun summaryRows(repository: ForgeRepository): List<Property> = buildList {
        add(Property("Repository", repository.fullName))
        repository.description?.takeIf { it.isNotBlank() }?.let { add(Property("Description", it)) }
        add(Property("Visibility", if (repository.isPrivate) "private" else "public"))
        if (repository.isFork) add(Property("Fork", "yes"))
        repository.defaultBranch?.let { add(Property("Default branch", it)) }
        add(Property("Open issues", repository.openIssues.toString()))
        add(Property("Stars", repository.stars.toString()))
        repository.updatedAt?.let { add(Property("Updated", Formats.isoTimestamp(it))) }
        add(Property("Web", repository.webUrl))
        repository.cloneUrl?.let { add(Property("Clone (HTTPS)", it)) }
        repository.sshUrl?.let { add(Property("Clone (SSH)", it)) }
    }

    private fun scroll(component: JComponent): JComponent =
        ScrollPaneFactory.createScrollPane(component, true)

    private fun <T> column(name: String, width: Int, value: (T) -> String?): ColumnInfo<T, String> =
        object : ColumnInfo<T, String>(name) {
            override fun valueOf(item: T): String = value(item).orEmpty()

            override fun getPreferredStringValue(): String = "x".repeat(width)
        }

    private fun <T> table(empty: String, vararg columns: ColumnInfo<T, *>): TableSection<T> =
        TableSection(empty, columns.toList())

    /** A table plus the small amount of state every one of these sections needs. */
    private class TableSection<T>(
        private val emptyText: String,
        columns: List<ColumnInfo<T, *>>,
    ) {
        private val model = ListTableModel<T>(*columns.toTypedArray())

        // Captured first: inside the JBTable builder `emptyText` resolves to the table's own
        // StatusText rather than to this constructor parameter.
        private val placeholder = emptyText

        val table: JBTable = JBTable(model).apply {
            setShowGrid(false)
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            tableHeader.reorderingAllowed = false
            rowHeight = JBUI.scale(20)
            this.emptyText.text = placeholder
        }

        fun setRows(rows: List<T>) {
            table.emptyText.text = emptyText
            model.items = rows.toMutableList()
        }

        fun clear() {
            table.emptyText.text = emptyText
            model.items = mutableListOf()
        }

        fun loading() {
            table.emptyText.text = "Loading..."
            model.items = mutableListOf()
        }

        fun selected(): T? = table.selectedRow
            .takeIf { it >= 0 }
            ?.let { model.getItem(table.convertRowIndexToModel(it)) }
    }
}
