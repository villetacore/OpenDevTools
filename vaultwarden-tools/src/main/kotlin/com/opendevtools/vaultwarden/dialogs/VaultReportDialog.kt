package com.opendevtools.vaultwarden.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.opendevtools.vaultwarden.model.VaultReport
import com.opendevtools.vaultwarden.model.VaultReportFinding
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.ListSelectionModel

/**
 * The vault health reports. Everything shown here is computed locally from already-decrypted
 * items - no password or hash of one leaves the machine, which is why the breach check the web
 * vault offers is deliberately absent.
 */
class VaultReportDialog(project: Project?, private val report: VaultReport) : DialogWrapper(project) {

    private val tabs = JBTabbedPane()

    /** The item the user picked, so the caller can select it in the tree. */
    private var chosen: VaultReportFinding? = null

    init {
        title = "Vault Report"
        setModal(true)
        init()
    }

    fun selectedFinding(): VaultReportFinding? = chosen

    override fun createCenterPanel(): JComponent {
        tabs.addTab(
            badge("Weak passwords", report.weakPasswords.size),
            buildTable(report.weakPasswords, "Strength"),
        )
        tabs.addTab(
            badge("Reused passwords", report.reusedPasswords.size),
            buildTable(report.reusedPasswords, "Reuse"),
        )
        tabs.addTab(
            badge("Unsecured websites", report.unsecuredUris.size),
            buildTable(report.unsecuredUris, "URI"),
        )
        tabs.addTab(
            badge("Missing 2FA", report.missingTotp.size),
            buildTable(report.missingTotp, "Detail"),
        )
        tabs.preferredSize = Dimension(JBUI.scale(720), JBUI.scale(420))
        return tabs
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    private fun badge(title: String, count: Int): String = "$title ($count)"

    private fun buildTable(findings: List<VaultReportFinding>, detailColumn: String): JComponent {
        val model = ListTableModel<VaultReportFinding>(
            column<VaultReportFinding>("Item") { it.item.name },
            column<VaultReportFinding>("Username") { it.item.username.orEmpty() },
            column<VaultReportFinding>(detailColumn) { it.detail },
        ).apply { items = findings.toMutableList() }

        val table = JBTable(model).apply {
            setShowGrid(false)
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            tableHeader.reorderingAllowed = false
            rowHeight = JBUI.scale(20)
            emptyText.text = "Nothing to report - good"
            selectionModel.addListSelectionListener {
                chosen = selectedRow.takeIf { it >= 0 }?.let { model.getItem(convertRowIndexToModel(it)) }
            }
        }
        return ScrollPaneFactory.createScrollPane(table, true)
    }

    private fun <T> column(name: String, value: (T) -> String): ColumnInfo<T, String> =
        object : ColumnInfo<T, String>(name) {
            override fun valueOf(item: T): String = value(item)
        }
}
