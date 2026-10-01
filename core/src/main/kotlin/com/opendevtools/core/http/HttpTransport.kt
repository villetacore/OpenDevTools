package com.opendevtools.core.http

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** HTTP verbs used by the plugins. */
enum class HttpMethod { GET, POST, PUT, DELETE, PATCH }

/** Body of a request, together with the content type the server should be told about. */
sealed class RequestBody {

    abstract val contentType: String

    abstract fun bytes(): ByteArray

    /** `application/x-www-form-urlencoded`; Proxmox speaks this for every write. */
    class Form(val fields: Map<String, String>) : RequestBody() {
        override val contentType: String = "application/x-www-form-urlencoded"
        override fun bytes(): ByteArray = encodeForm(fields).toByteArray(StandardCharsets.UTF_8)
    }

    /** A pre-rendered JSON document; Bitwarden speaks this for every write. */
    class Json(val json: String) : RequestBody() {
        override val contentType: String = "application/json; charset=utf-8"
        override fun bytes(): ByteArray = json.toByteArray(StandardCharsets.UTF_8)
    }

    /** Raw bytes with an explicit content type, used for multipart uploads. */
    class Raw(private val payload: ByteArray, override val contentType: String) : RequestBody() {
        override fun bytes(): ByteArray = payload
    }
}

class HttpRequest(
    val method: HttpMethod,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val body: RequestBody? = null,
)

class HttpResponse(val code: Int, val statusMessage: String?, val body: String, val rawBody: ByteArray) {
    val isSuccess: Boolean get() = code in 200..299
}

/**
 * Blocking HTTP transport shared by the plugins. It deliberately stays on `HttpURLConnection`:
 * the IDE bundles no HTTP client that is stable across versions, and the plugins only ever issue
 * a handful of simple requests.
 *
 * Never call from the EDT.
 */
class HttpTransport(
    private val baseUrl: String,
    private val verifySsl: Boolean,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) {

    fun execute(request: HttpRequest): HttpResponse {
        val url = buildUrl(request)
        val connection = openConnection(url)
        try {
            // HttpURLConnection refuses PATCH; the header below is what every server honours.
            val effectiveMethod = if (request.method == HttpMethod.PATCH) HttpMethod.POST else request.method
            connection.requestMethod = effectiveMethod.name
            if (request.method == HttpMethod.PATCH) {
                connection.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            }
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }

            request.body?.let { body ->
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", body.contentType)
                connection.outputStream.use { it.write(body.bytes()) }
            }

            val code = connection.responseCode
            val raw = readBody(if (code in 200..299) connection.inputStream else connection.errorStream)
            return HttpResponse(code, connection.responseMessage, String(raw, StandardCharsets.UTF_8), raw)
        } catch (e: IOException) {
            throw HttpTransportException("Cannot reach $baseUrl - ${e.message ?: e.toString()}", e)
        } finally {
            connection.disconnect()
        }
    }

    private fun buildUrl(request: HttpRequest): String {
        val path = if (request.path.startsWith("http")) request.path else baseUrl + request.path
        if (request.query.isEmpty()) return path
        val separator = if (path.contains('?')) "&" else "?"
        return path + separator + encodeForm(request.query)
    }

    private fun openConnection(url: String): HttpURLConnection {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        if (!verifySsl) Tls.relax(connection)
        return connection
    }

    private fun readBody(stream: InputStream?): ByteArray =
        stream?.use { it.readBytes() } ?: ByteArray(0)

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
        const val DEFAULT_READ_TIMEOUT_MS = 60_000
    }
}

class HttpTransportException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

fun encodeForm(fields: Map<String, String>): String =
    fields.entries.joinToString("&") { (key, value) -> urlEncode(key) + "=" + urlEncode(value) }

fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

/** Percent-encoding for a path segment: `+` means a literal plus in a path, not a space. */
fun encodePathSegment(value: String): String = urlEncode(value).replace("+", "%20")
