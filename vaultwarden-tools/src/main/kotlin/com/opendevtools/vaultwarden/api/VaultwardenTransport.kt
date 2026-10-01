package com.opendevtools.vaultwarden.api

import com.google.gson.JsonObject
import com.opendevtools.core.http.HttpMethod
import com.opendevtools.core.http.HttpRequest
import com.opendevtools.core.http.HttpTransport
import com.opendevtools.core.http.HttpTransportException
import com.opendevtools.core.http.RequestBody
import com.opendevtools.core.json.JsonAccess
import com.opendevtools.core.json.obj
import com.opendevtools.core.json.str
import java.security.SecureRandom

data class ApiConfig(
    val serverUrl: String,
    val clientId: String,
    val clientSecret: String,
    val deviceIdentifier: String,
    val verifySsl: Boolean,
) {
    val baseUrl: String get() = serverUrl.trim().trimEnd('/')

    val isComplete: Boolean
        get() = baseUrl.isNotEmpty() && clientId.isNotBlank() && clientSecret.isNotBlank()
}

/**
 * HTTP plumbing for the Bitwarden-compatible API: attaches the bearer token and the device
 * headers, and turns an error body into a [VaultwardenException] with the server's own wording.
 *
 * Never call from the EDT.
 */
class VaultwardenTransport(private val config: ApiConfig) {

    private val http = HttpTransport(config.baseUrl, config.verifySsl)

    fun get(path: String, accessToken: String?): JsonObject =
        requireObject(execute(HttpMethod.GET, path, null, accessToken), path)

    fun getOrNull(path: String, accessToken: String?): JsonObject? =
        JsonAccess.parseObject(execute(HttpMethod.GET, path, null, accessToken))

    fun postJson(path: String, body: JsonObject, accessToken: String?): JsonObject =
        requireObject(
            execute(HttpMethod.POST, path, RequestBody.Json(body.toString()), accessToken),
            path,
        )

    fun putJson(path: String, body: JsonObject, accessToken: String?): JsonObject =
        requireObject(
            execute(HttpMethod.PUT, path, RequestBody.Json(body.toString()), accessToken),
            path,
        )

    /** Some write endpoints answer 200 with an empty body; callers that ignore it use this. */
    fun putJsonIgnoringResponse(path: String, body: JsonObject, accessToken: String?) {
        execute(HttpMethod.PUT, path, RequestBody.Json(body.toString()), accessToken)
    }

    fun postForm(path: String, form: Map<String, String>): JsonObject =
        requireObject(execute(HttpMethod.POST, path, RequestBody.Form(form), null), path)

    fun delete(path: String, accessToken: String?) {
        execute(HttpMethod.DELETE, path, null, accessToken)
    }

    fun postRaw(path: String, body: RequestBody, accessToken: String?): JsonObject? =
        JsonAccess.parseObject(execute(HttpMethod.POST, path, body, accessToken))

    /** Downloads a binary payload; used for attachments, whose URL is absolute. */
    fun download(url: String): ByteArray {
        val response = try {
            http.execute(HttpRequest(HttpMethod.GET, url))
        } catch (e: HttpTransportException) {
            throw VaultwardenException(e.message ?: "Download failed", e)
        }
        if (!response.isSuccess) {
            throw VaultwardenException(describeError(response.code, response.statusMessage, response.body))
        }
        return response.rawBody
    }

    private fun execute(
        method: HttpMethod,
        path: String,
        body: RequestBody?,
        accessToken: String?,
    ): String {
        val headers = buildMap {
            put("Device-Type", deviceType().toString())
            put("Bitwarden-Client-Name", "cli")
            put("Bitwarden-Client-Version", CLIENT_VERSION)
            accessToken?.let { put("Authorization", "Bearer $it") }
        }
        val response = try {
            http.execute(HttpRequest(method, path, headers = headers, body = body))
        } catch (e: HttpTransportException) {
            throw VaultwardenException(e.message ?: "Request failed", e)
        }
        if (!response.isSuccess) {
            throw VaultwardenException(
                describeError(response.code, response.statusMessage, response.body),
                statusCode = response.code,
            )
        }
        return response.body
    }

    private fun requireObject(body: String, path: String): JsonObject =
        JsonAccess.parseObject(body) ?: throw VaultwardenException("Unexpected response from $path")

    private fun describeError(code: Int, statusMessage: String?, body: String): String {
        val head = "HTTP $code" + statusMessage?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        val details = extractError(body)
        return if (details.isNullOrBlank()) head else "$head - $details"
    }

    /**
     * Servers fill different fields depending on the failure, and some of them send the standard
     * OAuth keys as empty strings, so blank candidates have to be skipped rather than returned.
     */
    private fun extractError(body: String): String? {
        if (body.isBlank()) return null
        val root = JsonAccess.parseObject(body) ?: return body.take(MAX_ERROR_BODY)

        val candidates = listOf(
            root.str("error_description"),
            root.str("message"),
            root.obj("errorModel")?.str("message"),
            root.str("error"),
        )
        candidates.firstOrNull { !it.isNullOrBlank() }?.let { return it }

        // Validation failures arrive as { validationErrors: { field: [messages] } }.
        root.obj("validationErrors")?.let { errors ->
            val text = errors.entrySet().joinToString("; ") { "${it.key}: ${it.value}" }
            if (text.isNotBlank()) return text
        }
        return body.take(MAX_ERROR_BODY)
    }

    companion object {
        private const val MAX_ERROR_BODY = 300
        private const val CLIENT_VERSION = "2024.1.0"

        private val random = SecureRandom()

        /** Bitwarden device type enum: 6 Windows, 7 macOS, 8 Linux desktop. */
        fun deviceType(): Int {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            return when {
                os.contains("win") -> 6
                os.contains("mac") || os.contains("darwin") -> 7
                else -> 8
            }
        }

        /** A multipart boundary that cannot collide with the payload. */
        fun newBoundary(): String {
            val bytes = ByteArray(16)
            random.nextBytes(bytes)
            return "----OpenDevTools" + bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
