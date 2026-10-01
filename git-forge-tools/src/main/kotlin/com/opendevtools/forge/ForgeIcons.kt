package com.opendevtools.forge

import com.intellij.openapi.util.IconLoader
import com.opendevtools.forge.model.ForgeKind
import javax.swing.Icon

/** Icons of the plugin; each has a `_dark.svg` sibling that the loader picks up automatically. */
object ForgeIcons {

    @JvmField
    val ToolWindow: Icon = load("/icons/forge13.svg")

    @JvmField
    val Repository: Icon = load("/icons/repository.svg")

    @JvmField
    val RepositoryPrivate: Icon = load("/icons/repository_private.svg")

    @JvmField
    val Fork: Icon = load("/icons/fork.svg")

    @JvmField
    val Issue: Icon = load("/icons/issue.svg")

    @JvmField
    val PullRequest: Icon = load("/icons/pull_request.svg")

    @JvmField
    val Pipeline: Icon = load("/icons/pipeline.svg")

    @JvmField
    val Release: Icon = load("/icons/release.svg")

    @JvmField
    val Branch: Icon = load("/icons/branch.svg")

    /**
     * All five kinds share the generic forge mark. Vendor logos are trademarks, and shipping them
     * in a third-party plugin is a licensing question rather than a design one.
     */
    fun forKind(kind: ForgeKind): Icon = when (kind) {
        ForgeKind.GITHUB, ForgeKind.GITHUB_ENTERPRISE, ForgeKind.GITLAB,
        ForgeKind.GITEA, ForgeKind.FORGEJO,
        -> ToolWindow
    }

    private fun load(path: String): Icon = IconLoader.getIcon(path, ForgeIcons::class.java)
}
