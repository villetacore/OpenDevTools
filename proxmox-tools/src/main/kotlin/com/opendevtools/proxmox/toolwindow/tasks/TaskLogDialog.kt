package com.opendevtools.proxmox.toolwindow.tasks

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.opendevtools.core.async.Background
import com.opendevtools.core.async.Outcome
import com.opendevtools.proxmox.model.ProxmoxTask
import com.opendevtools.proxmox.service.ProxmoxService
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The log of one Proxmox task, tailed while the task is still running. This is the piece that
 * turns "the migration failed" into something a person can act on, so it is reachable from the
 * task list, from a failed-task notification and from the context menu.
 */
class TaskLogDialog(
    private val project: Project,
    private val node: String,
    private val upid: String,
    private val label: String,
) : DialogWrapper(project, false) {

    private val editor: EditorEx = createEditor()
    private val statusLabel = JBLabel().apply { border = JBUI.Borders.empty(4, 8) }
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)

    private var lastLineCount = 0

    init {
        title = "Task Log - $label"
        setModal(false)
        init()
        Disposer.register(disposable) { EditorFactory.getInstance().releaseEditor(editor) }
        reload()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout()).apply {
        preferredSize = Dimension(JBUI.scale(900), JBUI.scale(520))
        add(statusLabel, BorderLayout.NORTH)
        add(editor.component, BorderLayout.CENTER)
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    private fun createEditor(): EditorEx {
        val factory = EditorFactory.getInstance()
        val document = factory.createDocument("")
        return (factory.createViewer(document, project) as EditorEx).apply {
            settings.isLineNumbersShown = true
            settings.isLineMarkerAreaShown = false
            settings.isFoldingOutlineShown = false
            settings.isRightMarginShown = false
            settings.additionalLinesCount = 0
            settings.additionalColumnsCount = 0
            setCaretEnabled(true)
        }
    }

    private fun reload() {
        Background.run(
            {
                val service = ProxmoxService.getInstance()
                service.taskLog(node, upid) to runCatching { service.taskStatus(node, upid) }.getOrNull()
            },
        ) { outcome ->
            if (isDisposed) return@run
            when (outcome) {
                is Outcome.Failure -> statusLabel.text = "Cannot read the log: ${outcome.message}"
                is Outcome.Success -> {
                    val (lines, status) = outcome.value
                    render(lines.joinToString("\n") { it.text })
                    scheduleNextPollIfRunning(status)
                }
            }
        }
    }

    private fun render(text: String) {
        val document = editor.document
        com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction {
            document.setText(text)
        }
        // Follow the tail only while new lines keep arriving, so a user scrolling back is left alone.
        val lineCount = document.lineCount
        if (lineCount > lastLineCount) {
            editor.caretModel.moveToOffset(document.textLength)
            editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.MAKE_VISIBLE)
        }
        lastLineCount = lineCount
    }

    private fun scheduleNextPollIfRunning(status: ProxmoxTask?) {
        if (status == null) {
            statusLabel.text = "Status unknown"
            return
        }
        if (status.isRunning) {
            statusLabel.text = "Running for ${status.durationSeconds}s - following the log"
            alarm.addRequest({ reload() }, POLL_INTERVAL_MS)
        } else {
            statusLabel.text = "Finished: ${status.exitStatus ?: "unknown"}"
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 2_000L
    }
}
