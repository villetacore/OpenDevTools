package com.opendevtools.forge.api.provider

import com.google.gson.JsonObject
import com.opendevtools.core.http.encodePathSegment
import com.opendevtools.core.json.JsonWriter
import com.opendevtools.core.json.array
import com.opendevtools.core.json.bool
import com.opendevtools.core.json.int
import com.opendevtools.core.json.obj
import com.opendevtools.core.json.str
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
 * GitHub REST v3, which GitHub Enterprise serves under `/api/v3` on its own host.
 *
 * The one shape to watch: GitHub returns pull requests through `/issues` as well, so the issue
 * list has to drop anything carrying a `pull_request` key or every PR shows up twice.
 */
class GitHubClient(account: ForgeAccount, token: String) : BaseForgeClient(account, token) {

    override val apiPrefix: String = if (account.kind.apiPathHint == "/") "" else account.kind.apiPathHint

    override fun authHeaders(token: String): Map<String, String> = mapOf(
        "Authorization" to "Bearer $token",
        "X-GitHub-Api-Version" to API_VERSION,
        "Accept" to "application/vnd.github+json",
    )

    override fun currentUser(): ForgeUser {
        val raw = getObject("/user")
        return ForgeUser(
            login = raw.str("login") ?: throw ForgeException("The token did not identify a user"),
            name = raw.str("name"),
            avatarUrl = raw.str("avatar_url"),
            webUrl = raw.str("html_url"),
        )
    }

    override fun repositories(): List<ForgeRepository> =
        getPaged("/user/repos", mapOf("sort" to "updated", "affiliation" to "owner,collaborator,organization_member"))
            .mapNotNull { toRepository(it) }

    override fun issues(repository: ForgeRepository, state: IssueState): List<ForgeIssue> =
        getPaged(
            "/repos/${repoPath(repository)}/issues",
            mapOf("state" to state.name.lowercase(), "sort" to "updated"),
        )
            // A pull request is also an issue here; without this filter every PR is listed twice.
            .filterNot { it.has("pull_request") }
            .mapNotNull { toIssue(it) }

    override fun pullRequests(repository: ForgeRepository, state: IssueState): List<ForgePullRequest> =
        getPaged(
            "/repos/${repoPath(repository)}/pulls",
            mapOf("state" to state.name.lowercase(), "sort" to "updated", "direction" to "desc"),
        ).mapNotNull { toPullRequest(it) }

    override fun pipelines(repository: ForgeRepository): List<ForgePipeline> =
        getObject("/repos/${repoPath(repository)}/actions/runs", mapOf("per_page" to RUNS.toString()))
            .array("workflow_runs")
            .mapNotNull { raw ->
                val id = raw.str("id") ?: raw.int("id").toString()
                ForgePipeline(
                    id = id,
                    name = raw.str("name") ?: raw.str("display_title") ?: "run $id",
                    // A finished run reports its outcome in `conclusion`; a live one in `status`.
                    status = PipelineStatus.parse(raw.str("conclusion") ?: raw.str("status")),
                    branch = raw.str("head_branch"),
                    commitSha = raw.str("head_sha")?.take(SHORT_SHA),
                    startedAt = raw.str("run_started_at") ?: raw.str("created_at"),
                    webUrl = raw.str("html_url").orEmpty(),
                )
            }

    override fun releases(repository: ForgeRepository): List<ForgeRelease> =
        getPaged("/repos/${repoPath(repository)}/releases").mapNotNull { raw ->
            val tag = raw.str("tag_name") ?: return@mapNotNull null
            ForgeRelease(
                tag = tag,
                name = raw.str("name"),
                body = raw.str("body"),
                prerelease = raw.bool("prerelease"),
                draft = raw.bool("draft"),
                publishedAt = raw.str("published_at"),
                webUrl = raw.str("html_url").orEmpty(),
            )
        }

    override fun branches(repository: ForgeRepository): List<ForgeBranch> =
        getPaged("/repos/${repoPath(repository)}/branches").mapNotNull { raw ->
            val name = raw.str("name") ?: return@mapNotNull null
            ForgeBranch(
                name = name,
                protected = raw.bool("protected"),
                commitSha = raw.obj("commit")?.str("sha")?.take(SHORT_SHA),
                isDefault = name == repository.defaultBranch,
            )
        }

    override fun comments(repository: ForgeRepository, issueNumber: Int): List<ForgeComment> =
        getPaged("/repos/${repoPath(repository)}/issues/$issueNumber/comments").mapNotNull { raw ->
            ForgeComment(
                id = raw.str("id") ?: raw.int("id").toString(),
                author = raw.obj("user")?.str("login"),
                body = raw.str("body").orEmpty(),
                createdAt = raw.str("created_at"),
                webUrl = raw.str("html_url"),
            )
        }

    override fun createIssue(repository: ForgeRepository, issue: NewIssue): ForgeIssue {
        val body = JsonWriter.build {
            put("title", issue.title)
            issue.body?.let { put("body", it) }
            if (issue.labels.isNotEmpty()) put("labels", JsonWriter.arrayOfStrings(issue.labels))
        }
        val created = postJson("/repos/${repoPath(repository)}/issues", body)
        return toIssue(created) ?: throw ForgeException("The issue was created but could not be read back")
    }

    override fun comment(repository: ForgeRepository, issueNumber: Int, body: String) {
        postJson(
            "/repos/${repoPath(repository)}/issues/$issueNumber/comments",
            JsonWriter.build { put("body", body) },
        )
    }

    override fun setIssueState(repository: ForgeRepository, issueNumber: Int, open: Boolean) {
        patchJson(
            "/repos/${repoPath(repository)}/issues/$issueNumber",
            JsonWriter.build { put("state", if (open) "open" else "closed") },
        )
    }

    override fun createPullRequest(
        repository: ForgeRepository,
        request: NewPullRequest,
    ): ForgePullRequest {
        val body = JsonWriter.build {
            put("title", request.title)
            put("head", request.sourceBranch)
            put("base", request.targetBranch)
            request.body?.let { put("body", it) }
            put("draft", request.draft)
        }
        val created = postJson("/repos/${repoPath(repository)}/pulls", body)
        return toPullRequest(created)
            ?: throw ForgeException("The pull request was created but could not be read back")
    }

    override fun mergePullRequest(repository: ForgeRepository, number: Int, method: MergeMethod) {
        putJson(
            "/repos/${repoPath(repository)}/pulls/$number/merge",
            JsonWriter.build {
                put(
                    "merge_method",
                    when (method) {
                        MergeMethod.MERGE -> "merge"
                        MergeMethod.SQUASH -> "squash"
                        MergeMethod.REBASE -> "rebase"
                    },
                )
            },
        )
    }

    private fun repoPath(repository: ForgeRepository): String =
        encodePathSegment(repository.owner) + "/" + encodePathSegment(repository.name)

    private fun toRepository(raw: JsonObject): ForgeRepository? {
        val name = raw.str("name") ?: return null
        val owner = raw.obj("owner")?.str("login") ?: return null
        return ForgeRepository(
            id = raw.str("id") ?: raw.int("id").toString(),
            owner = owner,
            name = name,
            description = raw.str("description"),
            defaultBranch = raw.str("default_branch"),
            isPrivate = raw.bool("private"),
            isFork = raw.bool("fork"),
            webUrl = raw.str("html_url").orEmpty(),
            cloneUrl = raw.str("clone_url"),
            sshUrl = raw.str("ssh_url"),
            openIssues = raw.int("open_issues_count"),
            stars = raw.int("stargazers_count"),
            updatedAt = raw.str("updated_at"),
            accountId = account.id,
        )
    }

    private fun toIssue(raw: JsonObject): ForgeIssue? {
        val number = raw.str("number")?.toIntOrNull() ?: raw.int("number").takeIf { it > 0 } ?: return null
        return ForgeIssue(
            number = number,
            title = raw.str("title").orEmpty(),
            body = raw.str("body"),
            state = if (raw.str("state") == "closed") IssueState.CLOSED else IssueState.OPEN,
            author = raw.obj("user")?.str("login"),
            assignees = raw.array("assignees").mapNotNull { it.str("login") },
            labels = raw.array("labels").mapNotNull { label ->
                label.str("name")?.let { ForgeLabel(it, label.str("color")) }
            },
            comments = raw.int("comments"),
            createdAt = raw.str("created_at"),
            updatedAt = raw.str("updated_at"),
            webUrl = raw.str("html_url").orEmpty(),
        )
    }

    private fun toPullRequest(raw: JsonObject): ForgePullRequest? {
        val number = raw.str("number")?.toIntOrNull() ?: raw.int("number").takeIf { it > 0 } ?: return null
        return ForgePullRequest(
            number = number,
            title = raw.str("title").orEmpty(),
            body = raw.str("body"),
            state = if (raw.str("state") == "closed") IssueState.CLOSED else IssueState.OPEN,
            author = raw.obj("user")?.str("login"),
            sourceBranch = raw.obj("head")?.str("ref"),
            targetBranch = raw.obj("base")?.str("ref"),
            draft = raw.bool("draft"),
            merged = raw.bool("merged") || raw.str("merged_at") != null,
            // `mergeable` is computed lazily and is null until GitHub has worked it out.
            mergeable = raw.get("mergeable")?.takeIf { !it.isJsonNull }?.let { raw.bool("mergeable") },
            comments = raw.int("comments"),
            createdAt = raw.str("created_at"),
            updatedAt = raw.str("updated_at"),
            webUrl = raw.str("html_url").orEmpty(),
        )
    }

    private companion object {
        const val API_VERSION = "2022-11-28"
        const val RUNS = 30
        const val SHORT_SHA = 8
    }
}
