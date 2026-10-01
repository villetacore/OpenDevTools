package com.opendevtools.matrix.service

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.opendevtools.matrix.model.MatrixRoom
import com.opendevtools.matrix.model.MessageKind
import com.opendevtools.matrix.model.RoomMembership
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Sync is incremental, and that is the whole difficulty: a response says what changed, not what
 * is. These tests pin the merge rules that follow from it.
 */
class SyncMapperTest {

    private val ownUserId = "@alex:example.org"

    private fun sync(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    private fun joinResponse(roomId: String, events: String, state: String = "[]"): JsonObject =
        sync(
            """
            {
              "next_batch": "s2",
              "rooms": {
                "join": {
                  "$roomId": {
                    "state": { "events": $state },
                    "timeline": { "events": $events, "prev_batch": "p1" },
                    "unread_notifications": { "notification_count": 2, "highlight_count": 1 }
                  }
                }
              }
            }
            """.trimIndent(),
        )

    private fun message(id: String, sender: String, body: String, ts: Long): String =
        """
        {
          "event_id": "$id",
          "sender": "$sender",
          "type": "m.room.message",
          "origin_server_ts": $ts,
          "content": { "msgtype": "m.text", "body": "$body" }
        }
        """.trimIndent()

    @Test
    fun `a joined room is built from its state and timeline`() {
        val response = joinResponse(
            "!room:example.org",
            "[${message("\$1", "@bob:example.org", "hello", 1000)}]",
            """[{"type":"m.room.name","state_key":"","content":{"name":"Team"}},
                {"type":"m.room.topic","state_key":"","content":{"topic":"Talking shop"}}]""",
        )

        val rooms = SyncMapper.merge(emptyList(), response, ownUserId)

        assertEquals(1, rooms.size)
        val room = rooms.first()
        assertEquals("Team", room.name)
        assertEquals("Talking shop", room.topic)
        assertEquals(RoomMembership.JOINED, room.membership)
        assertEquals(2, room.notificationCount)
        assertEquals(1, room.highlightCount)
        assertEquals("p1", room.prevBatch)
        assertEquals(listOf("hello"), room.messages.map { it.body })
    }

    @Test
    fun `a room missing from a later response keeps its timeline`() {
        val first = SyncMapper.merge(
            emptyList(),
            joinResponse("!a:example.org", "[${message("\$1", "@bob:example.org", "first", 1000)}]"),
            ownUserId,
        )
        // The next sync only mentions a different room.
        val second = SyncMapper.merge(
            first,
            joinResponse("!b:example.org", "[${message("\$2", "@bob:example.org", "other", 2000)}]"),
            ownUserId,
        )

        val untouched = second.first { it.id == "!a:example.org" }
        assertEquals(listOf("first"), untouched.messages.map { it.body })
        assertEquals(2, second.size)
    }

    @Test
    fun `new events append to the existing timeline`() {
        val first = SyncMapper.merge(
            emptyList(),
            joinResponse("!a:example.org", "[${message("\$1", "@bob:example.org", "one", 1000)}]"),
            ownUserId,
        )
        val second = SyncMapper.merge(
            first,
            joinResponse("!a:example.org", "[${message("\$2", "@bob:example.org", "two", 2000)}]"),
            ownUserId,
        )

        assertEquals(listOf("one", "two"), second.first().messages.map { it.body })
    }

    @Test
    fun `an event id arriving twice replaces rather than duplicates`() {
        val first = SyncMapper.merge(
            emptyList(),
            joinResponse("!a:example.org", "[${message("\$1", "@bob:example.org", "typo", 1000)}]"),
            ownUserId,
        )
        val second = SyncMapper.merge(
            first,
            joinResponse("!a:example.org", "[${message("\$1", "@bob:example.org", "fixed", 1000)}]"),
            ownUserId,
        )

        assertEquals(1, second.first().messages.size)
        assertEquals("fixed", second.first().messages.first().body)
    }

    @Test
    fun `a room the account left disappears`() {
        val joined = SyncMapper.merge(
            emptyList(),
            joinResponse("!a:example.org", "[${message("\$1", "@bob:example.org", "bye", 1000)}]"),
            ownUserId,
        )
        val afterLeaving = SyncMapper.merge(
            joined,
            sync("""{"rooms":{"leave":{"!a:example.org":{}}}}"""),
            ownUserId,
        )

        assertTrue(afterLeaving.isEmpty())
    }

    @Test
    fun `an invitation becomes a room with the invited membership`() {
        val response = sync(
            """
            {
              "rooms": {
                "invite": {
                  "!inv:example.org": {
                    "invite_state": {
                      "events": [{"type":"m.room.name","content":{"name":"Secret"}}]
                    }
                  }
                }
              }
            }
            """.trimIndent(),
        )

        val rooms = SyncMapper.merge(emptyList(), response, ownUserId)

        assertEquals(1, rooms.size)
        assertEquals(RoomMembership.INVITED, rooms.first().membership)
        assertEquals("Secret", rooms.first().name)
        assertTrue(rooms.first().hasUnread)
    }

    @Test
    fun `an encrypted room is flagged and its events are marked unreadable`() {
        val response = joinResponse(
            "!enc:example.org",
            """[{"event_id":"$1","sender":"@bob:example.org","type":"m.room.encrypted",
                 "origin_server_ts":1000,"content":{"algorithm":"m.megolm.v1.aes-sha2"}}]""",
            """[{"type":"m.room.encryption","state_key":"","content":{"algorithm":"m.megolm.v1.aes-sha2"}}]""",
        )

        val room = SyncMapper.merge(emptyList(), response, ownUserId).first()

        assertTrue(room.encrypted)
        assertTrue(room.messages.single().encrypted)
        assertEquals(MessageKind.UNKNOWN, room.messages.single().kind)
    }

    @Test
    fun `an encryption flag once seen is not lost by a later response`() {
        val first = SyncMapper.merge(
            emptyList(),
            joinResponse(
                "!enc:example.org",
                "[]",
                """[{"type":"m.room.encryption","state_key":"","content":{}}]""",
            ),
            ownUserId,
        )
        val second = SyncMapper.merge(
            first,
            joinResponse("!enc:example.org", "[${message("\$1", "@bob:example.org", "x", 1000)}]"),
            ownUserId,
        )

        assertTrue(second.first().encrypted)
    }

    @Test
    fun `a redacted event keeps its place and says so`() {
        val response = joinResponse(
            "!a:example.org",
            """[{"event_id":"$1","sender":"@bob:example.org","type":"m.room.message",
                 "origin_server_ts":1000,"content":{}}]""",
        )

        val message = SyncMapper.merge(emptyList(), response, ownUserId).first().messages.single()

        assertTrue(message.redacted)
        assertEquals("(message deleted)", message.body)
    }

    @Test
    fun `an edit is read from its replacement content`() {
        val response = joinResponse(
            "!a:example.org",
            """[{"event_id":"$2","sender":"@bob:example.org","type":"m.room.message",
                 "origin_server_ts":2000,
                 "content":{"msgtype":"m.text","body":"* corrected",
                            "m.new_content":{"msgtype":"m.text","body":"corrected"},
                            "m.relates_to":{"rel_type":"m.replace","event_id":"$1"}}}]""",
        )

        val message = SyncMapper.merge(emptyList(), response, ownUserId).first().messages.single()

        assertTrue(message.edited)
        assertEquals("corrected", message.body)
    }

    @Test
    fun `members join and leave as their state events arrive`() {
        val joined = SyncMapper.merge(
            emptyList(),
            joinResponse(
                "!a:example.org",
                "[]",
                """[{"type":"m.room.member","state_key":"@bob:example.org",
                     "content":{"membership":"join","displayname":"Bob"}},
                    {"type":"m.room.member","state_key":"@eve:example.org",
                     "content":{"membership":"join","displayname":"Eve"}}]""",
            ),
            ownUserId,
        )
        assertEquals(2, joined.first().members.size)

        val afterLeaving = SyncMapper.merge(
            joined,
            joinResponse(
                "!a:example.org",
                "[]",
                """[{"type":"m.room.member","state_key":"@eve:example.org",
                     "content":{"membership":"leave"}}]""",
            ),
            ownUserId,
        )
        assertEquals(listOf("@bob:example.org"), afterLeaving.first().members.map { it.userId })
    }

    @Test
    fun `direct room ids are read from account data`() {
        val response = sync(
            """
            {
              "account_data": {
                "events": [
                  {"type":"m.direct","content":{"@bob:example.org":["!dm:example.org"]}}
                ]
              }
            }
            """.trimIndent(),
        )

        assertEquals(setOf("!dm:example.org"), SyncMapper.directRoomIds(response))
    }

    @Test
    fun `history pages in ahead of what is already cached`() {
        val room = SyncMapper.merge(
            emptyList(),
            joinResponse("!a:example.org", "[${message("\$5", "@bob:example.org", "recent", 5000)}]"),
            ownUserId,
        ).first()

        val older = listOf(
            requireNotNull(
                SyncMapper.toMessage(
                    "!a:example.org",
                    JsonParser.parseString(message("\$1", "@bob:example.org", "old", 1000)).asJsonObject,
                    false,
                ),
            ),
        )
        val extended = SyncMapper.prependHistory(room, older, "p0")

        assertEquals(listOf("old", "recent"), extended.messages.map { it.body })
        assertEquals("p0", extended.prevBatch)
    }

    @Test
    fun `paging the same page twice does not duplicate messages`() {
        val room = SyncMapper.merge(
            emptyList(),
            joinResponse("!a:example.org", "[${message("\$1", "@bob:example.org", "one", 1000)}]"),
            ownUserId,
        ).first()

        val same = listOf(room.messages.single())
        assertEquals(1, SyncMapper.prependHistory(room, same, "p0").messages.size)
    }

    @Test
    fun `a non-message event is ignored`() {
        val event = JsonParser.parseString(
            """{"event_id":"$1","sender":"@bob:example.org","type":"m.room.topic",
                "origin_server_ts":1000,"content":{"topic":"x"}}""",
        ).asJsonObject

        assertNull(SyncMapper.toMessage("!a:example.org", event, false))
    }

    @Test
    fun `a file message keeps its name and size`() {
        val event = JsonParser.parseString(
            """{"event_id":"$1","sender":"@bob:example.org","type":"m.room.message",
                "origin_server_ts":1000,
                "content":{"msgtype":"m.file","body":"report.pdf","url":"mxc://x/y",
                           "info":{"size":2048}}}""",
        ).asJsonObject

        val message = requireNotNull(SyncMapper.toMessage("!a:example.org", event, false))
        assertEquals(MessageKind.FILE, message.kind)
        assertEquals("report.pdf", message.fileName)
        assertEquals(2048, message.fileSize)
        assertEquals("mxc://x/y", message.url)
    }
}

class MatrixRoomTest {

    private fun room(
        name: String? = null,
        alias: String? = null,
        notifications: Int = 0,
        membership: RoomMembership = RoomMembership.JOINED,
    ) = MatrixRoom(
        id = "!room:example.org",
        name = name,
        topic = null,
        canonicalAlias = alias,
        avatarUrl = null,
        membership = membership,
        encrypted = false,
        notificationCount = notifications,
        highlightCount = 0,
    )

    @Test
    fun `a room falls back from name to alias to id`() {
        assertEquals("Team", room(name = "Team", alias = "#team:example.org").displayName)
        assertEquals("#team:example.org", room(alias = "#team:example.org").displayName)
        assertEquals("!room:example.org", room().displayName)
    }

    @Test
    fun `unread covers both a notification count and a pending invitation`() {
        assertFalse(room().hasUnread)
        assertTrue(room(notifications = 1).hasUnread)
        assertTrue(room(membership = RoomMembership.INVITED).hasUnread)
    }

    @Test
    fun `a mention matches the full id and the local part`() {
        val message = com.opendevtools.matrix.model.MatrixMessage(
            eventId = "\$1",
            roomId = "!a:example.org",
            sender = "@bob:example.org",
            body = "could alex take a look?",
            kind = MessageKind.TEXT,
            timestamp = 0,
        )
        assertTrue(message.mentions("@alex:example.org"))
        assertFalse(message.mentions("@carol:example.org"))
        assertFalse(message.mentions(""))
    }

    @Test
    fun `the sender display name drops the sigil and the server`() {
        val message = com.opendevtools.matrix.model.MatrixMessage(
            eventId = "\$1",
            roomId = "!a:example.org",
            sender = "@bob:example.org",
            body = "hi",
            kind = MessageKind.TEXT,
            timestamp = 0,
        )
        assertEquals("bob", message.senderDisplayName)
    }

    @Test
    fun `search matches the name, the alias and the message bodies`() {
        val withMessages = room(name = "Team", alias = "#team:example.org").copy(
            messages = listOf(
                com.opendevtools.matrix.model.MatrixMessage(
                    eventId = "\$1",
                    roomId = "!room:example.org",
                    sender = "@bob:example.org",
                    body = "deployment finished",
                    kind = MessageKind.TEXT,
                    timestamp = 0,
                ),
            ),
        )
        assertTrue(withMessages.matches(""))
        assertTrue(withMessages.matches("team"))
        assertTrue(withMessages.matches("#team"))
        assertTrue(withMessages.matches("deployment"))
        assertFalse(withMessages.matches("rollback"))
        assertNotNull(withMessages.lastMessage)
    }
}
