package com.opendevtools.proxmox.toolwindow

import com.intellij.openapi.actionSystem.DataKey
import com.opendevtools.proxmox.model.FirewallRule
import com.opendevtools.proxmox.model.GuestSnapshot
import com.opendevtools.proxmox.model.NodeService
import com.opendevtools.proxmox.model.ProxmoxGuest
import com.opendevtools.proxmox.model.ProxmoxNode
import com.opendevtools.proxmox.model.ProxmoxStorage
import com.opendevtools.proxmox.model.ProxmoxTask
import com.opendevtools.proxmox.model.StorageVolume

/**
 * What the actions read out of the action context. Every key is filled by [ProxmoxPanel], so an
 * action never has to reach into the UI to find out what the user selected.
 */
object ProxmoxDataKeys {

    @JvmField
    val PANEL: DataKey<ProxmoxPanel> = DataKey.create("proxmox.panel")

    @JvmField
    val GUESTS: DataKey<List<ProxmoxGuest>> = DataKey.create("proxmox.selected.guests")

    @JvmField
    val NODES: DataKey<List<ProxmoxNode>> = DataKey.create("proxmox.selected.nodes")

    @JvmField
    val STORAGES: DataKey<List<ProxmoxStorage>> = DataKey.create("proxmox.selected.storages")

    /** Row selected in the Snapshots tab. */
    @JvmField
    val SNAPSHOT: DataKey<GuestSnapshot> = DataKey.create("proxmox.selected.snapshot")

    /** Row selected in the Backups or Content tab. */
    @JvmField
    val VOLUME: DataKey<StorageVolume> = DataKey.create("proxmox.selected.volume")

    /** Row selected in the Tasks tab. */
    @JvmField
    val TASK: DataKey<ProxmoxTask> = DataKey.create("proxmox.selected.task")

    /** Row selected in the Firewall tab. */
    @JvmField
    val FIREWALL_RULE: DataKey<FirewallRule> = DataKey.create("proxmox.selected.firewallRule")

    /** Row selected in the Services tab. */
    @JvmField
    val SERVICE: DataKey<NodeService> = DataKey.create("proxmox.selected.service")
}
