package com.opendevtools.forge.api

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.opendevtools.core.http.HttpMethod
import com.opendevtools.core.http.HttpRequest
import com.opendevtools.core.http.HttpTransport
import com.opendevtools.core.http.HttpTransportException
import com.opendevtools.core.http.RequestBody
import com.opendevtools.core.json.JsonAccess
import com.opendevtools.core.json.str
import com.opendevtools.forge.model.ForgeAccount
import com.opendevtools.forge.model.ForgeBranch
import com.opendevtools.forge.model.ForgeComment
import com.opendevtools.forge.model.ForgeIssue
import com.opendevtools.forge.model.ForgePipeline
import com.opendevtools.forge.model.ForgePullRequest
import com.opendevtools.forge.model.ForgeRelease
import com.opendevtools.forge.model.ForgeRepository
import com.opendevtools.forge.model.ForgeUser
import com.opendevtools.forge.model.IssueState
import com.opendevtools.forge.model.MergeMethod
import com.opendevtools.forge.model.NewIssue
import com.opendevtools.forge.model.NewPullRequest

/** A failure from a forge, with the status code kept so the UI can say something useful. */
class ForgeException(
    message: String,
    cause: Throwable? = null,
    val statusCode: Int? = null,
) : RuntimeException(message, cause) {

    val isUnauthorized: Boolean get() = statusCode == 401
    val isForbidden: Boolean get() = statusCode == 403
    val isNotFound: Boolean get() = statusCode == 404
}

/**
 * What every forge must offer the plugin.
 *
 * The three dialects - GitHub, GitLab and Gitea/Forgejo - disagree on paths, on parameter names
 * and on what they call things, but they agree on the concepts. This interface is those concepts;
 * each implementation is the translation for one dialect.
 */
interface ForgeClient {

    val account: ForgeAccount

    /** Who the token belongs to; the connection test and the settings label both use it. */
    fun currentUser(): ForgeUser

    fun repositories(): List<ForgeRepository>

    fun issues(repository: ForgeRepository, state: IssueState): List<ForgeIssue>

    fun pullRequests(repository: ForgeRepository, state: IssueState): List<ForgePullRequest>

    fun pipelines(repository: ForgeRepository): List<ForgePipeline>

    fun releases(repository: ForgeRepository): List<ForgeRelease>

    fun branches(repository: ForgeRepository): List<ForgeBranch>

    fun comments(repository: ForgeRepository, issueNumber: Int): List<ForgeComment>

    fun createIssue(repository: ForgeRepository, issue: NewIssue): ForgeIssue

    fun comment(repository: ForgeRepository, issueNumber: Int, body: String)

    fun setIssueState(repository: ForgeRepository, issueNumber: Int, open: Boolean)

    fun createPullRequest(repository: ForgeRepository, request: NewPullRequest): ForgePullRequest

    fun mergePullRequest(repository: ForgeRepository, number: Int, method: MergeMethod)

    /** Which merge methods this forge accepts, so the dialog only offers real choices. */
    fun supportedMergeMethods(): List<MergeMethod> = MergeMethod.entries
}

/**
 * Shared HTTP plumbing. Subclasses supply the auth header and the error shape; everything else -
 * pagination, JSON parsing, error wrapping - is the same for all three.
 */
abstract class BaseForgeClient(
    final override val account: ForgeAccount,
    private val token: String,
) : ForgeClient {

    private val http = HttpTransport(account.normalizedBaseUrl, account.verifySsl)

    /** The header the forge expects; `Authorization: Bearer` for some, `PRIVATE-TOKEN` for GitLab. */
    protected abstract fun authHeaders(token: String): Map<String, String>

    /** Prefix under the base URL, such as `/api/v4`. */
    protected abstract val apiPrefix: String

    protected fun getObject(path: String, query: Map<String, String> = emptyMap()): JsonObject =
        JsonAccess.parseObject(execute(HttpMethod.GET, path, query, null))
            ?: throw ForgeException("Unexpected response from $path")

    protected fun getArray(path: String, query: Map<String, String> = emptyMap()): List<JsonObject> =
        (JsonAccess.parse(execute(HttpMethod.GET, path, query, null)) as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()

    /**
     * Follows the page parameter until a short page arrives. Link-header parsing would be more
     * precise on GitHub, but all three forges honour `page`/`per_page`, so one loop covers them.
     */
    protected fun getPaged(
        path: String,
        query: Map<String, String> = emptyMap(),
        pageSize: Int = PAGE_SIZE,
        maxPages: Int = MAX_PAGES,
    ): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        var page = 1
        while (page <= maxPages) {
            val batch = getArray(path, query + mapOf("per_page" to pageSize.toString(), "page" to page.toString()))
            result += batch
            if (batch.size < pageSize) break
            page++
        }
        return result
    }

    protected fun postJson(path: String, body: JsonObject): JsonObject =
        JsonAccess.parseObject(execute(HttpMethod.POST, path, emptyMap(), body))
            ?: JsonObject()

    protected fun putJson(path: String, body: JsonObject): JsonObject =
        JsonAccess.parseObject(execute(HttpMethod.PUT, path, emptyMap(), body)) ?: JsonObject()

    protected fun patchJson(path: String, body: JsonObject): JsonObject =
        JsonAccess.parseObject(execute(HttpMethod.PATCH, path, emptyMap(), body)) ?: JsonObject()

    private fun execute(
        method: HttpMethod,
        path: String,
        query: Map<String, String>,
        body: JsonObject?,
    ): String {
        val response = try {
            http.execute(
                HttpRequest(
                    method,
                    apiPrefix + path,
                    query = query,
                    headers = authHeaders(token) + mapOf("Accept" to "application/json"),
                    body = body?.let { RequestBody.Json(it.toString()) },
                ),
            )
        } catch (e: HttpTransportException) {
            throw ForgeException(e.message ?: "Request failed", e)
        }
        if (!response.isSuccess) {
            throw ForgeException(describeError(response.code, response.body), statusCode = response.code)
        }
        return response.body
    }

    /**
     * The three forges use different error shapes: GitHub `message`, GitLab `message` or `error`,
     * Gitea `message`. Trying each in turn beats printing a status code.
     */
    private fun describeError(code: Int, body: String): String {
        val root = JsonAccess.parseObject(body)
        val detail = root?.str("message")
            ?: root?.str("error")
            ?: root?.str("error_description")
            // GitLab validation errors arrive as { message: { field: [reasons] } }.
            ?: root?.get("message")?.takeIf { it.isJsonObject }?.toString()
        return when {
            !detail.isNullOrBlank() -> "HTTP $code - $detail"
            body.isNotBlank() -> "HTTP $code - " + body.take(MAX_ERROR_BODY)
            else -> "HTTP $code"
        }
    }

    companion object {
        const val PAGE_SIZE = 50

        /** A hard stop, so a misconfigured account cannot walk an unbounded repository list. */
        const val MAX_PAGES = 10
        private const val MAX_ERROR_BODY = 300
    }
}
