package com.opendevtools.forge.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.opendevtools.core.format.Formats
import com.opendevtools.forge.model.ForgeComment
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JComponent

/**
 * The discussion on an issue or a pull request, read-only.
 *
 * It is one text area rather than a list of components for the same reason the Matrix timeline is:
 * a long thread is something people read and copy from, and one area selects across all of it.
 */
class CommentsDialog(
    project: Project?,
    private val subject: String,
    private val body: String?,
    private val comments: List<ForgeComment>,
) : DialogWrapper(project) {

    private val area = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(6)
    }

    init {
        title = subject
        setModal(false)
        init()
        area.text = render()
        area.caretPosition = 0
    }

    override fun createCenterPanel(): JComponent =
        ScrollPaneFactory.createScrollPane(area, true).apply {
            preferredSize = Dimension(JBUI.scale(760), JBUI.scale(520))
        }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    private fun render(): String {
        val builder = StringBuilder()
        body?.takeIf { it.isNotBlank() }?.let {
            builder.append(it.trim()).append("\n\n").append("—".repeat(SEPARATOR)).append("\n\n")
        }
        if (comments.isEmpty()) {
            builder.append("No comments yet.")
            return builder.toString()
        }
        comments.forEach { comment ->
            builder.append(comment.author ?: "unknown")
                .append("  ")
                .append(Formats.isoTimestamp(comment.createdAt))
                .append('\n')
                .append(comment.body.trim())
                .append("\n\n")
        }
        return builder.toString().trimEnd()
    }

    private companion object {
        const val SEPARATOR = 60
    }
}
