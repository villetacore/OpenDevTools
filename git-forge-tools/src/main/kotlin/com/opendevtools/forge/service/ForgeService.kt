package com.opendevtools.forge.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.opendevtools.forge.api.ForgeClient
import com.opendevtools.forge.api.ForgeException
import com.opendevtools.forge.api.provider.GitHubClient
import com.opendevtools.forge.api.provider.GitLabClient
import com.opendevtools.forge.api.provider.GiteaClient
import com.opendevtools.forge.model.ForgeAccount
import com.opendevtools.forge.model.ForgeKind
import com.opendevtools.forge.model.ForgeRepository
import com.opendevtools.forge.model.ForgeUser
import com.opendevtools.forge.model.IssueState
import com.opendevtools.forge.model.RepositoryDetails
import com.opendevtools.forge.settings.ForgeCredentials
import com.opendevtools.forge.settings.ForgeSettings

/** Everything one refresh of the tool window loads: the repositories of every account. */
data class ForgeSnapshot(
    val repositoriesByAccount: Map<String, List<ForgeRepository>>,
    val failures: Map<String, String>,
    val loadedAt: Long = System.currentTimeMillis(),
) {
    val allRepositories: List<ForgeRepository> get() = repositoriesByAccount.values.flatten()

    companion object {
        val EMPTY = ForgeSnapshot(emptyMap(), emptyMap())
    }
}

/**
 * Builds clients from the configured accounts and loads what the UI shows.
 *
 * All methods block and must be called from a background thread.
 */
@Service(Service.Level.APP)
class ForgeService {

    @Volatile
    private var cached: ForgeSnapshot? = null

    fun invalidate() {
        cached = null
    }

    /** Builds the client for one account, picking the dialect from its kind. */
    fun client(account: ForgeAccount): ForgeClient {
        val token = ForgeCredentials.get(account.credentialKey)
        if (token.isNullOrBlank()) {
            throw ForgeException("No token stored for ${account.label}; add one in the settings")
        }
        return clientFor(account, token)
    }

    /** Used by the settings dialog, where the token is on screen but not yet saved. */
    fun clientFor(account: ForgeAccount, token: String): ForgeClient = when {
        account.kind.isGitHubFamily -> GitHubClient(account, token)
        account.kind == ForgeKind.GITLAB -> GitLabClient(account, token)
        account.kind.isGiteaFamily -> GiteaClient(account, token)
        else -> throw ForgeException("Unsupported forge kind ${account.kind}")
    }

    /**
     * Loads the repositories of every account. One account failing - a bad token, a server that is
     * down - must not hide the others, so failures are collected alongside the results.
     */
    fun loadAll(): ForgeSnapshot {
        val settings = ForgeSettings.getInstance()
        val repositories = LinkedHashMap<String, List<ForgeRepository>>()
        val failures = LinkedHashMap<String, String>()

        for (account in settings.accountList()) {
            try {
                repositories[account.id] = client(account).repositories()
                    .filter { settings.showForks || !it.isFork }
                    .filter { settings.showPrivate || !it.isPrivate }
                    .sortedBy { it.fullName.lowercase() }
            } catch (e: Throwable) {
                failures[account.id] = e.message ?: e.toString()
            }
        }
        return ForgeSnapshot(repositories, failures).also { cached = it }
    }

    /**
     * Loads everything one repository view needs. Each section is wrapped: a repository with
     * issues disabled, or a server without CI, should still show the sections it does have.
     */
    fun loadDetails(repository: ForgeRepository, state: IssueState): RepositoryDetails {
        val account = ForgeSettings.getInstance().account(repository.accountId)
            ?: throw ForgeException("The account for ${repository.fullName} is gone")
        val client = client(account)

        return RepositoryDetails(
            repository = repository,
            issues = runCatching { client.issues(repository, state) }.getOrDefault(emptyList()),
            pullRequests = runCatching { client.pullRequests(repository, state) }
                .getOrDefault(emptyList()),
            pipelines = runCatching { client.pipelines(repository) }.getOrDefault(emptyList()),
            releases = runCatching { client.releases(repository) }.getOrDefault(emptyList()),
            branches = runCatching { client.branches(repository) }.getOrDefault(emptyList()),
        )
    }

    fun clientFor(repository: ForgeRepository): ForgeClient {
        val account = ForgeSettings.getInstance().account(repository.accountId)
            ?: throw ForgeException("The account for ${repository.fullName} is gone")
        return client(account)
    }

    /** A one-line summary for the connection test in the settings. */
    fun describeConnection(account: ForgeAccount, token: String): String {
        val client = clientFor(account, token)
        val user = client.currentUser()
        val repositories = runCatching { client.repositories() }.getOrDefault(emptyList())
        return "Connected to ${account.kind.title} as ${user.login}\n" +
            "${repositories.size} repository(ies) visible"
    }

    companion object {
        fun getInstance(): ForgeService =
            ApplicationManager.getApplication().getService(ForgeService::class.java)
    }
}
