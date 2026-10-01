package com.opendevtools.proxmox.toolwindow.details

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.JBIntSpinner
import com.intellij.util.ui.JBUI
import com.opendevtools.proxmox.model.ProxmoxNode
import com.opendevtools.proxmox.service.ProxmoxService
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

/** The node's syslog tail, which is where a failed service or a storage problem shows up first. */
class SyslogTab(project: Project) : DetailTab(project, "Syslog", AllIcons.Debugger.Console) {

    private val area = JBTextArea().apply {
        isEditable = false
        lineWrap = false
        font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, JBUI.Fonts.label().size)
    }
    private val limit = JBIntSpinner(DEFAULT_LIMIT, 50, 5_000, 50)
    private val status = JBLabel().apply { border = JBUI.Borders.empty(2, 8) }

    private var currentNode: ProxmoxNode? = null

    init {
        add(
            JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2))).apply {
                add(JBLabel("Lines:"))
                add(limit)
                add(JButton("Reload").apply { addActionListener { reload() } })
                add(status)
            },
            BorderLayout.NORTH,
        )
        add(ScrollPaneFactory.createScrollPane(area, true), BorderLayout.CENTER)
    }

    override fun supports(target: DetailTarget): Boolean = target is DetailTarget.Node

    override fun loadData(target: DetailTarget) {
        currentNode = (target as? DetailTarget.Node)?.node
        reload()
    }

    private fun reload() {
        val node = currentNode ?: run {
            area.text = ""
            status.text = ""
            return
        }
        val lines = limit.number
        status.text = "Loading..."
        load(
            compute = { ProxmoxService.getInstance().nodeSyslog(node.name, lines) },
            onError = {
                status.text = it
                area.text = ""
            },
            onSuccess = {
                area.text = it.joinToString("\n")
                area.caretPosition = area.document.length
                status.text = "${it.size} lines"
            },
        )
    }

    private companion object {
        const val DEFAULT_LIMIT = 500
    }
}
