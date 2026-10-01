package com.opendevtools.proxmox.api

import com.opendevtools.proxmox.model.GuestType

/**
 * Web UI URLs of a Proxmox VE server. These are handed to an external browser and never fetched,
 * because the console is a noVNC/xterm.js application that needs a real browser to run.
 */
object ProxmoxUrls {

    fun baseUrl(host: String, port: Int): String = "https://" + host.trim() + ":" + port

    /** noVNC console of a guest. */
    fun console(baseUrl: String, node: String, type: GuestType, vmid: Int): String =
        "$baseUrl/?console=${type.consoleType}&novnc=1&vmid=$vmid&node=$node&resize=off"

    /** xterm.js serial console of a guest, which is lighter than noVNC over a slow link. */
    fun xtermConsole(baseUrl: String, node: String, type: GuestType, vmid: Int): String =
        "$baseUrl/?console=${type.consoleType}&xtermjs=1&vmid=$vmid&node=$node"

    /** SPICE needs a local client; the web UI hands out a .vv file from this URL. */
    fun spiceConsole(baseUrl: String, node: String, vmid: Int): String =
        "$baseUrl/?console=kvm&vmid=$vmid&node=$node&cmd=spice"

    fun nodeShell(baseUrl: String, node: String): String =
        "$baseUrl/?console=shell&xtermjs=1&node=$node"

    /** Deep link into the web UI for anything the plugin does not render itself. */
    fun guestPanel(baseUrl: String, node: String, type: GuestType, vmid: Int): String =
        "$baseUrl/#v1:0:=${type.api}%2F$vmid:4::::::"
}
