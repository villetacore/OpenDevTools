package com.opendevtools.proxmox.api

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.opendevtools.core.http.HttpMethod
import com.opendevtools.core.http.HttpRequest
import com.opendevtools.core.http.HttpTransport
import com.opendevtools.core.http.HttpTransportException
import com.opendevtools.core.http.RequestBody
import com.opendevtools.core.http.urlEncode
import com.opendevtools.core.json.JsonAccess
import com.opendevtools.core.json.asObjectList
import com.opendevtools.core.json.asObjectOrNull
import com.opendevtools.core.json.asStringOrNull
import com.opendevtools.core.json.notNull
import com.opendevtools.core.json.obj
import com.opendevtools.core.json.str
import com.opendevtools.proxmox.settings.ProxmoxAuthType
import com.opendevtools.proxmox.settings.ProxmoxConnectionConfig

/**
 * Authenticated access to `/api2/json`. Everything the endpoint groups do goes through
 * [request]: it attaches either the API-token header or a login ticket plus the CSRF token,
 * unwraps the `data` envelope, and turns an error body into a [ProxmoxException].
 *
 * Never call from the EDT - every method performs network I/O.
 */
class ProxmoxTransport(val config: ProxmoxConnectionConfig) {

    private class Ticket(val value: String, val csrf: String, val issuedAt: Long)

    private val http = HttpTransport(config.apiUrl, config.verifySsl)

    @Volatile
    private var ticket: Ticket? = null

    fun get(path: String, params: Map<String, String> = emptyMap()): JsonElement? =
        request(HttpMethod.GET, path, params)

    fun post(path: String, params: Map<String, String> = emptyMap()): JsonElement? =
        request(HttpMethod.POST, path, params)

    fun put(path: String, params: Map<String, String> = emptyMap()): JsonElement? =
        request(HttpMethod.PUT, path, params)

    fun delete(path: String, params: Map<String, String> = emptyMap()): JsonElement? =
        request(HttpMethod.DELETE, path, params)

    fun getObjects(path: String, params: Map<String, String> = emptyMap()): List<JsonObject> =
        get(path, params).asObjectList()

    fun getObject(path: String, params: Map<String, String> = emptyMap()): JsonObject? =
        get(path, params).asObjectOrNull()

    /** Write endpoints answer with the UPID of the task they started, or with nothing at all. */
    fun postForUpid(path: String, params: Map<String, String> = emptyMap()): String? =
        post(path, params).asStringOrNull()

    fun putForUpid(path: String, params: Map<String, String> = emptyMap()): String? =
        put(path, params).asStringOrNull()

    fun deleteForUpid(path: String, params: Map<String, String> = emptyMap()): String? =
        delete(path, params).asStringOrNull()

    private fun request(
        method: HttpMethod,
        path: String,
        params: Map<String, String>,
        allowRetry: Boolean = true,
    ): JsonElement? {
        val headers = LinkedHashMap<String, String>()
        applyAuthentication(headers, method)

        val request = if (method == HttpMethod.GET || params.isEmpty()) {
            HttpRequest(method, path, query = params.takeIf { method == HttpMethod.GET }.orEmpty(), headers = headers)
        } else {
            HttpRequest(method, path, headers = headers, body = RequestBody.Form(params))
        }

        val response = try {
            http.execute(request)
        } catch (e: HttpTransportException) {
            throw ProxmoxException(e.message ?: "Request failed", e)
        }

        // A ticket can expire between two refreshes; one silent re-login is friendlier than an error.
        if (response.code == 401 && allowRetry && config.authType == ProxmoxAuthType.PASSWORD) {
            ticket = null
            return request(method, path, params, allowRetry = false)
        }
        if (!response.isSuccess) {
            throw ProxmoxException(
                describeError(response.code, response.statusMessage, response.body),
                statusCode = response.code,
            )
        }
        return unwrap(response.body)
    }

    private fun applyAuthentication(headers: MutableMap<String, String>, method: HttpMethod) {
        when (config.authType) {
            ProxmoxAuthType.API_TOKEN ->
                headers["Authorization"] = "PVEAPIToken=${config.tokenId}=${config.secret}"

            ProxmoxAuthType.PASSWORD -> {
                val current = validTicket()
                headers["Cookie"] = "PVEAuthCookie=" + urlEncode(current.value)
                if (method != HttpMethod.GET) headers["CSRFPreventionToken"] = current.csrf
            }
        }
    }

    private fun validTicket(): Ticket {
        val current = ticket
        if (current != null && System.currentTimeMillis() - current.issuedAt < TICKET_LIFETIME_MS) {
            return current
        }
        return login().also { ticket = it }
    }

    private fun login(): Ticket {
        val response = try {
            http.execute(
                HttpRequest(
                    HttpMethod.POST,
                    "/access/ticket",
                    body = RequestBody.Form(
                        mapOf("username" to config.user, "password" to config.secret),
                    ),
                ),
            )
        } catch (e: HttpTransportException) {
            throw ProxmoxException(e.message ?: "Login failed", e)
        }
        if (!response.isSuccess) {
            throw ProxmoxException(
                "Authentication as ${config.user} failed: " +
                    describeError(response.code, response.statusMessage, response.body),
                statusCode = response.code,
            )
        }
        val data = unwrap(response.body).asObjectOrNull()
            ?: throw ProxmoxException("Authentication response did not contain a ticket")
        val value = data.str("ticket")
            ?: throw ProxmoxException("Authentication response did not contain a ticket")
        return Ticket(value, data.str("CSRFPreventionToken").orEmpty(), System.currentTimeMillis())
    }

    private fun unwrap(body: String): JsonElement? {
        if (body.isBlank()) return null
        val root = JsonAccess.parse(body)
            ?: throw ProxmoxException("Unexpected non-JSON response from the server")
        if (!root.isJsonObject) return root
        return root.asJsonObject.get("data").notNull()
    }

    private fun describeError(code: Int, statusMessage: String?, body: String): String {
        val head = "HTTP $code" + statusMessage?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        val details = extractErrorDetails(body)
        return if (details.isNullOrBlank()) head else "$head - $details"
    }

    /** Proxmox reports parameter problems in `errors` and everything else in `message`. */
    private fun extractErrorDetails(body: String): String? {
        val root = JsonAccess.parseObject(body) ?: return null
        root.obj("errors")?.let { errors ->
            val text = errors.entrySet().joinToString("; ") { "${it.key}: ${it.value.asStringOrNull() ?: it.value}" }
            if (text.isNotBlank()) return text
        }
        return root.str("message")?.trim()
    }

    companion object {
        /** Proxmox tickets are valid for two hours; refresh well before that. */
        private const val TICKET_LIFETIME_MS = 90L * 60 * 1000
    }
}
