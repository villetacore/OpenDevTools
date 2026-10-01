package com.opendevtools.matrix.toolwindow

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.opendevtools.core.format.Formats
import com.opendevtools.matrix.model.MatrixMessage
import com.opendevtools.matrix.model.MatrixRoom
import com.opendevtools.matrix.model.MessageKind
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

/**
 * The message timeline of one room.
 *
 * Messages are rendered into a single monospaced text area rather than a list of components: a
 * busy room can hold hundreds of messages, and one text area scrolls and selects across the whole
 * conversation, which is what a person wants when copying a snippet out of a chat.
 */
class TimelinePanel(
    private val onLoadEarlier: () -> Unit,
) : JPanel(BorderLayout()) {

    private val header = JBLabel().apply {
        border = JBUI.Borders.empty(4, 8)
        font = font.deriveFont(Font.BOLD)
    }

    private val loadEarlierButton = JButton("Load earlier messages").apply {
        isFocusable = false
        addActionListener { onLoadEarlier() }
    }

    private val area = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = EditorColorsManager.getInstance().globalScheme.getFont(null)
            ?: JBUI.Fonts.create(Font.MONOSPACED, JBUI.Fonts.label().size)
        border = JBUI.Borders.empty(4, 8)
    }

    private val scrollPane: JScrollPane = ScrollPaneFactory.createScrollPane(area, true)

    private var currentRoomId: String? = null

    init {
        val top = JPanel(BorderLayout()).apply {
            add(header, BorderLayout.CENTER)
            add(loadEarlierButton, BorderLayout.EAST)
        }
        add(top, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        showNothing()
    }

    fun showNothing() {
        currentRoomId = null
        header.text = "Select a room"
        loadEarlierButton.isVisible = false
        area.text = ""
    }

    fun show(room: MatrixRoom, ownUserId: String) {
        val sameRoom = currentRoomId == room.id
        // Only follow the tail when the user is already at the bottom; otherwise a new message
        // must not yank the view away from what they were reading.
        val wasAtBottom = !sameRoom || isScrolledToBottom()

        currentRoomId = room.id
        header.text = buildString {
            append(room.displayName)
            room.canonicalAlias?.let { append("  ").append(it) }
            room.topic?.takeIf { it.isNotBlank() }?.let {
                append("  -  ").append(it.lineSequence().first().take(TOPIC_LENGTH))
            }
        }
        loadEarlierButton.isVisible = room.prevBatch != null && !room.encrypted

        area.text = render(room, ownUserId)
        if (wasAtBottom) SwingUtilities.invokeLater { scrollToBottom() }
    }

    private fun render(room: MatrixRoom, ownUserId: String): String {
        if (room.encrypted && room.messages.none { !it.encrypted }) {
            return "This room is end-to-end encrypted.\n\n" +
                "The plugin does not implement the Olm/Megolm key exchange, so it can list the " +
                "room but cannot read or send its messages. Use a full Matrix client for this one."
        }
        if (room.messages.isEmpty()) return "No messages yet."

        val builder = StringBuilder()
        var lastDay = ""
        var lastSender = ""
        for (message in room.messages) {
            val day = Formats.date(message.timestamp / 1000)
            if (day != lastDay) {
                if (builder.isNotEmpty()) builder.append('\n')
                builder.append("——— ").append(day).append(" ———\n")
                lastDay = day
                lastSender = ""
            }
            // Consecutive messages from one person do not repeat the name, as every chat client does.
            if (message.sender != lastSender) {
                builder.append('\n').append(message.senderDisplayName)
                if (message.sender == ownUserId) builder.append(" (you)")
                builder.append('\n')
                lastSender = message.sender
            }
            builder.append("  ")
                .append(time(message.timestamp))
                .append("  ")
                .append(bodyOf(message))
                .append('\n')
        }
        return builder.toString()
    }

    private fun bodyOf(message: MatrixMessage): String {
        val text = when (message.kind) {
            MessageKind.EMOTE -> "* ${message.senderDisplayName} ${message.body}"
            MessageKind.IMAGE -> "[image] ${message.fileName ?: message.body}"
            MessageKind.FILE -> "[file] ${message.fileName ?: message.body}" +
                message.fileSize.takeIf { it > 0 }?.let { " (${Formats.bytes(it)})" }.orEmpty()

            MessageKind.AUDIO -> "[audio] ${message.fileName ?: message.body}"
            MessageKind.VIDEO -> "[video] ${message.fileName ?: message.body}"
            MessageKind.LOCATION -> "[location] ${message.body}"
            else -> message.body
        }
        val suffix = when {
            message.redacted -> ""
            message.edited -> "  (edited)"
            else -> ""
        }
        return text.replace("\n", "\n          ") + suffix
    }

    private fun time(timestampMs: Long): String =
        Formats.timestamp(timestampMs / 1000).substringAfter(' ').substringBeforeLast(':')

    private fun isScrolledToBottom(): Boolean {
        val bar = scrollPane.verticalScrollBar
        return bar.value + bar.visibleAmount >= bar.maximum - JBUI.scale(SCROLL_SLACK)
    }

    private fun scrollToBottom() {
        val bar = scrollPane.verticalScrollBar
        bar.value = bar.maximum
    }

    /** Selected text, for the copy action; empty when nothing is selected. */
    fun selectedText(): String = area.selectedText.orEmpty()

    private companion object {
        const val TOPIC_LENGTH = 80
        const val SCROLL_SLACK = 24
    }
}
