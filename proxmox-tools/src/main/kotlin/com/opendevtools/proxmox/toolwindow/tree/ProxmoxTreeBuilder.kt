package com.opendevtools.proxmox.toolwindow.tree

import com.opendevtools.proxmox.model.ClusterSnapshot
import com.opendevtools.proxmox.model.GuestType
import com.opendevtools.proxmox.model.ProxmoxGuest
import com.opendevtools.proxmox.model.ProxmoxPool
import com.opendevtools.proxmox.model.TreeGrouping
import javax.swing.tree.DefaultMutableTreeNode

/** Filters applied to the tree before it is built. */
data class TreeFilter(
    val query: String = "",
    val showTemplates: Boolean = true,
    val showStorages: Boolean = true,
    val runningOnly: Boolean = false,
) {
    fun accepts(guest: ProxmoxGuest): Boolean {
        if (!showTemplates && guest.template) return false
        if (runningOnly && !guest.isRunning) return false
        return guest.matches(query)
    }
}

/**
 * Builds the tree model for one of the groupings the web UI offers. Keeping this out of the panel
 * makes the four layouts comparable side by side, and each of them is a pure function of the
 * snapshot plus the filter.
 */
object ProxmoxTreeBuilder {

    fun build(
        root: DefaultMutableTreeNode,
        snapshot: ClusterSnapshot,
        grouping: TreeGrouping,
        filter: TreeFilter,
    ) {
        root.removeAllChildren()
        when (grouping) {
            TreeGrouping.BY_NODE -> buildByNode(root, snapshot, filter)
            TreeGrouping.BY_POOL -> buildByPool(root, snapshot, filter)
            TreeGrouping.BY_TAG -> buildByTag(root, snapshot, filter)
            TreeGrouping.FLAT -> buildFlat(root, snapshot, filter)
        }
    }

    private fun buildByNode(root: DefaultMutableTreeNode, snapshot: ClusterSnapshot, filter: TreeFilter) {
        for (node in snapshot.nodes) {
            val nodeBranch = DefaultMutableTreeNode(ProxmoxTreeItem.NodeItem(node))
            root.add(nodeBranch)

            for (type in GuestType.entries) {
                val guests = node.guests.filter { it.type == type && filter.accepts(it) }
                if (guests.isEmpty()) continue
                addGuestGroup(nodeBranch, node.name, type, guests)
            }
            if (filter.showStorages && node.storages.isNotEmpty()) {
                val group = DefaultMutableTreeNode(
                    ProxmoxTreeItem.StorageGroupItem(node.name, node.storages.size),
                )
                nodeBranch.add(group)
                node.storages.forEach { group.add(DefaultMutableTreeNode(ProxmoxTreeItem.StorageItem(it))) }
            }
        }
    }

    private fun buildByPool(root: DefaultMutableTreeNode, snapshot: ClusterSnapshot, filter: TreeFilter) {
        val guests = snapshot.allGuests.filter { filter.accepts(it) }
        val byPool = guests.groupBy { it.pool }

        for (pool in snapshot.pools) {
            val members = byPool[pool.id].orEmpty()
            if (members.isEmpty()) continue
            addPoolBranch(root, pool, members)
        }
        // Guests outside every pool still need a home, and the web UI shows them the same way.
        val unpooled = byPool[null].orEmpty()
        if (unpooled.isNotEmpty()) {
            addPoolBranch(root, ProxmoxPool("(no pool)", null), unpooled)
        }
    }

    private fun buildByTag(root: DefaultMutableTreeNode, snapshot: ClusterSnapshot, filter: TreeFilter) {
        val guests = snapshot.allGuests.filter { filter.accepts(it) }
        val tags = guests.flatMap { it.tags }.distinct().sorted()

        for (tag in tags) {
            val tagged = guests.filter { tag in it.tags }
            val branch = DefaultMutableTreeNode(ProxmoxTreeItem.TagItem(tag, tagged.size))
            root.add(branch)
            tagged.sortedBy { it.vmid }
                .forEach { branch.add(DefaultMutableTreeNode(ProxmoxTreeItem.GuestItem(it))) }
        }
        val untagged = guests.filter { it.tags.isEmpty() }
        if (untagged.isNotEmpty()) {
            val branch = DefaultMutableTreeNode(ProxmoxTreeItem.TagItem("(no tag)", untagged.size))
            root.add(branch)
            untagged.sortedBy { it.vmid }
                .forEach { branch.add(DefaultMutableTreeNode(ProxmoxTreeItem.GuestItem(it))) }
        }
    }

    private fun buildFlat(root: DefaultMutableTreeNode, snapshot: ClusterSnapshot, filter: TreeFilter) {
        snapshot.allGuests
            .filter { filter.accepts(it) }
            .sortedBy { it.vmid }
            .forEach { root.add(DefaultMutableTreeNode(ProxmoxTreeItem.GuestItem(it))) }
    }

    private fun addPoolBranch(root: DefaultMutableTreeNode, pool: ProxmoxPool, members: List<ProxmoxGuest>) {
        val branch = DefaultMutableTreeNode(ProxmoxTreeItem.PoolItem(pool, members.size))
        root.add(branch)
        for (type in GuestType.entries) {
            val guests = members.filter { it.type == type }
            if (guests.isEmpty()) continue
            addGuestGroup(branch, "pool:${pool.id}", type, guests)
        }
    }

    private fun addGuestGroup(
        parent: DefaultMutableTreeNode,
        scope: String,
        type: GuestType,
        guests: List<ProxmoxGuest>,
    ) {
        val group = DefaultMutableTreeNode(ProxmoxTreeItem.GuestGroupItem(scope, type, guests.size))
        parent.add(group)
        guests.sortedBy { it.vmid }
            .forEach { group.add(DefaultMutableTreeNode(ProxmoxTreeItem.GuestItem(it))) }
    }
}
