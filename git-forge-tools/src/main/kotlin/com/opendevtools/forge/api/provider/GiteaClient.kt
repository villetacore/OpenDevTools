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
 * Gitea and Forgejo, which share the `/api/v1` surface - Forgejo is a fork of Gitea and has kept
 * it compatible, so one client serves both.
 *
 * The API is close to GitHub's on purpose, with a few differences that matter here: issues and
 * pull requests are separate collections, the token header is `token <value>` rather than
 * `Bearer`, and CI ("Actions") is only present on newer builds, so its absence is not an error.
 */
class GiteaClient(account: ForgeAccount, token: String) : BaseForgeClient(account, token) {

    override val apiPrefix: String = "/api/v1"

    override fun authHeaders(token: String): Map<String, String> =
        mapOf("Authorization" to "token $token")

    override fun currentUser(): ForgeUser {
        val raw = getObject("/user")
        return ForgeUser(
            login = raw.str("login") ?: throw ForgeException("The token did not identify a user"),
            name = raw.str("full_name"),
            avatarUrl = raw.str("avatar_url"),
            webUrl = raw.str("html_url"),
        )
    }

    override fun repositories(): List<ForgeRepository> =
        getPaged("/user/repos").mapNotNull { toRepository(it) }

    override fun issues(repository: ForgeRepository, state: IssueState): List<ForgeIssue> =
        getPaged(
            "/repos/${repoPath(repository)}/issues",
            mapOf("state" to state.name.lowercase(), "type" to "issues"),
        ).mapNotNull { toIssue(it) }

    override fun pullRequests(repository: ForgeRepository, state: IssueState): List<ForgePullRequest> =
        getPaged(
            "/repos/${repoPath(repository)}/pulls",
            mapOf("state" to state.name.lowercase()),
        ).mapNotNull { toPullRequest(it) }

    /**
     * Actions arrived in Gitea 1.19 and is absent or disabled on plenty of instances, so a 404
     * here means "this server has no CI", not a failure worth reporting.
     */
    override fun pipelines(repository: ForgeRepository): List<ForgePipeline> = runCatching {
        getObject(
            "/repos/${repoPath(repository)}/actions/runs",
            mapOf("limit" to RUNS.toString()),
        ).array("workflow_runs").mapNotNull { raw ->
            val id = raw.str("id") ?: raw.int("id").toString()
            ForgePipeline(
                id = id,
                name = raw.str("display_title") ?: raw.str("name") ?: "run $id",
                status = PipelineStatus.parse(raw.str("conclusion") ?: raw.str("status")),
                branch = raw.str("head_branch"),
                commitSha = raw.str("head_sha")?.take(SHORT_SHA),
                startedAt = raw.str("run_started_at") ?: raw.str("created_at"),
                webUrl = raw.str("html_url").orEmpty(),
            )
        }
    }.getOrDefault(emptyList())

    override fun releases(repository: ForgeRepository): List<ForgeRelease> =
        getPaged("/repos/${repoPath(repository)}/releases").mapNotNull { raw ->
            val tag = raw.str("tag_name") ?: return@mapNotNull null
            ForgeRelease(
                tag = tag,
                name = raw.str("name"),
                body = raw.str("body"),
                prerelease = raw.bool("prerelease"),
                draft = raw.bool("draft"),
                publishedAt = raw.str("published_at") ?: raw.str("created_at"),
                webUrl = raw.str("html_url").orEmpty(),
            )
        }

    override fun branches(repository: ForgeRepository): List<ForgeBranch> =
        getPaged("/repos/${repoPath(repository)}/branches").mapNotNull { raw ->
            val name = raw.str("name") ?: return@mapNotNull null
            ForgeBranch(
                name = name,
                protected = raw.bool("protected"),
                commitSha = raw.obj("commit")?.str("id")?.take(SHORT_SHA),
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
        }
        val created = postJson("/repos/${repoPath(repository)}/pulls", body)
        return toPullRequest(created)
            ?: throw ForgeException("The pull request was created but could not be read back")
    }

    override fun mergePullRequest(repository: ForgeRepository, number: Int, method: MergeMethod) {
        postJson(
            "/repos/${repoPath(repository)}/pulls/$number/merge",
            JsonWriter.build {
                put(
                    "Do",
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
            stars = raw.int("stars_count"),
            updatedAt = raw.str("updated_at"),
            accountId = account.id,
        )
    }

    private fun toIssue(raw: JsonObject): ForgeIssue? {
        val number = raw.int("number").takeIf { it > 0 } ?: return null
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
        val number = raw.int("number").takeIf { it > 0 } ?: return null
        return ForgePullRequest(
            number = number,
            title = raw.str("title").orEmpty(),
            body = raw.str("body"),
            state = if (raw.str("state") == "closed") IssueState.CLOSED else IssueState.OPEN,
            author = raw.obj("user")?.str("login"),
            sourceBranch = raw.obj("head")?.str("ref"),
            targetBranch = raw.obj("base")?.str("ref"),
            draft = raw.bool("draft"),
            merged = raw.bool("merged"),
            mergeable = raw.get("mergeable")?.takeIf { !it.isJsonNull }?.let { raw.bool("mergeable") },
            comments = raw.int("comments"),
            createdAt = raw.str("created_at"),
            updatedAt = raw.str("updated_at"),
            webUrl = raw.str("html_url").orEmpty(),
        )
    }

    private companion object {
        const val RUNS = 30
        const val SHORT_SHA = 8
    }
}
