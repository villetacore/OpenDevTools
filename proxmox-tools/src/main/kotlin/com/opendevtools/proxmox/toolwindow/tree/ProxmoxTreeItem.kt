package com.opendevtools.proxmox.toolwindow.tree

import com.opendevtools.core.ui.KeyedTreeItem
import com.opendevtools.proxmox.model.GuestType
import com.opendevtools.proxmox.model.ProxmoxGuest
import com.opendevtools.proxmox.model.ProxmoxNode
import com.opendevtools.proxmox.model.ProxmoxPool
import com.opendevtools.proxmox.model.ProxmoxStorage

/**
 * User object of a node in the Proxmox tree. [key] survives refreshes and drives which rows stay
 * expanded and selected when the model is rebuilt.
 */
sealed class ProxmoxTreeItem(override val key: String) : KeyedTreeItem {

    class DatacenterItem(val name: String, val quorate: Boolean?) : ProxmoxTreeItem("datacenter")

    class NodeItem(val node: ProxmoxNode) : ProxmoxTreeItem(node.key)

    class PoolItem(val pool: ProxmoxPool, val count: Int) : ProxmoxTreeItem("pool:${pool.id}")

    class TagItem(val tag: String, val count: Int) : ProxmoxTreeItem("tag:$tag")

    class GuestGroupItem(val scope: String, val type: GuestType, val count: Int) :
        ProxmoxTreeItem("group:$scope:${type.api}")

    class StorageGroupItem(val nodeName: String, val count: Int) :
        ProxmoxTreeItem("storages:$nodeName")

    class GuestItem(val guest: ProxmoxGuest) : ProxmoxTreeItem(guest.key)

    class StorageItem(val storage: ProxmoxStorage) : ProxmoxTreeItem(storage.key)

    class MessageItem(val message: String) : ProxmoxTreeItem("message:$message")
}
