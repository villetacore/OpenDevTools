package com.opendevtools.forge.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The three forges spell their CI states differently, so the mapping onto the six the UI shows is
 * the one place where a typo silently turns a red pipeline green.
 */
class PipelineStatusTest {

    @Test
    fun `GitHub Actions conclusions map onto the shared states`() {
        assertEquals(PipelineStatus.SUCCESS, PipelineStatus.parse("success"))
        assertEquals(PipelineStatus.FAILED, PipelineStatus.parse("failure"))
        assertEquals(PipelineStatus.FAILED, PipelineStatus.parse("timed_out"))
        assertEquals(PipelineStatus.CANCELLED, PipelineStatus.parse("cancelled"))
        assertEquals(PipelineStatus.SKIPPED, PipelineStatus.parse("neutral"))
        assertEquals(PipelineStatus.RUNNING, PipelineStatus.parse("in_progress"))
        assertEquals(PipelineStatus.RUNNING, PipelineStatus.parse("queued"))
    }

    @Test
    fun `GitLab pipeline states map onto the shared states`() {
        assertEquals(PipelineStatus.SUCCESS, PipelineStatus.parse("success"))
        assertEquals(PipelineStatus.FAILED, PipelineStatus.parse("failed"))
        assertEquals(PipelineStatus.RUNNING, PipelineStatus.parse("running"))
        assertEquals(PipelineStatus.RUNNING, PipelineStatus.parse("preparing"))
        assertEquals(PipelineStatus.PENDING, PipelineStatus.parse("created"))
        assertEquals(PipelineStatus.PENDING, PipelineStatus.parse("manual"))
        // GitLab spells it with one l.
        assertEquals(PipelineStatus.CANCELLED, PipelineStatus.parse("canceled"))
        assertEquals(PipelineStatus.SKIPPED, PipelineStatus.parse("skipped"))
    }

    @Test
    fun `an unknown or absent state is not mistaken for success`() {
        assertEquals(PipelineStatus.UNKNOWN, PipelineStatus.parse("something-new"))
        assertEquals(PipelineStatus.UNKNOWN, PipelineStatus.parse(null))
    }

    @Test
    fun `state is case insensitive`() {
        assertEquals(PipelineStatus.SUCCESS, PipelineStatus.parse("SUCCESS"))
        assertEquals(PipelineStatus.FAILED, PipelineStatus.parse("Failed"))
    }

    @Test
    fun `only the live states count as unfinished`() {
        assertFalse(PipelineStatus.RUNNING.isFinished)
        assertFalse(PipelineStatus.PENDING.isFinished)
        assertTrue(PipelineStatus.SUCCESS.isFinished)
        assertTrue(PipelineStatus.FAILED.isFinished)
        assertTrue(PipelineStatus.UNKNOWN.isFinished)
    }
}

class ForgeKindTest {

    @Test
    fun `Gitea and Forgejo are one API family`() {
        assertTrue(ForgeKind.GITEA.isGiteaFamily)
        assertTrue(ForgeKind.FORGEJO.isGiteaFamily)
        assertFalse(ForgeKind.GITLAB.isGiteaFamily)
        assertFalse(ForgeKind.GITHUB.isGiteaFamily)
    }

    @Test
    fun `GitHub and GitHub Enterprise are one API family`() {
        assertTrue(ForgeKind.GITHUB.isGitHubFamily)
        assertTrue(ForgeKind.GITHUB_ENTERPRISE.isGitHubFamily)
        assertFalse(ForgeKind.GITEA.isGitHubFamily)
    }

    @Test
    fun `the self-hosted kinds have no default host to pre-fill`() {
        assertTrue(ForgeKind.GITEA.isSelfHosted)
        assertTrue(ForgeKind.FORGEJO.isSelfHosted)
        assertTrue(ForgeKind.GITHUB_ENTERPRISE.isSelfHosted)
        assertFalse(ForgeKind.GITHUB.isSelfHosted)
        assertFalse(ForgeKind.GITLAB.isSelfHosted)
    }
}

class ForgeAccountTest {

    private val account = ForgeAccount(
        id = "abc",
        kind = ForgeKind.GITEA,
        displayName = "Home server",
        baseUrl = "https://git.example.com/",
        username = "alex",
    )

    @Test
    fun `the base URL loses its trailing slash`() {
        assertEquals("https://git.example.com", account.normalizedBaseUrl)
    }

    @Test
    fun `the credential key is tied to the account id, not to its name`() {
        // Two accounts on the same server must not share one token.
        val other = account.copy(id = "def")
        assertTrue(account.credentialKey != other.credentialKey)
        // Renaming an account must not orphan its token.
        assertEquals(account.credentialKey, account.copy(displayName = "Renamed").credentialKey)
    }

    @Test
    fun `the label carries the name and the user`() {
        assertEquals("Home server (alex)", account.label)
        assertEquals("Gitea", account.copy(displayName = "", username = null).label)
    }
}

class ForgePullRequestTest {

    private fun pullRequest(
        state: IssueState = IssueState.OPEN,
        merged: Boolean = false,
        draft: Boolean = false,
        mergeable: Boolean? = true,
    ) = ForgePullRequest(
        number = 1,
        title = "Add a thing",
        body = null,
        state = state,
        author = "alex",
        sourceBranch = "feature",
        targetBranch = "main",
        draft = draft,
        merged = merged,
        mergeable = mergeable,
        comments = 0,
        createdAt = null,
        updatedAt = null,
        webUrl = "https://example.com/pull/1",
    )

    @Test
    fun `the status label reflects the most significant fact first`() {
        assertEquals("merged", pullRequest(state = IssueState.CLOSED, merged = true).statusLabel)
        assertEquals("draft", pullRequest(draft = true).statusLabel)
        assertEquals("closed", pullRequest(state = IssueState.CLOSED).statusLabel)
        assertEquals("conflicts", pullRequest(mergeable = false).statusLabel)
        assertEquals("open", pullRequest().statusLabel)
    }

    @Test
    fun `an unknown mergeable state does not read as conflicts`() {
        // GitHub computes mergeability lazily and reports null until it knows.
        assertEquals("open", pullRequest(mergeable = null).statusLabel)
    }
}

class ForgeRepositoryTest {

    private val repository = ForgeRepository(
        id = "1",
        owner = "acme",
        name = "widgets",
        description = "Widget factory",
        defaultBranch = "main",
        isPrivate = false,
        isFork = false,
        webUrl = "https://github.com/acme/widgets",
        cloneUrl = null,
        sshUrl = null,
        openIssues = 3,
        stars = 10,
        updatedAt = null,
        accountId = "account",
    )

    @Test
    fun `search matches the full name and the description`() {
        assertTrue(repository.matches(""))
        assertTrue(repository.matches("widget"))
        assertTrue(repository.matches("acme/wid"))
        assertTrue(repository.matches("factory"))
        assertFalse(repository.matches("gadget"))
    }

    @Test
    fun `the key separates the same repository seen through two accounts`() {
        assertTrue(repository.key != repository.copy(accountId = "other").key)
    }
}
