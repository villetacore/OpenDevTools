package com.opendevtools.vaultwarden.api

import com.google.gson.JsonObject
import com.opendevtools.core.http.RequestBody
import com.opendevtools.core.http.encodePathSegment
import com.opendevtools.core.json.JsonWriter
import com.opendevtools.core.json.int
import com.opendevtools.core.json.intOrNull
import com.opendevtools.core.json.str
import com.opendevtools.vaultwarden.crypto.KdfParams
import java.nio.charset.StandardCharsets

data class TokenResponse(val accessToken: String, val expiresInSeconds: Int)

/**
 * The Bitwarden-compatible API exposed by Vaultwarden. Authentication uses a personal API key
 * (`client_credentials`), which keeps working when interactive login goes through an external
 * identity provider such as Keycloak.
 *
 * Never call from the EDT.
 */
class VaultwardenApi(private val config: ApiConfig) {

    private val transport = VaultwardenTransport(config)

    // --- Authentication ---------------------------------------------------------------------

    /** KDF parameters of an account; needed before the master password can be turned into a key. */
    fun prelogin(email: String): KdfParams {
        val payload = JsonWriter.build { put("email", email) }
        val response = try {
            transport.postJson("/identity/accounts/prelogin", payload, null)
        } catch (e: VaultwardenException) {
            // Older Vaultwarden builds expose prelogin under /api.
            transport.postJson("/api/accounts/prelogin", payload, null)
        }
        return KdfParams(
            type = response.int("kdf"),
            iterations = response.intOrNull("kdfIterations") ?: 600_000,
            memoryMib = response.intOrNull("kdfMemory"),
            parallelism = response.intOrNull("kdfParallelism"),
        )
    }

    /** Exchanges the personal API key for an access token. */
    fun requestToken(): TokenResponse {
        val response = transport.postForm(
            "/identity/connect/token",
            mapOf(
                "grant_type" to "client_credentials",
                "scope" to "api",
                "client_id" to config.clientId.trim(),
                "client_secret" to config.clientSecret,
                "deviceType" to VaultwardenTransport.deviceType().toString(),
                "deviceIdentifier" to config.deviceIdentifier,
                "deviceName" to "IntelliJ IDEA",
            ),
        )
        val token = response.str("access_token")
            ?: throw VaultwardenException("The server did not return an access token")
        return TokenResponse(token, response.intOrNull("expires_in") ?: 3600)
    }

    // --- Reads ------------------------------------------------------------------------------

    /** Full vault snapshot: profile with the wrapped keys, folders, collections, ciphers, sends. */
    fun sync(accessToken: String): JsonObject =
        transport.get("/api/sync?excludeDomains=true", accessToken)

    fun cipher(accessToken: String, id: String): JsonObject =
        transport.get("/api/ciphers/" + encodePathSegment(id), accessToken)

    // --- Ciphers ----------------------------------------------------------------------------

    /** Creates a personal item. Organization items go through [createSharedCipher]. */
    fun createCipher(accessToken: String, cipher: JsonObject): JsonObject =
        transport.postJson("/api/ciphers", cipher, accessToken)

    /**
     * Creates an item that belongs to an organization. The server needs the collection ids
     * alongside the cipher, which is why this is a separate endpoint rather than a flag.
     */
    fun createSharedCipher(
        accessToken: String,
        cipher: JsonObject,
        collectionIds: List<String>,
    ): JsonObject = transport.postJson(
        "/api/ciphers/create",
        JsonWriter.build {
            put("cipher", cipher)
            put("collectionIds", JsonWriter.arrayOfStrings(collectionIds))
        },
        accessToken,
    )

    fun updateCipher(accessToken: String, id: String, cipher: JsonObject): JsonObject =
        transport.putJson("/api/ciphers/" + encodePathSegment(id), cipher, accessToken)

    /** Moves an item to the trash; recoverable until it is purged. */
    fun softDeleteCipher(accessToken: String, id: String) {
        transport.putJsonIgnoringResponse(
            "/api/ciphers/" + encodePathSegment(id) + "/delete",
            JsonObject(),
            accessToken,
        )
    }

    fun restoreCipher(accessToken: String, id: String): JsonObject =
        transport.putJson(
            "/api/ciphers/" + encodePathSegment(id) + "/restore",
            JsonObject(),
            accessToken,
        )

    /** Removes an item for good. */
    fun deleteCipher(accessToken: String, id: String) {
        transport.delete("/api/ciphers/" + encodePathSegment(id), accessToken)
    }

    fun moveCiphersToFolder(accessToken: String, ids: List<String>, folderId: String?) {
        transport.putJsonIgnoringResponse(
            "/api/ciphers/move",
            JsonWriter.build {
                put("ids", JsonWriter.arrayOfStrings(ids))
                put("folderId", folderId)
            },
            accessToken,
        )
    }

    // --- Folders ----------------------------------------------------------------------------

    fun createFolder(accessToken: String, encryptedName: String): JsonObject =
        transport.postJson(
            "/api/folders",
            JsonWriter.build { put("name", encryptedName) },
            accessToken,
        )

    fun updateFolder(accessToken: String, id: String, encryptedName: String): JsonObject =
        transport.putJson(
            "/api/folders/" + encodePathSegment(id),
            JsonWriter.build { put("name", encryptedName) },
            accessToken,
        )

    /** Deleting a folder does not delete its items; they fall back to "No Folder". */
    fun deleteFolder(accessToken: String, id: String) {
        transport.delete("/api/folders/" + encodePathSegment(id), accessToken)
    }

    // --- Attachments ------------------------------------------------------------------------

    /**
     * Two-step upload: the server first reserves an attachment and answers with the id and the
     * URL to push the bytes to. Older servers accept the one-shot form instead, which is what
     * the fallback in the service uses.
     */
    fun reserveAttachment(
        accessToken: String,
        cipherId: String,
        fileNameEnc: String,
        fileSize: Long,
        wrappedKey: String,
    ): JsonObject = transport.postJson(
        "/api/ciphers/" + encodePathSegment(cipherId) + "/attachment/v2",
        JsonWriter.build {
            put("fileName", fileNameEnc)
            put("fileSize", fileSize)
            put("key", wrappedKey)
        },
        accessToken,
    )

    fun uploadAttachmentData(
        accessToken: String,
        cipherId: String,
        attachmentId: String,
        fileNameEnc: String,
        encryptedBytes: ByteArray,
    ) {
        val boundary = VaultwardenTransport.newBoundary()
        transport.postRaw(
            "/api/ciphers/" + encodePathSegment(cipherId) +
                "/attachment/" + encodePathSegment(attachmentId),
            multipart(boundary, fileNameEnc, encryptedBytes),
            accessToken,
        )
    }

    fun deleteAttachment(accessToken: String, cipherId: String, attachmentId: String) {
        transport.delete(
            "/api/ciphers/" + encodePathSegment(cipherId) +
                "/attachment/" + encodePathSegment(attachmentId),
            accessToken,
        )
    }

    /** Attachment URLs are absolute and time limited; the sync payload carries the current one. */
    fun downloadAttachment(url: String): ByteArray = transport.download(url)

    fun attachmentInfo(accessToken: String, cipherId: String, attachmentId: String): JsonObject? =
        transport.getOrNull(
            "/api/ciphers/" + encodePathSegment(cipherId) +
                "/attachment/" + encodePathSegment(attachmentId),
            accessToken,
        )

    // --- Sends ------------------------------------------------------------------------------

    fun deleteSend(accessToken: String, id: String) {
        transport.delete("/api/sends/" + encodePathSegment(id), accessToken)
    }

    // --- Multipart --------------------------------------------------------------------------

    /**
     * The API expects a `multipart/form-data` body with a single `data` part. Building it by hand
     * keeps the plugin free of an HTTP client dependency for the sake of one request shape.
     */
    private fun multipart(boundary: String, fileName: String, content: ByteArray): RequestBody {
        val header = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Disposition: form-data; name=\"data\"; filename=\"")
                .append(fileName.replace("\"", "")).append("\"\r\n")
            append("Content-Type: application/octet-stream\r\n\r\n")
        }.toByteArray(StandardCharsets.UTF_8)
        val footer = "\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8)

        return RequestBody.Raw(
            header + content + footer,
            "multipart/form-data; boundary=$boundary",
        )
    }
}
