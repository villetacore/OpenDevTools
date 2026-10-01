package com.opendevtools.forge.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.opendevtools.core.format.Formats
import com.opendevtools.core.ui.KeyedTreeItem
import com.opendevtools.forge.ForgeIcons
import com.opendevtools.forge.model.ForgeAccount
import com.opendevtools.forge.model.ForgeRepository
import com.opendevtools.forge.service.ForgeSnapshot
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

/** User object of a node in the forge tree. */
sealed class ForgeTreeItem(override val key: String) : KeyedTreeItem {

    class AccountItem(val account: ForgeAccount, val count: Int, val error: String?) :
        ForgeTreeItem(account.key)

    class RepositoryItem(val repository: ForgeRepository, val isProjectRepository: Boolean) :
        ForgeTreeItem(repository.key)

    class MessageItem(val message: String) : ForgeTreeItem("message:$message")
}

/** Builds the tree: one branch per account, its repositories beneath. */
object ForgeTreeBuilder {

    fun build(
        root: DefaultMutableTreeNode,
        accounts: List<ForgeAccount>,
        snapshot: ForgeSnapshot,
        query: String,
        projectRepositoryKey: String?,
    ) {
        root.removeAllChildren()
        for (account in accounts) {
            val repositories = snapshot.repositoriesByAccount[account.id]
                .orEmpty()
                .filter { it.matches(query) }
            val error = snapshot.failures[account.id]

            // An account that failed still gets a row - that is where its error is reported.
            if (repositories.isEmpty() && error == null && query.isNotBlank()) continue

            val branch = DefaultMutableTreeNode(
                ForgeTreeItem.AccountItem(account, repositories.size, error),
            )
            root.add(branch)
            repositories.forEach { repository ->
                branch.add(
                    DefaultMutableTreeNode(
                        ForgeTreeItem.RepositoryItem(
                            repository,
                            isProjectRepository = repository.key == projectRepositoryKey,
                        ),
                    ),
                )
            }
        }
    }
}

/** Draws one tree row. */
class ForgeTreeCellRenderer : ColoredTreeCellRenderer() {

    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        when (val item = (value as? DefaultMutableTreeNode)?.userObject as? ForgeTreeItem ?: return) {
            is ForgeTreeItem.AccountItem -> {
                icon = ForgeIcons.forKind(item.account.kind)
                append(item.account.label)
                if (item.error != null) {
                    append("  ${item.error}", SimpleTextAttributes.ERROR_ATTRIBUTES)
                } else {
                    append("  ${item.count}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }

            is ForgeTreeItem.RepositoryItem -> renderRepository(item)

            is ForgeTreeItem.MessageItem -> {
                icon = AllIcons.General.Information
                append(item.message, SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
    }

    private fun renderRepository(item: ForgeTreeItem.RepositoryItem) {
        val repository = item.repository
        icon = when {
            repository.isFork -> ForgeIcons.Fork
            repository.isPrivate -> ForgeIcons.RepositoryPrivate
            else -> ForgeIcons.Repository
        }
        append(
            repository.fullName,
            if (item.isProjectRepository) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
            else SimpleTextAttributes.REGULAR_ATTRIBUTES,
        )
        if (item.isProjectRepository) append("  this project", PROJECT)
        if (repository.openIssues > 0) {
            append("  ${repository.openIssues} open", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        repository.updatedAt?.let {
            append("  ${Formats.isoTimestamp(it)}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }

    private companion object {
        val PROJECT = SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, JBColor(0x2E7D32, 0x57965C))
    }
}
