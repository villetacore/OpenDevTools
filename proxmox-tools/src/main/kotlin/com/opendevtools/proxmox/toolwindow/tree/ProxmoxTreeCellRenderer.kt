package com.opendevtools.proxmox.toolwindow.tree

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.opendevtools.core.format.Formats
import com.opendevtools.proxmox.ProxmoxIcons
import com.opendevtools.proxmox.model.GuestStatus
import com.opendevtools.proxmox.model.GuestType
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

/** Draws one tree row; the trailing grey text is the same live summary the web UI shows. */
class ProxmoxTreeCellRenderer : ColoredTreeCellRenderer() {

    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        when (val item = (value as? DefaultMutableTreeNode)?.userObject as? ProxmoxTreeItem ?: return) {
            is ProxmoxTreeItem.DatacenterItem -> renderDatacenter(item)
            is ProxmoxTreeItem.NodeItem -> renderNode(item)
            is ProxmoxTreeItem.PoolItem -> renderPool(item)
            is ProxmoxTreeItem.TagItem -> renderTag(item)
            is ProxmoxTreeItem.GuestGroupItem -> renderGuestGroup(item)
            is ProxmoxTreeItem.StorageGroupItem -> renderStorageGroup(item)
            is ProxmoxTreeItem.GuestItem -> renderGuest(item)
            is ProxmoxTreeItem.StorageItem -> renderStorage(item)
            is ProxmoxTreeItem.MessageItem -> {
                icon = AllIcons.General.Information
                append(item.message, SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
    }

    private fun renderDatacenter(item: ProxmoxTreeItem.DatacenterItem) {
        icon = ProxmoxIcons.Datacenter
        append(item.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        when (item.quorate) {
            true -> append("  quorate", RUNNING)
            false -> append("  no quorum", SimpleTextAttributes.ERROR_ATTRIBUTES)
            null -> Unit
        }
    }

    private fun renderNode(item: ProxmoxTreeItem.NodeItem) {
        val node = item.node
        icon = if (node.isOnline) ProxmoxIcons.Node else ProxmoxIcons.NodeOffline
        append(node.name)
        append("  ${node.status}", statusAttributes(node.status))
        if (node.isOnline) {
            append(
                "  CPU ${Formats.percent(node.cpu)}" +
                    ", RAM ${Formats.percent(memoryShare(node.mem, node.maxMem))}" +
                    ", up ${Formats.uptime(node.uptime)}",
                SimpleTextAttributes.GRAYED_ATTRIBUTES,
            )
        }
    }

    private fun renderPool(item: ProxmoxTreeItem.PoolItem) {
        icon = AllIcons.Nodes.Folder
        append(item.pool.id)
        append("  ${item.count}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        item.pool.comment?.let { append("  $it", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES) }
    }

    private fun renderTag(item: ProxmoxTreeItem.TagItem) {
        icon = AllIcons.Nodes.Tag
        append(item.tag)
        append("  ${item.count}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }

    private fun renderGuestGroup(item: ProxmoxTreeItem.GuestGroupItem) {
        icon = if (item.type == GuestType.QEMU) ProxmoxIcons.VirtualMachine else ProxmoxIcons.Container
        append(item.type.groupTitle)
        append("  ${item.count}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }

    private fun renderStorageGroup(item: ProxmoxTreeItem.StorageGroupItem) {
        icon = ProxmoxIcons.Storage
        append("Storage")
        append("  ${item.count}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }

    private fun renderGuest(item: ProxmoxTreeItem.GuestItem) {
        val guest = item.guest
        icon = when {
            guest.template -> ProxmoxIcons.Template
            guest.type == GuestType.QEMU ->
                if (guest.isRunning) ProxmoxIcons.VirtualMachineRunning else ProxmoxIcons.VirtualMachine

            else -> if (guest.isRunning) ProxmoxIcons.ContainerRunning else ProxmoxIcons.Container
        }
        append("${guest.vmid}  ")
        append(guest.name)

        val status = if (guest.template) "template" else guest.status.display
        append("  $status", statusAttributes(status))

        if (guest.isRunning) {
            append(
                "  CPU ${Formats.percent(guest.cpu)}, RAM ${Formats.percent(memoryShare(guest.mem, guest.maxMem))}",
                SimpleTextAttributes.GRAYED_ATTRIBUTES,
            )
        }
        guest.lock?.let { append("  locked: $it", SimpleTextAttributes.ERROR_ATTRIBUTES) }
        guest.haState?.let { append("  HA: $it", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES) }
        if (guest.tags.isNotEmpty()) {
            append("  ${guest.tags.joinToString(" ")}", TAG)
        }
    }

    private fun renderStorage(item: ProxmoxTreeItem.StorageItem) {
        val storage = item.storage
        icon = if (storage.shared) ProxmoxIcons.StorageShared else ProxmoxIcons.Storage
        append(storage.name)
        storage.type?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
        append("  ${Formats.ratio(storage.used, storage.total)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        if (!storage.isActive) append("  inactive", SimpleTextAttributes.ERROR_ATTRIBUTES)
    }

    private fun memoryShare(used: Long, total: Long): Double =
        if (total <= 0) 0.0 else used.toDouble() / total

    private fun statusAttributes(status: String): SimpleTextAttributes = when (status) {
        GuestStatus.RUNNING.api, "online" -> RUNNING
        GuestStatus.PAUSED.api, GuestStatus.SUSPENDED.api -> PAUSED
        "unknown" -> SimpleTextAttributes.ERROR_ATTRIBUTES
        else -> SimpleTextAttributes.GRAYED_ATTRIBUTES
    }

    private companion object {
        val RUNNING = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor(0x2E7D32, 0x57965C))
        val PAUSED = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor(0xB07800, 0xD6AE58))
        val TAG = SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, JBColor(0x4A6FA5, 0x7FA0D0))
    }
}
