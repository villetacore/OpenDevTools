package com.opendevtools.proxmox

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/** Icons of the plugin; each has a `_dark.svg` sibling that the loader picks up automatically. */
object ProxmoxIcons {

    @JvmField
    val ToolWindow: Icon = load("/icons/proxmox13.svg")

    @JvmField
    val Datacenter: Icon = load("/icons/datacenter.svg")

    @JvmField
    val Node: Icon = load("/icons/node.svg")

    @JvmField
    val NodeOffline: Icon = load("/icons/node_offline.svg")

    @JvmField
    val VirtualMachine: Icon = load("/icons/vm.svg")

    @JvmField
    val VirtualMachineRunning: Icon = load("/icons/vm_running.svg")

    @JvmField
    val Container: Icon = load("/icons/container.svg")

    @JvmField
    val ContainerRunning: Icon = load("/icons/container_running.svg")

    @JvmField
    val Template: Icon = load("/icons/template.svg")

    @JvmField
    val Storage: Icon = load("/icons/storage.svg")

    @JvmField
    val StorageShared: Icon = load("/icons/storage_shared.svg")

    @JvmField
    val Snapshot: Icon = load("/icons/snapshot.svg")

    @JvmField
    val Backup: Icon = load("/icons/backup.svg")

    @JvmField
    val Task: Icon = load("/icons/task.svg")

    @JvmField
    val Firewall: Icon = load("/icons/firewall.svg")

    private fun load(path: String): Icon = IconLoader.getIcon(path, ProxmoxIcons::class.java)
}
