package com.opendevtools.matrix.api

import com.google.gson.JsonObject
import com.opendevtools.core.http.HttpMethod
import com.opendevtools.core.http.HttpRequest
import com.opendevtools.core.http.HttpTransport
import com.opendevtools.core.http.HttpTransportException
import com.opendevtools.core.http.RequestBody
import com.opendevtools.core.http.encodePathSegment
import com.opendevtools.core.json.JsonAccess
import com.opendevtools.core.json.JsonWriter
import com.opendevtools.core.json.longOrNull
import com.opendevtools.core.json.str
import com.opendevtools.core.json.stringArray

/** Non-secret part of a homeserver connection. */
data class MatrixConfig(
    val homeserverUrl: String,
    val userId: String,
    val verifySsl: Boolean,
) {
    val baseUrl: String get() = homeserverUrl.trim().trimEnd('/')

    val isComplete: Boolean get() = baseUrl.isNotEmpty()
}

/** What a successful login hands back. */
data class MatrixSession(val userId: String, val accessToken: String, val deviceId: String?)

/**
 * The Matrix Client-Server API (v3). Only the endpoints the tool window actually uses are here;
 * each one names the spec route it maps to so the mapping stays checkable.
 *
 * Never call from the EDT - `sync` in particular blocks for as long as its timeout.
 */
class MatrixApi(private val config: MatrixConfig) {

    /**
     * Sync long-polls, so it needs a read timeout above its own `timeout` parameter; everything
     * else uses the shorter default.
     */
    private val http = HttpTransport(config.baseUrl, config.verifySsl)
    private val syncHttp = HttpTransport(config.baseUrl, config.verifySsl, readTimeoutMs = SYNC_READ_TIMEOUT_MS)

    // --- Discovery and login --------------------------------------------------------------------

    /** `GET /_matrix/client/versions` - also the cheapest reachability probe. */
    fun versions(): List<String> =
        get("/_matrix/client/versions", token = null).stringArray("versions")

    /** `POST /_matrix/client/v3/login` with `m.login.password`. */
    fun loginWithPassword(user: String, password: CharArray, deviceName: String): MatrixSession {
        val body = JsonWriter.build {
            put("type", "m.login.password")
            put(
                "identifier",
                JsonWriter.build {
                    put("type", "m.id.user")
                    put("user", user)
                },
            )
            put("password", String(password))
            put("initial_device_display_name", deviceName)
        }
        val response = post("/_matrix/client/v3/login", body, token = null)
        return MatrixSession(
            userId = response.str("user_id")
                ?: throw MatrixException("The server did not return a user id"),
            accessToken = response.str("access_token")
                ?: throw MatrixException("The server did not return an access token"),
            deviceId = response.str("device_id"),
        )
    }

    /** `GET /_matrix/client/v3/account/whoami` - validates an access token pasted by hand. */
    fun whoAmI(token: String): String =
        get("/_matrix/client/v3/account/whoami", token).str("user_id")
            ?: throw MatrixException("The server did not report a user id for this token")

    /** `POST /_matrix/client/v3/logout` - invalidates the access token server-side. */
    fun logout(token: String) {
        runCatching { post("/_matrix/client/v3/logout", JsonObject(), token) }
    }

    // --- Sync -----------------------------------------------------------------------------------

    /**
     * `GET /_matrix/client/v3/sync`. With no [since] this is the initial sync and the server may
     * take a while; afterwards it long-polls for up to [timeoutMs] and returns as soon as
     * something happens.
     */
    fun sync(token: String, since: String?, timeoutMs: Int, messageLimit: Int): JsonObject {
        val query = buildMap {
            put("timeout", timeoutMs.toString())
            since?.let { put("since", it) }
            // A filter keeps the initial sync from dragging in the entire history of every room.
            put("filter", syncFilter(messageLimit))
        }
        return request(syncHttp, HttpMethod.GET, "/_matrix/client/v3/sync", query, null, token)
    }

    /** The inline filter used by [sync]; the spec allows it as a JSON string in the query. */
    private fun syncFilter(messageLimit: Int): String = JsonWriter.build {
        put(
            "room",
            JsonWriter.build {
                put("timeline", JsonWriter.build { put("limit", messageLimit) })
            },
        )
    }.toString()

    // --- Rooms ----------------------------------------------------------------------------------

    /** `GET /_matrix/client/v3/joined_rooms`. */
    fun joinedRooms(token: String): List<String> =
        get("/_matrix/client/v3/joined_rooms", token).stringArray("joined_rooms")

    /**
     * `GET /_matrix/client/v3/rooms/{roomId}/messages` - paging backwards through history,
     * which is what "load earlier messages" needs.
     */
    fun messages(token: String, roomId: String, from: String?, limit: Int): JsonObject = request(
        http,
        HttpMethod.GET,
        "/_matrix/client/v3/rooms/${encodePathSegment(roomId)}/messages",
        buildMap {
            put("dir", "b")
            put("limit", limit.toString())
            from?.let { put("from", it) }
        },
        null,
        token,
    )

    /**
     * `PUT /_matrix/client/v3/rooms/{roomId}/send/m.room.message/{txnId}`.
     * The transaction id makes the send idempotent if the request is retried.
     */
    fun sendMessage(
        token: String,
        roomId: String,
        body: String,
        transactionId: String,
        formattedBody: String? = null,
    ): String {
        val payload = JsonWriter.build {
            put("msgtype", "m.text")
            put("body", body)
            formattedBody?.let {
                put("format", "org.matrix.custom.html")
                put("formatted_body", it)
            }
        }
        val response = request(
            http,
            HttpMethod.PUT,
            "/_matrix/client/v3/rooms/${encodePathSegment(roomId)}/send/m.room.message/" +
                encodePathSegment(transactionId),
            emptyMap(),
            payload,
            token,
        )
        return response.str("event_id") ?: throw MatrixException("The server returned no event id")
    }

    /** `POST /_matrix/client/v3/rooms/{roomId}/read_markers` - clears the unread badge. */
    fun markRead(token: String, roomId: String, eventId: String) {
        post(
            "/_matrix/client/v3/rooms/${encodePathSegment(roomId)}/read_markers",
            JsonWriter.build {
                put("m.fully_read", eventId)
                put("m.read", eventId)
            },
            token,
        )
    }

    /** `PUT /_matrix/client/v3/rooms/{roomId}/typing/{userId}`. */
    fun setTyping(token: String, roomId: String, userId: String, typing: Boolean, timeoutMs: Int) {
        runCatching {
            request(
                http,
                HttpMethod.PUT,
                "/_matrix/client/v3/rooms/${encodePathSegment(roomId)}/typing/" +
                    encodePathSegment(userId),
                emptyMap(),
                JsonWriter.build {
                    put("typing", typing)
                    if (typing) put("timeout", timeoutMs)
                },
                token,
            )
        }
    }

    /** `POST /_matrix/client/v3/join/{roomIdOrAlias}` - accepts an invite or joins by alias. */
    fun joinRoom(token: String, roomIdOrAlias: String): String =
        post("/_matrix/client/v3/join/${encodePathSegment(roomIdOrAlias)}", JsonObject(), token)
            .str("room_id") ?: roomIdOrAlias

    /** `POST /_matrix/client/v3/rooms/{roomId}/leave` - also how an invite is declined. */
    fun leaveRoom(token: String, roomId: String) {
        post("/_matrix/client/v3/rooms/${encodePathSegment(roomId)}/leave", JsonObject(), token)
    }

    /** `POST /_matrix/client/v3/rooms/{roomId}/invite`. */
    fun invite(token: String, roomId: String, userId: String) {
        post(
            "/_matrix/client/v3/rooms/${encodePathSegment(roomId)}/invite",
            JsonWriter.build { put("user_id", userId) },
            token,
        )
    }

    /** `PUT /_matrix/client/v3/rooms/{roomId}/redact/{eventId}/{txnId}` - deletes a message. */
    fun redact(token: String, roomId: String, eventId: String, transactionId: String, reason: String?) {
        request(
            http,
            HttpMethod.PUT,
            "/_matrix/client/v3/rooms/${encodePathSegment(roomId)}/redact/" +
                encodePathSegment(eventId) + "/" + encodePathSegment(transactionId),
            emptyMap(),
            JsonWriter.build { reason?.takeIf { it.isNotBlank() }?.let { put("reason", it) } },
            token,
        )
    }

    // --- Profiles -------------------------------------------------------------------------------

    /** `GET /_matrix/client/v3/profile/{userId}`. */
    fun profile(token: String, userId: String): JsonObject? = runCatching {
        get("/_matrix/client/v3/profile/${encodePathSegment(userId)}", token)
    }.getOrNull()

    // --- Plumbing -------------------------------------------------------------------------------

    private fun get(path: String, token: String?): JsonObject =
        request(http, HttpMethod.GET, path, emptyMap(), null, token)

    private fun post(path: String, body: JsonObject, token: String?): JsonObject =
        request(http, HttpMethod.POST, path, emptyMap(), body, token)

    private fun request(
        transport: HttpTransport,
        method: HttpMethod,
        path: String,
        query: Map<String, String>,
        body: JsonObject?,
        token: String?,
    ): JsonObject {
        val headers = token?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty()
        val response = try {
            transport.execute(
                HttpRequest(
                    method,
                    path,
                    query = query,
                    headers = headers,
                    body = body?.let { RequestBody.Json(it.toString()) },
                ),
            )
        } catch (e: HttpTransportException) {
            throw MatrixException(e.message ?: "Request failed", e)
        }

        if (!response.isSuccess) throw describeError(response.code, response.statusMessage, response.body)
        return JsonAccess.parseObject(response.body)
            ?: throw MatrixException("Unexpected response from $path")
    }

    /**
     * Matrix errors carry `errcode` and `error`, and the plugin is far more useful when it repeats
     * the server's own wording than when it renders "HTTP 403".
     */
    private fun describeError(code: Int, statusMessage: String?, body: String): MatrixException {
        val root = JsonAccess.parseObject(body)
        val errorCode = root?.str("errcode")
        val detail = root?.str("error")
        val head = "HTTP $code" + statusMessage?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        val message = when {
            !detail.isNullOrBlank() && !errorCode.isNullOrBlank() -> "$detail ($errorCode)"
            !detail.isNullOrBlank() -> detail
            else -> head
        }
        return MatrixException(
            message,
            statusCode = code,
            errorCode = errorCode,
            retryAfterMs = root?.longOrNull("retry_after_ms"),
        )
    }

    companion object {
        /** Long-polling sync needs a read timeout comfortably above its own timeout parameter. */
        private const val SYNC_READ_TIMEOUT_MS = 90_000
    }
}
