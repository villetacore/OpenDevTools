package com.opendevtools.matrix.model

/** How the local user relates to a room. */
enum class RoomMembership(val title: String) {
    JOINED("Joined"),
    INVITED("Invited"),
    LEFT("Left"),
}

/** The message kinds the timeline renders; anything else falls back to [UNKNOWN]. */
enum class MessageKind(val msgtype: String) {
    TEXT("m.text"),
    EMOTE("m.emote"),
    NOTICE("m.notice"),
    IMAGE("m.image"),
    FILE("m.file"),
    AUDIO("m.audio"),
    VIDEO("m.video"),
    LOCATION("m.location"),
    UNKNOWN("");

    companion object {
        fun fromMsgType(value: String?): MessageKind =
            entries.firstOrNull { it.msgtype == value } ?: UNKNOWN
    }
}

/** One timeline entry, already reduced to what the UI needs. */
data class MatrixMessage(
    val eventId: String,
    val roomId: String,
    val sender: String,
    val body: String,
    val kind: MessageKind,
    val timestamp: Long,
    /** `mxc://` reference of an attachment, for the media kinds. */
    val url: String? = null,
    val fileName: String? = null,
    val fileSize: Long = 0,
    val redacted: Boolean = false,
    val edited: Boolean = false,
    /** Event id this message replies to, from `m.relates_to`/`m.in_reply_to`. */
    val replyTo: String? = null,
    /** True when the event could not be read because the room is encrypted. */
    val encrypted: Boolean = false,
) {
    val key: String get() = "message:$eventId"

    /** The short name shown in the timeline: `@alex:example.org` reads as `alex`. */
    val senderDisplayName: String
        get() = sender.removePrefix("@").substringBefore(':')

    fun mentions(userId: String): Boolean {
        if (userId.isBlank()) return false
        val localPart = userId.removePrefix("@").substringBefore(':')
        return body.contains(userId, ignoreCase = true) ||
            (localPart.isNotEmpty() && body.contains(localPart, ignoreCase = true))
    }
}

/** A member of a room, as far as the sync payload describes them. */
data class RoomMember(val userId: String, val displayName: String?, val avatarUrl: String?) {
    val name: String get() = displayName ?: userId.removePrefix("@").substringBefore(':')
}

/**
 * A room and its recent timeline. [encrypted] rooms are listed and can be left, but their
 * messages cannot be read - see the note in the README.
 */
data class MatrixRoom(
    val id: String,
    val name: String?,
    val topic: String?,
    val canonicalAlias: String?,
    val avatarUrl: String?,
    val membership: RoomMembership,
    val encrypted: Boolean,
    val notificationCount: Int,
    val highlightCount: Int,
    val messages: List<MatrixMessage> = emptyList(),
    val members: List<RoomMember> = emptyList(),
    /** Token for paging further back through this room's history. */
    val prevBatch: String? = null,
    val direct: Boolean = false,
) {
    val key: String get() = "room:$id"

    /** A room with no name falls back to its alias, then to the other member, then to its id. */
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() }
            ?: canonicalAlias?.takeIf { it.isNotBlank() }
            ?: members.firstOrNull { it.userId != OWN_MEMBER_PLACEHOLDER }?.name
            ?: id

    val lastMessage: MatrixMessage? get() = messages.lastOrNull()

    val hasUnread: Boolean get() = notificationCount > 0 || membership == RoomMembership.INVITED

    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        val needle = query.trim().lowercase()
        return displayName.lowercase().contains(needle) ||
            canonicalAlias?.lowercase()?.contains(needle) == true ||
            topic?.lowercase()?.contains(needle) == true ||
            messages.any { it.body.lowercase().contains(needle) }
    }

    private companion object {
        const val OWN_MEMBER_PLACEHOLDER = ""
    }
}

/** How the room list is grouped. */
enum class RoomGrouping(val title: String) {
    BY_ACTIVITY("Recent activity"),
    BY_MEMBERSHIP("Invites and rooms"),
    ALPHABETICAL("Alphabetical"),
}

/** One consistent view of the account, rebuilt after every sync. */
data class MatrixSnapshot(
    val rooms: List<MatrixRoom>,
    val userId: String,
    val nextBatch: String?,
    val loadedAt: Long = System.currentTimeMillis(),
) {
    fun room(id: String): MatrixRoom? = rooms.firstOrNull { it.id == id }

    val invites: List<MatrixRoom> get() = rooms.filter { it.membership == RoomMembership.INVITED }

    companion object {
        val EMPTY = MatrixSnapshot(emptyList(), "", null)
    }
}
