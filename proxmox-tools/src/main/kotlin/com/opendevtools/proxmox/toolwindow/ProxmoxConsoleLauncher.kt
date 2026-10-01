package com.opendevtools.proxmox.toolwindow

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.opendevtools.proxmox.api.ProxmoxUrls
import com.opendevtools.proxmox.model.GuestType
import com.opendevtools.proxmox.model.ProxmoxGuest
import com.opendevtools.proxmox.model.ProxmoxNode
import com.opendevtools.proxmox.settings.ConsolePreference
import com.opendevtools.proxmox.settings.ProxmoxSettings
import com.opendevtools.proxmox.util.ProxmoxNotifier

/**
 * Opens a Proxmox console in the browser. The console is a noVNC or xterm.js application served
 * by the Proxmox web UI, so it needs a real browser; the plugin only builds the right URL.
 */
object ProxmoxConsoleLauncher {

    fun open(
        project: Project?,
        guests: List<ProxmoxGuest>,
        nodes: List<ProxmoxNode>,
        preference: ConsolePreference? = null,
    ) {
        val settings = ProxmoxSettings.getInstance()
        if (settings.host.isBlank()) {
            ProxmoxNotifier.warn(project, "Proxmox host is not configured")
            return
        }
        val baseUrl = ProxmoxUrls.baseUrl(settings.host, settings.port)
        val kind = preference ?: settings.consolePreference

        guests.forEach { guest ->
            BrowserUtil.browse(consoleUrl(baseUrl, guest, kind))
        }
        nodes.forEach { node ->
            BrowserUtil.browse(ProxmoxUrls.nodeShell(baseUrl, node.name))
        }
    }

    /** Opens the guest's page in the Proxmox web UI, for anything the plugin does not render. */
    fun openWebUi(project: Project?, guest: ProxmoxGuest) {
        val settings = ProxmoxSettings.getInstance()
        if (settings.host.isBlank()) {
            ProxmoxNotifier.warn(project, "Proxmox host is not configured")
            return
        }
        val baseUrl = ProxmoxUrls.baseUrl(settings.host, settings.port)
        BrowserUtil.browse(ProxmoxUrls.guestPanel(baseUrl, guest.node, guest.type, guest.vmid))
    }

    private fun consoleUrl(baseUrl: String, guest: ProxmoxGuest, kind: ConsolePreference): String =
        when {
            // SPICE only exists for QEMU; a container falls back to its serial console.
            kind == ConsolePreference.SPICE && guest.type == GuestType.QEMU ->
                ProxmoxUrls.spiceConsole(baseUrl, guest.node, guest.vmid)

            kind == ConsolePreference.XTERM || guest.type == GuestType.LXC ->
                ProxmoxUrls.xtermConsole(baseUrl, guest.node, guest.type, guest.vmid)

            else -> ProxmoxUrls.console(baseUrl, guest.node, guest.type, guest.vmid)
        }
}
