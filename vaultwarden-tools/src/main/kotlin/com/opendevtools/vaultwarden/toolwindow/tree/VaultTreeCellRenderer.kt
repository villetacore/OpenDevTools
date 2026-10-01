package com.opendevtools.vaultwarden.toolwindow.tree

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.opendevtools.core.format.Formats
import com.opendevtools.vaultwarden.VaultwardenIcons
import com.opendevtools.vaultwarden.model.SendType
import com.opendevtools.vaultwarden.model.VaultItemType
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

/** Draws one row of the vault tree. Secret values never appear here - only names and metadata. */
class VaultTreeCellRenderer : ColoredTreeCellRenderer() {

    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        when (val node = (value as? DefaultMutableTreeNode)?.userObject as? VaultTreeItem ?: return) {
            is VaultTreeItem.FolderNode -> {
                icon = AllIcons.Nodes.Folder
                append(node.folder.name)
                appendCount(node.count)
            }

            is VaultTreeItem.TypeNode -> {
                icon = iconFor(node.type)
                append(node.type.groupTitle)
                appendCount(node.count)
            }

            is VaultTreeItem.CollectionNode -> {
                icon = AllIcons.Nodes.Folder
                append(node.collection.name)
                appendCount(node.count)
                if (node.collection.readOnly) {
                    append("  read-only", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                }
            }

            is VaultTreeItem.FavoritesNode -> {
                icon = AllIcons.Nodes.Favorite
                append("Favorites")
                appendCount(node.count)
            }

            is VaultTreeItem.TrashNode -> {
                icon = AllIcons.Actions.GC
                append("Trash")
                appendCount(node.count)
            }

            is VaultTreeItem.SendsNode -> {
                icon = VaultwardenIcons.Send
                append("Sends")
                appendCount(node.count)
            }

            is VaultTreeItem.ItemNode -> renderItem(node)
            is VaultTreeItem.SendNode -> renderSend(node)

            is VaultTreeItem.MessageNode -> {
                icon = AllIcons.General.Information
                append(node.message, SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
    }

    private fun renderItem(node: VaultTreeItem.ItemNode) {
        val item = node.item
        icon = iconFor(item.type)
        append(
            item.name,
            if (item.favorite) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
            else SimpleTextAttributes.REGULAR_ATTRIBUTES,
        )
        item.subtitle?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
        if (item.hasTotp) append("  TOTP", BADGE)
        if (item.hasAttachments) append("  ${item.attachments.size} 📎", BADGE)
        if (item.isShared) append("  shared", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
        if (item.isDeleted) append("  in trash", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
        if (item.readOnly) append("  read-only", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
    }

    private fun renderSend(node: VaultTreeItem.SendNode) {
        val send = node.send
        icon = if (send.type == SendType.FILE) VaultwardenIcons.Attachment else VaultwardenIcons.Send
        append(send.name)
        append("  ${send.accessCount} views", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        send.deletionDate?.let {
            append("  deletes ${Formats.isoTimestamp(it)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        when {
            send.disabled -> append("  disabled", SimpleTextAttributes.ERROR_ATTRIBUTES)
            send.isExhausted -> append("  no views left", SimpleTextAttributes.ERROR_ATTRIBUTES)
            send.passwordProtected -> append("  password", BADGE)
        }
    }

    private fun appendCount(count: Int) =
        append("  $count", SimpleTextAttributes.GRAYED_ATTRIBUTES)

    private fun iconFor(type: VaultItemType) = when (type) {
        VaultItemType.LOGIN -> VaultwardenIcons.Login
        VaultItemType.SECURE_NOTE -> VaultwardenIcons.Note
        VaultItemType.CARD -> VaultwardenIcons.Card
        VaultItemType.IDENTITY -> VaultwardenIcons.Identity
        VaultItemType.SSH_KEY -> VaultwardenIcons.SshKey
    }

    private companion object {
        val BADGE = SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, JBColor(0x4A6FA5, 0x7FA0D0))
    }
}
