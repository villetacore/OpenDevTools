package com.opendevtools.forge.api.provider

import com.google.gson.JsonObject
import com.opendevtools.core.http.encodePathSegment
import com.opendevtools.core.json.JsonWriter
import com.opendevtools.core.json.array
import com.opendevtools.core.json.bool
import com.opendevtools.core.json.int
import com.opendevtools.core.json.obj
import com.opendevtools.core.json.str
import com.opendevtools.core.json.stringArray
import com.opendevtools.forge.api.BaseForgeClient
import com.opendevtools.forge.api.ForgeException
import com.opendevtools.forge.model.ForgeAccount
import com.opendevtools.forge.model.ForgeBranch
import com.opendevtools.forge.model.ForgeComment
import com.opendevtools.forge.model.ForgeIssue
import com.opendevtools.forge.model.ForgeLabel
import com.opendevtools.forge.model.ForgePipeline
import com.opendevtools.forge.model.ForgePullRequest
import com.opendevtools.forge.model.ForgeRelease
import com.opendevtools.forge.model.ForgeRepository
import com.opendevtools.forge.model.ForgeUser
import com.opendevtools.forge.model.IssueState
import com.opendevtools.forge.model.MergeMethod
import com.opendevtools.forge.model.NewIssue
import com.opendevtools.forge.model.NewPullRequest
import com.opendevtools.forge.model.PipelineStatus

/**
 * GitLab API v4.
 *
 * Two things differ from the other two dialects and drive most of this class:
 * a project is addressed by its numeric id or by a URL-encoded `group/project` path, and what the
 * others call a pull request is a *merge request* with `iid` as its per-project number.
 */
class GitLabClient(account: ForgeAccount, token: String) : BaseForgeClient(account, token) {

    override val apiPrefix: String = "/api/v4"

    override fun authHeaders(token: String): Map<String, String> = mapOf("PRIVATE-TOKEN" to token)

    override fun currentUser(): ForgeUser {
        val raw = getObject("/user")
        return ForgeUser(
            login = raw.str("username") ?: throw ForgeException("The token did not identify a user"),
            name = raw.str("name"),
            avatarUrl = raw.str("avatar_url"),
            webUrl = raw.str("web_url"),
        )
    }

    override fun repositories(): List<ForgeRepository> =
        getPaged("/projects", mapOf("membership" to "true", "order_by" to "last_activity_at"))
            .mapNotNull { toRepository(it) }

    override fun issues(repository: ForgeRepository, state: IssueState): List<ForgeIssue> =
        getPaged("/projects/${projectId(repository)}/issues", stateQuery(state))
            .mapNotNull { toIssue(it) }

    override fun pullRequests(repository: ForgeRepository, state: IssueState): List<ForgePullRequest> =
        getPaged("/projects/${projectId(repository)}/merge_requests", stateQuery(state))
            .mapNotNull { toMergeRequest(it) }

    override fun pipelines(repository: ForgeRepository): List<ForgePipeline> =
        getArray(
            "/projects/${projectId(repository)}/pipelines",
            mapOf("per_page" to PIPELINES.toString(), "order_by" to "id", "sort" to "desc"),
        ).mapNotNull { raw ->
            val id = raw.str("id") ?: raw.int("id").toString()
            ForgePipeline(
                id = id,
                name = raw.str("name") ?: raw.str("ref")?.let { "pipeline on $it" } ?: "pipeline $id",
                status = PipelineStatus.parse(raw.str("status")),
                branch = raw.str("ref"),
                commitSha = raw.str("sha")?.take(SHORT_SHA),
                startedAt = raw.str("created_at"),
                webUrl = raw.str("web_url").orEmpty(),
            )
        }

    override fun releases(repository: ForgeRepository): List<ForgeRelease> =
        getPaged("/projects/${projectId(repository)}/releases").mapNotNull { raw ->
            val tag = raw.str("tag_name") ?: return@mapNotNull null
            ForgeRelease(
                tag = tag,
                name = raw.str("name"),
                body = raw.str("description"),
                // GitLab has no prerelease or draft flag on a release.
                prerelease = false,
                draft = false,
                publishedAt = raw.str("released_at") ?: raw.str("created_at"),
                webUrl = raw.obj("_links")?.str("self").orEmpty(),
            )
        }

    override fun branches(repository: ForgeRepository): List<ForgeBranch> =
        getPaged("/projects/${projectId(repository)}/repository/branches").mapNotNull { raw ->
            val name = raw.str("name") ?: return@mapNotNull null
            ForgeBranch(
                name = name,
                protected = raw.bool("protected"),
                commitSha = raw.obj("commit")?.str("id")?.take(SHORT_SHA),
                isDefault = raw.bool("default") || name == repository.defaultBranch,
            )
        }

    override fun comments(repository: ForgeRepository, issueNumber: Int): List<ForgeComment> =
        getPaged("/projects/${projectId(repository)}/issues/$issueNumber/notes").mapNotNull { raw ->
            // System notes are GitLab's activity log ("changed the milestone"), not conversation.
            if (raw.bool("system")) return@mapNotNull null
            ForgeComment(
                id = raw.str("id") ?: raw.int("id").toString(),
                author = raw.obj("author")?.str("username"),
                body = raw.str("body").orEmpty(),
                createdAt = raw.str("created_at"),
                webUrl = null,
            )
        }

    override fun createIssue(repository: ForgeRepository, issue: NewIssue): ForgeIssue {
        val body = JsonWriter.build {
            put("title", issue.title)
            issue.body?.let { put("description", it) }
            if (issue.labels.isNotEmpty()) put("labels", issue.labels.joinToString(","))
        }
        val created = postJson("/projects/${projectId(repository)}/issues", body)
        return toIssue(created) ?: throw ForgeException("The issue was created but could not be read back")
    }

    override fun comment(repository: ForgeRepository, issueNumber: Int, body: String) {
        postJson(
            "/projects/${projectId(repository)}/issues/$issueNumber/notes",
            JsonWriter.build { put("body", body) },
        )
    }

    override fun setIssueState(repository: ForgeRepository, issueNumber: Int, open: Boolean) {
        putJson(
            "/projects/${projectId(repository)}/issues/$issueNumber",
            JsonWriter.build { put("state_event", if (open) "reopen" else "close") },
        )
    }

    override fun createPullRequest(
        repository: ForgeRepository,
        request: NewPullRequest,
    ): ForgePullRequest {
        val body = JsonWriter.build {
            // GitLab marks a draft by the title prefix rather than by a flag.
            put("title", if (request.draft) "Draft: ${request.title}" else request.title)
            put("source_branch", request.sourceBranch)
            put("target_branch", request.targetBranch)
            request.body?.let { put("description", it) }
        }
        val created = postJson("/projects/${projectId(repository)}/merge_requests", body)
        return toMergeRequest(created)
            ?: throw ForgeException("The merge request was created but could not be read back")
    }

    override fun mergePullRequest(repository: ForgeRepository, number: Int, method: MergeMethod) {
        putJson(
            "/projects/${projectId(repository)}/merge_requests/$number/merge",
            JsonWriter.build {
                if (method == MergeMethod.SQUASH) put("squash", true)
            },
        )
    }

    /** GitLab merges or squashes; a rebase is a separate endpoint, so it is not offered here. */
    override fun supportedMergeMethods(): List<MergeMethod> =
        listOf(MergeMethod.MERGE, MergeMethod.SQUASH)

    /**
     * A project is addressed either by numeric id or by its URL-encoded full path. The id is
     * cheaper and unambiguous, so it is preferred and the path is the fallback.
     */
    private fun projectId(repository: ForgeRepository): String =
        repository.id.takeIf { it.toIntOrNull() != null }
            ?: encodePathSegment(repository.fullName).replace("/", "%2F")

    private fun stateQuery(state: IssueState): Map<String, String> = when (state) {
        IssueState.OPEN -> mapOf("state" to "opened", "order_by" to "updated_at")
        IssueState.CLOSED -> mapOf("state" to "closed", "order_by" to "updated_at")
        IssueState.ALL -> mapOf("order_by" to "updated_at")
    }

    private fun toRepository(raw: JsonObject): ForgeRepository? {
        val path = raw.str("path_with_namespace") ?: return null
        val namespace = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        return ForgeRepository(
            id = raw.str("id") ?: raw.int("id").toString(),
            owner = namespace,
            name = name,
            description = raw.str("description"),
            defaultBranch = raw.str("default_branch"),
            isPrivate = raw.str("visibility") != "public",
            isFork = raw.obj("forked_from_project") != null,
            webUrl = raw.str("web_url").orEmpty(),
            cloneUrl = raw.str("http_url_to_repo"),
            sshUrl = raw.str("ssh_url_to_repo"),
            openIssues = raw.int("open_issues_count"),
            stars = raw.int("star_count"),
            updatedAt = raw.str("last_activity_at"),
            accountId = account.id,
        )
    }

    private fun toIssue(raw: JsonObject): ForgeIssue? {
        // `iid` is the number a person sees; `id` is global across the instance.
        val number = raw.int("iid").takeIf { it > 0 } ?: return null
        return ForgeIssue(
            number = number,
            title = raw.str("title").orEmpty(),
            body = raw.str("description"),
            state = if (raw.str("state") == "closed") IssueState.CLOSED else IssueState.OPEN,
            author = raw.obj("author")?.str("username"),
            assignees = raw.array("assignees").mapNotNull { it.str("username") },
            labels = raw.stringArray("labels").map { ForgeLabel(it, null) },
            comments = raw.int("user_notes_count"),
            createdAt = raw.str("created_at"),
            updatedAt = raw.str("updated_at"),
            webUrl = raw.str("web_url").orEmpty(),
        )
    }

    private fun toMergeRequest(raw: JsonObject): ForgePullRequest? {
        val number = raw.int("iid").takeIf { it > 0 } ?: return null
        val state = raw.str("state")
        return ForgePullRequest(
            number = number,
            title = raw.str("title").orEmpty(),
            body = raw.str("description"),
            state = if (state == "opened") IssueState.OPEN else IssueState.CLOSED,
            author = raw.obj("author")?.str("username"),
            sourceBranch = raw.str("source_branch"),
            targetBranch = raw.str("target_branch"),
            draft = raw.bool("draft") || raw.bool("work_in_progress"),
            merged = state == "merged",
            // `merge_status` is "can_be_merged", "cannot_be_merged" or "unchecked".
            mergeable = raw.str("merge_status")?.let { it == "can_be_merged" },
            comments = raw.int("user_notes_count"),
            createdAt = raw.str("created_at"),
            updatedAt = raw.str("updated_at"),
            webUrl = raw.str("web_url").orEmpty(),
        )
    }

    private companion object {
        const val PIPELINES = 30
        const val SHORT_SHA = 8
    }
}
