package com.opendevtools.vaultwarden.toolwindow.tree

import com.opendevtools.core.ui.KeyedTreeItem
import com.opendevtools.vaultwarden.model.VaultCollection
import com.opendevtools.vaultwarden.model.VaultFolder
import com.opendevtools.vaultwarden.model.VaultItem
import com.opendevtools.vaultwarden.model.VaultItemType
import com.opendevtools.vaultwarden.model.VaultSend

/** User object of a node in the vault tree; [key] survives a rebuild after a sync. */
sealed class VaultTreeItem(override val key: String) : KeyedTreeItem {

    class FolderNode(val folder: VaultFolder, val count: Int) : VaultTreeItem(folder.key)

    class TypeNode(val type: VaultItemType, val count: Int) :
        VaultTreeItem("type:${type.code}")

    class CollectionNode(val collection: VaultCollection, val count: Int) :
        VaultTreeItem(collection.key)

    class FavoritesNode(val count: Int) : VaultTreeItem("favorites")

    class TrashNode(val count: Int) : VaultTreeItem("trash")

    class SendsNode(val count: Int) : VaultTreeItem("sends")

    class ItemNode(val item: VaultItem) : VaultTreeItem(item.key)

    class SendNode(val send: VaultSend) : VaultTreeItem(send.key)

    class MessageNode(val message: String) : VaultTreeItem("message:$message")
}
