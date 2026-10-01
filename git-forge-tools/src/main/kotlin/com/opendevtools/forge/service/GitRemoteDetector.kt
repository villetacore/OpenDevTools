package com.opendevtools.forge.service

import com.intellij.openapi.project.Project
import com.opendevtools.forge.model.ForgeRepository
import java.io.File

/** A remote found in the project's `.git/config`. */
data class GitRemote(val name: String, val url: String) {

    /**
     * Both remote shapes reduce to the same `owner/name`:
     * `https://host/owner/name.git` and `git@host:owner/name.git`.
     */
    val ownerAndName: String?
        get() {
            val withoutSuffix = url.trim().removeSuffix(".git").removeSuffix("/")
            val path = when {
                withoutSuffix.startsWith("git@") -> withoutSuffix.substringAfter(':', "")
                withoutSuffix.contains("://") -> withoutSuffix.substringAfter("://")
                    .substringAfter('/', "")

                else -> return null
            }
            val segments = path.split('/').filter { it.isNotBlank() }
            // A GitLab subgroup path is deeper than two segments; the last two identify the project.
            return if (segments.size >= 2) segments.takeLast(2).joinToString("/") else null
        }

    val host: String?
        get() {
            val trimmed = url.trim()
            return when {
                trimmed.startsWith("git@") -> trimmed.removePrefix("git@").substringBefore(':')
                trimmed.contains("://") -> trimmed.substringAfter("://")
                    .substringAfter('@')
                    .substringBefore('/')
                    .substringBefore(':')

                else -> null
            }.takeIf { !it.isNullOrBlank() }
        }
}

/**
 * Finds the git remotes of the open project by reading `.git/config` directly.
 *
 * Parsing the file avoids depending on the bundled Git plugin, which would make this plugin
 * refuse to load in an IDE where that plugin is disabled - a steep price for one convenience.
 */
object GitRemoteDetector {

    fun remotes(project: Project): List<GitRemote> {
        val base = project.basePath?.let { File(it) } ?: return emptyList()
        val config = findGitConfig(base) ?: return emptyList()

        val result = mutableListOf<GitRemote>()
        var currentRemote: String? = null
        runCatching { config.readLines() }.getOrDefault(emptyList()).forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("[") -> {
                    // Section headers look like: [remote "origin"]
                    currentRemote = REMOTE_SECTION.find(line)?.groupValues?.getOrNull(1)
                }

                currentRemote != null && line.startsWith("url") -> {
                    val url = line.substringAfter('=', "").trim()
                    if (url.isNotEmpty()) result.add(GitRemote(currentRemote!!, url))
                }
            }
        }
        // `origin` first: it is the one a person means by "the remote".
        return result.sortedBy { if (it.name == "origin") 0 else 1 }
    }

    /**
     * Walks up from the project directory looking for `.git`. A `.git` file rather than a
     * directory means a worktree or a submodule, whose config lives where that file points.
     */
    private fun findGitConfig(start: File): File? {
        var directory: File? = start
        var depth = 0
        while (directory != null && depth < MAX_DEPTH) {
            val git = File(directory, ".git")
            when {
                git.isDirectory -> return File(git, "config").takeIf { it.isFile }
                git.isFile -> {
                    val pointer = runCatching { git.readText() }.getOrNull().orEmpty()
                    val path = pointer.substringAfter("gitdir:", "").trim()
                    if (path.isNotEmpty()) {
                        val resolved = File(path).takeIf { it.isAbsolute } ?: File(directory, path)
                        return File(resolved, "config").takeIf { it.isFile }
                    }
                }
            }
            directory = directory.parentFile
            depth++
        }
        return null
    }

    /**
     * The repository among [repositories] that the project's remotes point at. Both the host and
     * the `owner/name` have to agree: two forges can host a repository of the same name.
     */
    fun matchingRepository(
        remotes: List<GitRemote>,
        repositories: List<ForgeRepository>,
    ): ForgeRepository? {
        for (remote in remotes) {
            val path = remote.ownerAndName?.lowercase() ?: continue
            val host = remote.host?.lowercase()
            val match = repositories.firstOrNull { repository ->
                repository.fullName.lowercase().endsWith(path) &&
                    (host == null || repository.webUrl.lowercase().contains(host))
            }
            if (match != null) return match
        }
        return null
    }

    private val REMOTE_SECTION = Regex("""\[remote\s+"([^"]+)"]""")
    private const val MAX_DEPTH = 8
}
