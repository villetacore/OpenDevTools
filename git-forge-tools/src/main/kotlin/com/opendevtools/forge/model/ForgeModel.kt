package com.opendevtools.forge.model

/**
 * The forge families the plugin speaks. Forgejo is a Gitea fork and its API is the same `/api/v1`,
 * so the two share a client and differ only in what the settings call them.
 */
enum class ForgeKind(
    val title: String,
    val defaultBaseUrl: String,
    val tokenHint: String,
    val apiPathHint: String,
) {
    GITHUB(
        "GitHub",
        "https://api.github.com",
        "A personal access token: Settings | Developer settings | Personal access tokens",
        "/",
    ),
    GITHUB_ENTERPRISE(
        "GitHub Enterprise",
        "",
        "A personal access token on your GitHub Enterprise server",
        "/api/v3",
    ),
    GITLAB(
        "GitLab",
        "https://gitlab.com",
        "A personal access token with the api scope: Preferences | Access tokens",
        "/api/v4",
    ),
    GITEA(
        "Gitea",
        "",
        "An access token: Settings | Applications | Generate new token",
        "/api/v1",
    ),
    FORGEJO(
        "Forgejo",
        "",
        "An access token: Settings | Applications | Generate new token",
        "/api/v1",
    );

    /** Gitea and Forgejo share one API dialect, and so one client implementation. */
    val isGiteaFamily: Boolean get() = this == GITEA || this == FORGEJO

    val isGitHubFamily: Boolean get() = this == GITHUB || this == GITHUB_ENTERPRISE

    /** A self-hosted instance has no sensible default host to pre-fill. */
    val isSelfHosted: Boolean get() = defaultBaseUrl.isEmpty()
}

/**
 * One configured connection to a forge. Several accounts can point at the same kind - two GitLab
 * instances, or a work and a personal GitHub - so the id is what identifies it, not the kind.
 */
data class ForgeAccount(
    val id: String,
    val kind: ForgeKind,
    val displayName: String,
    val baseUrl: String,
    val verifySsl: Boolean = true,
    /** Filled after a successful connection test; purely informational. */
    val username: String? = null,
) {
    val key: String get() = "account:$id"

    /** Where the secret for this account lives in the password safe. */
    val credentialKey: String get() = "$id|$baseUrl"

    val normalizedBaseUrl: String get() = baseUrl.trim().trimEnd('/')

    val label: String
        get() = displayName.ifBlank { kind.title } +
            username?.let { " ($it)" }.orEmpty()
}

data class ForgeUser(val login: String, val name: String?, val avatarUrl: String?, val webUrl: String?)

data class ForgeRepository(
    val id: String,
    val owner: String,
    val name: String,
    val description: String?,
    val defaultBranch: String?,
    val isPrivate: Boolean,
    val isFork: Boolean,
    val webUrl: String,
    val cloneUrl: String?,
    val sshUrl: String?,
    val openIssues: Int,
    val stars: Int,
    val updatedAt: String?,
    val accountId: String,
) {
    val fullName: String get() = "$owner/$name"
    val key: String get() = "repo:$accountId:$fullName"

    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        val needle = query.trim().lowercase()
        return fullName.lowercase().contains(needle) ||
            description?.lowercase()?.contains(needle) == true
    }
}

enum class IssueState(val title: String) { OPEN("Open"), CLOSED("Closed"), ALL("All") }

data class ForgeLabel(val name: String, val color: String?)

data class ForgeIssue(
    val number: Int,
    val title: String,
    val body: String?,
    val state: IssueState,
    val author: String?,
    val assignees: List<String>,
    val labels: List<ForgeLabel>,
    val comments: Int,
    val createdAt: String?,
    val updatedAt: String?,
    val webUrl: String,
) {
    val key: String get() = "issue:$number"
}

/** GitHub and Gitea call it a pull request, GitLab a merge request; the model is the same. */
data class ForgePullRequest(
    val number: Int,
    val title: String,
    val body: String?,
    val state: IssueState,
    val author: String?,
    val sourceBranch: String?,
    val targetBranch: String?,
    val draft: Boolean,
    val merged: Boolean,
    val mergeable: Boolean?,
    val comments: Int,
    val createdAt: String?,
    val updatedAt: String?,
    val webUrl: String,
) {
    val key: String get() = "pr:$number"

    val statusLabel: String
        get() = when {
            merged -> "merged"
            draft -> "draft"
            state == IssueState.CLOSED -> "closed"
            mergeable == false -> "conflicts"
            else -> "open"
        }
}

/** A CI run: a GitHub Actions workflow run, a GitLab pipeline or a Gitea Actions run. */
data class ForgePipeline(
    val id: String,
    val name: String,
    val status: PipelineStatus,
    val branch: String?,
    val commitSha: String?,
    val startedAt: String?,
    val webUrl: String,
) {
    val key: String get() = "pipeline:$id"
}

enum class PipelineStatus(val title: String) {
    SUCCESS("Success"),
    FAILED("Failed"),
    RUNNING("Running"),
    PENDING("Pending"),
    CANCELLED("Cancelled"),
    SKIPPED("Skipped"),
    UNKNOWN("Unknown");

    val isFinished: Boolean get() = this != RUNNING && this != PENDING

    companion object {
        /**
         * Each forge spells its states differently; this is the union of what the three send,
         * mapped onto the six the UI distinguishes.
         */
        fun parse(value: String?): PipelineStatus = when (value?.lowercase()) {
            "success", "completed", "passed" -> SUCCESS
            "failure", "failed", "error", "timed_out" -> FAILED
            "in_progress", "running", "queued", "waiting_for_resource", "preparing" -> RUNNING
            "pending", "created", "scheduled", "manual", "requested", "waiting" -> PENDING
            "cancelled", "canceled" -> CANCELLED
            "skipped", "neutral", "manual_skipped" -> SKIPPED
            else -> UNKNOWN
        }
    }
}

data class ForgeRelease(
    val tag: String,
    val name: String?,
    val body: String?,
    val prerelease: Boolean,
    val draft: Boolean,
    val publishedAt: String?,
    val webUrl: String,
) {
    val key: String get() = "release:$tag"
}

data class ForgeBranch(
    val name: String,
    val protected: Boolean,
    val commitSha: String?,
    val isDefault: Boolean,
) {
    val key: String get() = "branch:$name"
}

data class ForgeComment(
    val id: String,
    val author: String?,
    val body: String,
    val createdAt: String?,
    val webUrl: String?,
)

/** What the "new issue" dialog produces. */
data class NewIssue(val title: String, val body: String?, val labels: List<String>)

/** What the "new pull request" dialog produces. */
data class NewPullRequest(
    val title: String,
    val body: String?,
    val sourceBranch: String,
    val targetBranch: String,
    val draft: Boolean,
)

/** How a pull request is merged; not every forge supports every method. */
enum class MergeMethod(val title: String) {
    MERGE("Create a merge commit"),
    SQUASH("Squash and merge"),
    REBASE("Rebase and merge"),
}

/** Everything one repository view needs, loaded together so the tabs do not each hit the API. */
data class RepositoryDetails(
    val repository: ForgeRepository,
    val issues: List<ForgeIssue> = emptyList(),
    val pullRequests: List<ForgePullRequest> = emptyList(),
    val pipelines: List<ForgePipeline> = emptyList(),
    val releases: List<ForgeRelease> = emptyList(),
    val branches: List<ForgeBranch> = emptyList(),
)
