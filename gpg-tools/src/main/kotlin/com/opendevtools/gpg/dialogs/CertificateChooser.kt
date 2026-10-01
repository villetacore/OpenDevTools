package com.opendevtools.gpg.dialogs

import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import com.opendevtools.gpg.GpgIcons
import com.opendevtools.gpg.model.GpgKey
import java.awt.BorderLayout
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * A filterable list of certificates, used wherever a dialog has to ask "which keys?".
 *
 * The filter matters more than it looks: a working keyring has hundreds of entries, and picking
 * recipients out of an unsorted list of those is the part of Kleopatra people complain about.
 */
class CertificateChooser(
    private val all: List<GpgKey>,
    multiSelect: Boolean,
    private val emptyMessage: String = "No suitable certificates in this keyring",
) : JPanel(BorderLayout()) {

    private val model = CollectionListModel(all)

    private val list = JBList(model).apply {
        selectionMode = if (multiSelect) {
            ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        } else {
            ListSelectionModel.SINGLE_SELECTION
        }
        cellRenderer = Renderer()
        emptyText.text = emptyMessage
        visibleRowCount = VISIBLE_ROWS
    }

    private val search = SearchTextField().apply {
        textEditor.emptyText.text = "Filter"
        border = JBUI.Borders.emptyBottom(4)
    }

    init {
        add(search, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(list, true), BorderLayout.CENTER)
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) = applyFilter()
        })
    }

    val selection: List<GpgKey> get() = list.selectedValuesList

    val selected: GpgKey? get() = list.selectedValue

    fun select(fingerprint: String?) {
        val match = fingerprint?.let { wanted -> all.firstOrNull { it.fingerprint == wanted } } ?: return
        list.setSelectedValue(match, true)
    }

    fun selectAll(fingerprints: Collection<String>) {
        val indices = model.items.withIndex()
            .filter { it.value.fingerprint in fingerprints }
            .map { it.index }
        if (indices.isNotEmpty()) list.selectedIndices = indices.toIntArray()
    }

    private fun applyFilter() {
        // The current selection is kept across a filter change, so typing does not silently drop
        // a recipient the user already picked.
        val kept = list.selectedValuesList.toSet()
        val query = search.text
        model.replaceAll(all.filter { it.matches(query) })
        val indices = model.items.withIndex().filter { it.value in kept }.map { it.index }
        list.selectedIndices = indices.toIntArray()
    }

    private class Renderer : ColoredListCellRenderer<GpgKey>() {
        override fun customizeCellRenderer(
            list: JList<out GpgKey>,
            value: GpgKey?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            val key = value ?: return
            icon = if (key.hasSecret) GpgIcons.SecretCertificate else GpgIcons.Certificate
            append(key.displayName)
            append("  " + key.shortKeyId, SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append("  " + key.algorithmTitle, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }

    private companion object {
        const val VISIBLE_ROWS = 12
    }
}
