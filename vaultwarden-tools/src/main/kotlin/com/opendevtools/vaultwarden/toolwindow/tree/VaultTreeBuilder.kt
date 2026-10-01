package com.opendevtools.vaultwarden.toolwindow.tree

import com.opendevtools.vaultwarden.model.VaultFolder
import com.opendevtools.vaultwarden.model.VaultGrouping
import com.opendevtools.vaultwarden.model.VaultItem
import com.opendevtools.vaultwarden.model.VaultItemType
import com.opendevtools.vaultwarden.model.VaultSnapshot
import javax.swing.tree.DefaultMutableTreeNode

/** What the tree is currently allowed to show. */
data class VaultFilter(
    val query: String = "",
    val favoritesOnly: Boolean = false,
    val typeFilter: VaultItemType? = null,
    val showTrash: Boolean = false,
    val showSends: Boolean = true,
) {
    fun accepts(item: VaultItem): Boolean {
        if (favoritesOnly && !item.favorite) return false
        if (typeFilter != null && item.type != typeFilter) return false
        return item.matches(query)
    }
}

/**
 * Builds the tree for one of the groupings the web vault offers. Trash and Sends are appended as
 * their own branches regardless of the grouping, which is also how the web vault presents them.
 */
object VaultTreeBuilder {

    fun build(
        root: DefaultMutableTreeNode,
        snapshot: VaultSnapshot,
        grouping: VaultGrouping,
        filter: VaultFilter,
        favoritesFirst: Boolean,
    ) {
        root.removeAllChildren()
        val visible = snapshot.items.filter { filter.accepts(it) }

        if (favoritesFirst) {
            val favorites = visible.filter { it.favorite }
            if (favorites.isNotEmpty()) {
                val branch = DefaultMutableTreeNode(VaultTreeItem.FavoritesNode(favorites.size))
                root.add(branch)
                addItems(branch, favorites)
            }
        }

        when (grouping) {
            VaultGrouping.BY_FOLDER -> buildByFolder(root, snapshot, visible)
            VaultGrouping.BY_TYPE -> buildByType(root, visible)
            VaultGrouping.BY_COLLECTION -> buildByCollection(root, snapshot, visible)
            VaultGrouping.FLAT -> addItems(root, visible)
        }

        if (filter.showSends && snapshot.sends.isNotEmpty()) {
            val branch = DefaultMutableTreeNode(VaultTreeItem.SendsNode(snapshot.sends.size))
            root.add(branch)
            snapshot.sends.forEach { branch.add(DefaultMutableTreeNode(VaultTreeItem.SendNode(it))) }
        }

        if (snapshot.deletedItems.isNotEmpty()) {
            val trashed = snapshot.deletedItems.filter { filter.accepts(it) }
            val branch = DefaultMutableTreeNode(VaultTreeItem.TrashNode(trashed.size))
            root.add(branch)
            addItems(branch, trashed)
        }
    }

    private fun buildByFolder(
        root: DefaultMutableTreeNode,
        snapshot: VaultSnapshot,
        visible: List<VaultItem>,
    ) {
        val byFolder = visible.groupBy { it.folderId }
        val known = snapshot.folders.map { it.id }.toSet()

        // "No Folder" first, then the named folders, which is the order the web vault uses.
        val noFolder = byFolder[null].orEmpty()
        if (noFolder.isNotEmpty()) {
            addFolderBranch(root, VaultFolder.NONE, noFolder)
        }
        snapshot.folders
            .sortedBy { it.name.lowercase() }
            .forEach { folder ->
                val items = byFolder[folder.id].orEmpty()
                if (items.isNotEmpty()) addFolderBranch(root, folder, items)
            }

        // An item can point at a folder the sync did not return; it still has to appear somewhere.
        val orphans = visible.filter { it.folderId != null && it.folderId !in known }
        if (orphans.isNotEmpty()) {
            addFolderBranch(root, VaultFolder(null, "Other"), orphans)
        }
    }

    private fun buildByType(root: DefaultMutableTreeNode, visible: List<VaultItem>) {
        for (type in VaultItemType.entries) {
            val items = visible.filter { it.type == type }
            if (items.isEmpty()) continue
            val branch = DefaultMutableTreeNode(VaultTreeItem.TypeNode(type, items.size))
            root.add(branch)
            addItems(branch, items)
        }
    }

    private fun buildByCollection(
        root: DefaultMutableTreeNode,
        snapshot: VaultSnapshot,
        visible: List<VaultItem>,
    ) {
        val personal = visible.filter { it.organizationId == null }
        if (personal.isNotEmpty()) {
            addFolderBranch(root, VaultFolder(null, "My Vault"), personal)
        }
        snapshot.collections
            .sortedBy { it.name.lowercase() }
            .forEach { collection ->
                val items = visible.filter { collection.id in it.collectionIds }
                if (items.isEmpty()) return@forEach
                val branch = DefaultMutableTreeNode(
                    VaultTreeItem.CollectionNode(collection, items.size),
                )
                root.add(branch)
                addItems(branch, items)
            }
    }

    private fun addFolderBranch(
        root: DefaultMutableTreeNode,
        folder: VaultFolder,
        items: List<VaultItem>,
    ) {
        val branch = DefaultMutableTreeNode(VaultTreeItem.FolderNode(folder, items.size))
        root.add(branch)
        addItems(branch, items)
    }

    private fun addItems(parent: DefaultMutableTreeNode, items: List<VaultItem>) {
        items.sortedBy { it.name.lowercase() }
            .forEach { parent.add(DefaultMutableTreeNode(VaultTreeItem.ItemNode(it))) }
    }
}
