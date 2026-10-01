package com.opendevtools.core.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.CopyPasteManagerEx
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

/** One line of a property sheet. [secret] rows are masked until the user asks to see them. */
data class Property(val name: String, val value: String, val secret: Boolean = false) {

    companion object {
        fun of(name: String, value: String?): Property? =
            value?.takeIf { it.isNotBlank() }?.let { Property(name, it) }
    }
}

/**
 * Read-only key/value sheet used by every details pane in both plugins. Rows can be copied from
 * the context menu, which is what makes the pane useful for values too long to read at a glance.
 */
class PropertiesTable(emptyText: String = "Nothing selected") : JPanel(BorderLayout()) {

    private val model = PropertiesModel()

    private val table = JBTable(model).apply {
        setShowGrid(false)
        rowSelectionAllowed = true
        setSelectionMode(ListSelectionModel.SINGLE_INTERVAL_SELECTION)
        tableHeader.reorderingAllowed = false
        this.emptyText.text = emptyText
    }

    private var showSecrets = false

    init {
        border = JBUI.Borders.empty()
        add(ScrollPaneFactory.createScrollPane(table, true), BorderLayout.CENTER)
        table.columnModel.getColumn(0).preferredWidth = JBUI.scale(160)
        table.columnModel.getColumn(1).preferredWidth = JBUI.scale(420)
        installContextMenu()
    }

    fun setProperties(rows: List<Property>) = model.setRows(rows)

    fun clear() = model.setRows(emptyList())

    fun setShowSecrets(value: Boolean) {
        showSecrets = value
        model.setMasking(!value)
    }

    fun emptyText(text: String) {
        table.emptyText.text = text
    }

    /** The value as stored, never the mask - this is what the copy actions hand out. */
    private fun selectedRawValue(): String? =
        table.selectedRow.takeIf { it >= 0 }?.let { model.rawValueAt(table.convertRowIndexToModel(it)) }

    private fun installContextMenu() {
        val group = DefaultActionGroup(
            object : AnAction("Copy Value", "Copy the value of the selected row", AllIcons.Actions.Copy) {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = selectedRawValue() != null
                }

                override fun actionPerformed(e: AnActionEvent) {
                    selectedRawValue()?.let { CopyPasteManagerEx.getInstance().setContents(StringSelection(it)) }
                }
            },
            object : AnAction("Copy All", "Copy every row as text", AllIcons.Actions.Copy) {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

                override fun actionPerformed(e: AnActionEvent) {
                    CopyPasteManagerEx.getInstance().setContents(StringSelection(model.asText()))
                }
            },
        )
        PopupHandler.installPopupMenu(table, group, "OpenDevToolsPropertiesTable")
    }

    private class PropertiesModel : AbstractTableModel() {

        private var rows: List<Property> = emptyList()
        private var masking = true

        fun setRows(value: List<Property>) {
            rows = value
            fireTableDataChanged()
        }

        fun setMasking(value: Boolean) {
            if (masking == value) return
            masking = value
            fireTableDataChanged()
        }

        fun rawValueAt(row: Int): String? = rows.getOrNull(row)?.value

        fun asText(): String = rows.joinToString(System.lineSeparator()) { it.name + "\t" + display(it) }

        override fun getRowCount(): Int = rows.size

        override fun getColumnCount(): Int = 2

        override fun getColumnName(column: Int): String = if (column == 0) "Property" else "Value"

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val row = rows[rowIndex]
            return if (columnIndex == 0) row.name else display(row)
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false

        private fun display(row: Property): String = when {
            row.secret && masking -> "•".repeat(row.value.length.coerceIn(1, 12))
            else -> row.value.replace('\n', ' ')
        }
    }
}
