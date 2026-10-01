package com.opendevtools.matrix.service

import com.google.gson.JsonObject
import com.opendevtools.core.json.array
import com.opendevtools.core.json.int
import com.opendevtools.core.json.long
import com.opendevtools.core.json.obj
import com.opendevtools.core.json.str
import com.opendevtools.matrix.model.MatrixMessage
import com.opendevtools.matrix.model.MatrixRoom
import com.opendevtools.matrix.model.MessageKind
import com.opendevtools.matrix.model.RoomMember
import com.opendevtools.matrix.model.RoomMembership

/**
 * Folds a `/sync` response into the plugin model.
 *
 * Sync is incremental: each response carries only what changed, so rooms are merged onto the
 * previous snapshot rather than replacing it. A room absent from a response has simply not
 * changed and must keep its timeline.
 */
object SyncMapper {

    /** Merges one sync response into [previous] and returns the new room list. */
    fun merge(previous: List<MatrixRoom>, sync: JsonObject, ownUserId: String): List<MatrixRoom> {
        val byId = previous.associateBy { it.id }.toMutableMap()
        val rooms = sync.obj("rooms")

        rooms?.obj("join")?.entrySet()?.forEach { (roomId, raw) ->
            val room = raw as? JsonObject ?: return@forEach
            byId[roomId] = mergeJoined(byId[roomId], roomId, room, ownUserId)
        }
        rooms?.obj("invite")?.entrySet()?.forEach { (roomId, raw) ->
            val room = raw as? JsonObject ?: return@forEach
            byId[roomId] = mergeInvited(byId[roomId], roomId, room)
        }
        // A room the account left disappears from the list entirely.
        rooms?.obj("leave")?.entrySet()?.forEach { (roomId, _) -> byId.remove(roomId) }

        return byId.values.sortedByDescending { it.lastMessage?.timestamp ?: 0 }
    }

    private fun mergeJoined(
        existing: MatrixRoom?,
        roomId: String,
        raw: JsonObject,
        ownUserId: String,
    ): MatrixRoom {
        val stateEvents = raw.obj("state")?.array("events").orEmpty()
        val timeline = raw.obj("timeline")
        val timelineEvents = timeline?.array("events").orEmpty()

        // Room name and topic can arrive in either section; the timeline wins because it is newer.
        val allState = stateEvents + timelineEvents.filter { it.str("state_key") != null }

        val unread = raw.obj("unread_notifications")
        val encrypted = existing?.encrypted == true ||
            allState.any { it.str("type") == "m.room.encryption" }

        // A limited timeline means the server left a gap; the cached messages before it may no
        // longer be contiguous, but keeping them still beats showing nothing.
        val merged = mergeMessages(
            existing?.messages.orEmpty(),
            timelineEvents.mapNotNull { toMessage(roomId, it, encrypted) },
        )

        return MatrixRoom(
            id = roomId,
            name = stateValue(allState, "m.room.name", "name") ?: existing?.name,
            topic = stateValue(allState, "m.room.topic", "topic") ?: existing?.topic,
            canonicalAlias = stateValue(allState, "m.room.canonical_alias", "alias")
                ?: existing?.canonicalAlias,
            avatarUrl = stateValue(allState, "m.room.avatar", "url") ?: existing?.avatarUrl,
            membership = RoomMembership.JOINED,
            encrypted = encrypted,
            notificationCount = unread?.int("notification_count") ?: existing?.notificationCount ?: 0,
            highlightCount = unread?.int("highlight_count") ?: existing?.highlightCount ?: 0,
            messages = merged,
            members = mergeMembers(existing?.members.orEmpty(), allState, ownUserId),
            prevBatch = timeline?.str("prev_batch") ?: existing?.prevBatch,
            direct = existing?.direct == true,
        )
    }

    private fun mergeInvited(existing: MatrixRoom?, roomId: String, raw: JsonObject): MatrixRoom {
        val state = raw.obj("invite_state")?.array("events").orEmpty()
        return MatrixRoom(
            id = roomId,
            name = stateValue(state, "m.room.name", "name") ?: existing?.name,
            topic = stateValue(state, "m.room.topic", "topic") ?: existing?.topic,
            canonicalAlias = stateValue(state, "m.room.canonical_alias", "alias"),
            avatarUrl = stateValue(state, "m.room.avatar", "url"),
            membership = RoomMembership.INVITED,
            encrypted = state.any { it.str("type") == "m.room.encryption" },
            notificationCount = 1,
            highlightCount = 0,
            messages = emptyList(),
            members = emptyList(),
        )
    }

    /** Prepends history loaded by paging backwards through `/messages`. */
    fun prependHistory(room: MatrixRoom, older: List<MatrixMessage>, newPrevBatch: String?): MatrixRoom {
        val known = room.messages.map { it.eventId }.toSet()
        val additions = older.filterNot { it.eventId in known }
        return room.copy(
            messages = (additions + room.messages).sortedBy { it.timestamp },
            prevBatch = newPrevBatch,
        )
    }

    /**
     * New events are appended, and an event already present is replaced - an edit or a redaction
     * arrives as a new event that supersedes an old one.
     */
    private fun mergeMessages(
        existing: List<MatrixMessage>,
        incoming: List<MatrixMessage>,
    ): List<MatrixMessage> {
        if (incoming.isEmpty()) return existing
        val byId = LinkedHashMap<String, MatrixMessage>()
        existing.forEach { byId[it.eventId] = it }
        // A redaction or an edit replaces the original in place rather than adding a row.
        incoming.forEach { byId[it.eventId] = it }
        return byId.values.sortedBy { it.timestamp }.takeLast(MAX_CACHED_MESSAGES)
    }

    private fun mergeMembers(
        existing: List<RoomMember>,
        state: List<JsonObject>,
        ownUserId: String,
    ): List<RoomMember> {
        val byId = existing.associateBy { it.userId }.toMutableMap()
        state.filter { it.str("type") == "m.room.member" }.forEach { event ->
            val userId = event.str("state_key") ?: return@forEach
            val content = event.obj("content")
            when (content?.str("membership")) {
                "join" -> byId[userId] = RoomMember(
                    userId = userId,
                    displayName = content.str("displayname"),
                    avatarUrl = content.str("avatar_url"),
                )

                "leave", "ban" -> byId.remove(userId)
            }
        }
        return byId.values.sortedBy { if (it.userId == ownUserId) "" else it.name.lowercase() }
    }

    /** Turns one timeline event into a message, or null when it is not one. */
    fun toMessage(roomId: String, event: JsonObject, roomEncrypted: Boolean): MatrixMessage? {
        val eventId = event.str("event_id") ?: return null
        val sender = event.str("sender") ?: return null
        val timestamp = event.long("origin_server_ts")
        val type = event.str("type")

        if (type == "m.room.encrypted") {
            return MatrixMessage(
                eventId = eventId,
                roomId = roomId,
                sender = sender,
                body = "(encrypted message - this plugin cannot read it)",
                kind = MessageKind.UNKNOWN,
                timestamp = timestamp,
                encrypted = true,
            )
        }
        if (type != "m.room.message") return null

        val content = event.obj("content")
        // A redacted event keeps its shell but loses its content.
        if (content == null || content.entrySet().isEmpty()) {
            return MatrixMessage(
                eventId = eventId,
                roomId = roomId,
                sender = sender,
                body = "(message deleted)",
                kind = MessageKind.UNKNOWN,
                timestamp = timestamp,
                redacted = true,
                encrypted = roomEncrypted,
            )
        }

        // An edit carries the replacement text in `m.new_content`.
        val relatesTo = content.obj("m.relates_to")
        val isEdit = relatesTo?.str("rel_type") == "m.replace"
        val effective = if (isEdit) content.obj("m.new_content") ?: content else content
        val info = effective.obj("info")

        return MatrixMessage(
            eventId = eventId,
            roomId = roomId,
            sender = sender,
            body = effective.str("body").orEmpty(),
            kind = MessageKind.fromMsgType(effective.str("msgtype")),
            timestamp = timestamp,
            url = effective.str("url"),
            fileName = effective.str("filename") ?: effective.str("body"),
            fileSize = info?.long("size") ?: 0,
            edited = isEdit,
            replyTo = relatesTo?.obj("m.in_reply_to")?.str("event_id"),
            encrypted = false,
        )
    }

    /** Reads one field out of the newest state event of a given type. */
    private fun stateValue(events: List<JsonObject>, type: String, field: String): String? =
        events.lastOrNull { it.str("type") == type }
            ?.obj("content")
            ?.str(field)
            ?.takeIf { it.isNotBlank() }

    /** Direct-message room ids, from the `m.direct` account data event. */
    fun directRoomIds(sync: JsonObject): Set<String> =
        sync.obj("account_data")?.array("events").orEmpty()
            .firstOrNull { it.str("type") == "m.direct" }
            ?.obj("content")
            ?.entrySet()
            ?.flatMap { (_, value) ->
                (value as? com.google.gson.JsonArray)
                    ?.mapNotNull { runCatching { it.asString }.getOrNull() }
                    .orEmpty()
            }
            ?.toSet()
            .orEmpty()

    /** How many messages of a room are kept in memory; a session can otherwise grow without limit. */
    const val MAX_CACHED_MESSAGES = 500
}
