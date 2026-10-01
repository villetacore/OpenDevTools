package com.opendevtools.matrix.toolwindow

import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.opendevtools.core.format.Formats
import com.opendevtools.matrix.MatrixIcons
import com.opendevtools.matrix.model.MatrixRoom
import com.opendevtools.matrix.model.RoomGrouping
import com.opendevtools.matrix.model.RoomMembership
import javax.swing.JList

/** One row of the room list: either a group header or a room. */
sealed class RoomListEntry {

    class Header(val title: String, val count: Int) : RoomListEntry()

    class Room(val room: MatrixRoom) : RoomListEntry()
}

/** Turns the snapshot into the flat row list the room list shows, honouring the grouping. */
object RoomListBuilder {

    fun build(
        rooms: List<MatrixRoom>,
        grouping: RoomGrouping,
        query: String,
        showEncrypted: Boolean,
    ): List<RoomListEntry> {
        val visible = rooms
            .filter { showEncrypted || !it.encrypted }
            .filter { it.matches(query) }

        // Invites always come first: they are the only rows that need an answer.
        val invites = visible.filter { it.membership == RoomMembership.INVITED }
        val joined = visible.filter { it.membership == RoomMembership.JOINED }

        return buildList {
            if (invites.isNotEmpty()) {
                add(RoomListEntry.Header("Invitations", invites.size))
                invites.forEach { add(RoomListEntry.Room(it)) }
            }
            when (grouping) {
                RoomGrouping.BY_ACTIVITY -> {
                    val ordered = joined.sortedByDescending { it.lastMessage?.timestamp ?: 0 }
                    if (ordered.isNotEmpty()) add(RoomListEntry.Header("Rooms", ordered.size))
                    ordered.forEach { add(RoomListEntry.Room(it)) }
                }

                RoomGrouping.ALPHABETICAL -> {
                    val ordered = joined.sortedBy { it.displayName.lowercase() }
                    if (ordered.isNotEmpty()) add(RoomListEntry.Header("Rooms", ordered.size))
                    ordered.forEach { add(RoomListEntry.Room(it)) }
                }

                RoomGrouping.BY_MEMBERSHIP -> {
                    val direct = joined.filter { it.direct }.sortedBy { it.displayName.lowercase() }
                    val group = joined.filterNot { it.direct }.sortedBy { it.displayName.lowercase() }
                    if (direct.isNotEmpty()) {
                        add(RoomListEntry.Header("Direct messages", direct.size))
                        direct.forEach { add(RoomListEntry.Room(it)) }
                    }
                    if (group.isNotEmpty()) {
                        add(RoomListEntry.Header("Rooms", group.size))
                        group.forEach { add(RoomListEntry.Room(it)) }
                    }
                }
            }
        }
    }
}

/** Draws a room row: name, unread badge, and a preview of the last message. */
class RoomListCellRenderer : ColoredListCellRenderer<RoomListEntry>() {

    override fun customizeCellRenderer(
        list: JList<out RoomListEntry>,
        value: RoomListEntry?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        when (value) {
            is RoomListEntry.Header -> {
                append(value.title.uppercase(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                append("  ${value.count}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }

            is RoomListEntry.Room -> renderRoom(value.room)
            null -> Unit
        }
    }

    private fun renderRoom(room: MatrixRoom) {
        icon = when {
            room.membership == RoomMembership.INVITED -> MatrixIcons.Invite
            room.encrypted -> MatrixIcons.RoomEncrypted
            room.hasUnread -> MatrixIcons.RoomUnread
            room.direct -> MatrixIcons.DirectMessage
            else -> MatrixIcons.Room
        }
        append(
            room.displayName,
            if (room.hasUnread) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
            else SimpleTextAttributes.REGULAR_ATTRIBUTES,
        )
        if (room.notificationCount > 0) {
            val style = if (room.highlightCount > 0) HIGHLIGHT else BADGE
            append("  ${room.notificationCount}", style)
        }
        if (room.membership == RoomMembership.INVITED) {
            append("  invitation", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
            return
        }
        room.lastMessage?.let { last ->
            append(
                "  ${last.senderDisplayName}: ${last.body.lineSequence().first().take(PREVIEW)}",
                SimpleTextAttributes.GRAYED_ATTRIBUTES,
            )
            append("  ${Formats.relative(last.timestamp)}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }

    private companion object {
        const val PREVIEW = 60
        val BADGE = SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, JBColor(0x4A6FA5, 0x7FA0D0))
        val HIGHLIGHT =
            SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor(0xC0392B, 0xE07A6E))
    }
}
