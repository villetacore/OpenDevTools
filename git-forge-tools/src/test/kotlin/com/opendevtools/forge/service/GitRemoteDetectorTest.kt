package com.opendevtools.forge.service

import com.opendevtools.forge.model.ForgeRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Remote URLs come in several shapes, and getting `owner/name` out of them is what connects the
 * open project to an account's repository list.
 */
class GitRemoteTest {

    @Test
    fun `an https remote yields its owner and name`() {
        val remote = GitRemote("origin", "https://github.com/acme/widgets.git")
        assertEquals("acme/widgets", remote.ownerAndName)
        assertEquals("github.com", remote.host)
    }

    @Test
    fun `an ssh remote yields the same`() {
        val remote = GitRemote("origin", "git@github.com:acme/widgets.git")
        assertEquals("acme/widgets", remote.ownerAndName)
        assertEquals("github.com", remote.host)
    }

    @Test
    fun `a missing dot-git suffix and a trailing slash are both tolerated`() {
        assertEquals("acme/widgets", GitRemote("o", "https://git.example.com/acme/widgets").ownerAndName)
        assertEquals("acme/widgets", GitRemote("o", "https://git.example.com/acme/widgets/").ownerAndName)
    }

    @Test
    fun `a GitLab subgroup path reduces to its last two segments`() {
        val remote = GitRemote("origin", "https://gitlab.com/acme/backend/widgets.git")
        assertEquals("backend/widgets", remote.ownerAndName)
    }

    @Test
    fun `a self-hosted instance on a port is handled`() {
        val remote = GitRemote("origin", "https://git.example.com:8443/acme/widgets.git")
        assertEquals("acme/widgets", remote.ownerAndName)
        assertEquals("git.example.com", remote.host)
    }

    @Test
    fun `a URL with credentials does not confuse the host`() {
        val remote = GitRemote("origin", "https://user:token@git.example.com/acme/widgets.git")
        assertEquals("git.example.com", remote.host)
        assertEquals("acme/widgets", remote.ownerAndName)
    }

    @Test
    fun `something that is not a remote URL yields nothing`() {
        assertNull(GitRemote("origin", "/local/path/to/repo").ownerAndName)
        assertNull(GitRemote("origin", "").ownerAndName)
    }
}

class RepositoryMatchingTest {

    private fun repository(owner: String, name: String, host: String) = ForgeRepository(
        id = "1",
        owner = owner,
        name = name,
        description = null,
        defaultBranch = "main",
        isPrivate = false,
        isFork = false,
        webUrl = "https://$host/$owner/$name",
        cloneUrl = null,
        sshUrl = null,
        openIssues = 0,
        stars = 0,
        updatedAt = null,
        accountId = "account",
    )

    @Test
    fun `the repository matching the remote is found`() {
        val repositories = listOf(
            repository("other", "thing", "github.com"),
            repository("acme", "widgets", "github.com"),
        )
        val remotes = listOf(GitRemote("origin", "git@github.com:acme/widgets.git"))

        val match = GitRemoteDetector.matchingRepository(remotes, repositories)
        assertEquals("acme/widgets", match?.fullName)
    }

    @Test
    fun `the same path on a different host does not match`() {
        // Two forges can host a repository of the same name; the host has to agree as well.
        val repositories = listOf(repository("acme", "widgets", "gitlab.com"))
        val remotes = listOf(GitRemote("origin", "git@github.com:acme/widgets.git"))

        assertNull(GitRemoteDetector.matchingRepository(remotes, repositories))
    }

    @Test
    fun `nothing matches when the account holds no such repository`() {
        val repositories = listOf(repository("other", "thing", "github.com"))
        val remotes = listOf(GitRemote("origin", "git@github.com:acme/widgets.git"))

        assertNull(GitRemoteDetector.matchingRepository(remotes, repositories))
    }

    @Test
    fun `an empty remote list matches nothing rather than failing`() {
        assertNull(GitRemoteDetector.matchingRepository(emptyList(), emptyList()))
    }
}
