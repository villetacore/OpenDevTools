package com.opendevtools.proxmox.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.XmlSerializerUtil
import com.opendevtools.core.secrets.SecretStore
import com.opendevtools.proxmox.model.TreeGrouping

enum class ProxmoxAuthType { API_TOKEN, PASSWORD }

/** Which console the plugin opens on a double click. */
enum class ConsolePreference(val title: String) {
    NOVNC("noVNC (graphical)"),
    XTERM("xterm.js (serial)"),
    SPICE("SPICE (external client)"),
}

/**
 * Non-secret part of the Proxmox VE connection plus tool window preferences. The secret
 * (API token value or password) lives in the IDE password safe, see [ProxmoxCredentials].
 */
@Service(Service.Level.APP)
@State(name = "ProxmoxToolsSettings", storages = [Storage("proxmox-tools.xml")])
class ProxmoxSettings : PersistentStateComponent<ProxmoxSettings> {

    var host: String = ""
    var port: Int = 8006
    var authType: ProxmoxAuthType = ProxmoxAuthType.API_TOKEN
    var user: String = "root@pam"
    var tokenName: String = ""
    var verifySsl: Boolean = false

    var autoRefreshSeconds: Int = 0
    var grouping: TreeGrouping = TreeGrouping.BY_NODE
    var consolePreference: ConsolePreference = ConsolePreference.NOVNC
    var showTemplates: Boolean = true
    var showStoragesInTree: Boolean = true
    var confirmDestructiveActions: Boolean = true

    /** Number of task rows the Tasks tab requests per node. */
    var taskHistoryLimit: Int = 200

    override fun getState(): ProxmoxSettings = this

    override fun loadState(state: ProxmoxSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    /** Key under which the secret for the current host/user pair is stored. */
    fun credentialKey(): String = "$host|$user|${authType.name}"

    fun toConfig(secret: String = ProxmoxCredentials.get(credentialKey()).orEmpty()): ProxmoxConnectionConfig =
        ProxmoxConnectionConfig(
            host = host.trim(),
            port = port,
            authType = authType,
            user = user.trim(),
            tokenName = tokenName.trim(),
            secret = secret,
            verifySsl = verifySsl,
        )

    companion object {
        fun getInstance(): ProxmoxSettings =
            ApplicationManager.getApplication().getService(ProxmoxSettings::class.java)

        /** Fired after the connection settings or the tool window preferences changed. */
        @JvmField
        val TOPIC: Topic<ProxmoxSettingsListener> =
            Topic.create("Proxmox settings", ProxmoxSettingsListener::class.java)

        fun fireChanged() {
            ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).settingsChanged()
        }
    }
}

fun interface ProxmoxSettingsListener {
    fun settingsChanged()
}

object ProxmoxCredentials {

    private val store = SecretStore("Proxmox Tools")

    fun get(key: String): String? = store.get(key)

    fun set(key: String, secret: String?) = store.set(key, secret)
}

data class ProxmoxConnectionConfig(
    val host: String,
    val port: Int,
    val authType: ProxmoxAuthType,
    val user: String,
    val tokenName: String,
    val secret: String,
    val verifySsl: Boolean,
) {
    val baseUrl: String get() = "https://$host:$port"
    val apiUrl: String get() = "$baseUrl/api2/json"
    val tokenId: String get() = "$user!$tokenName"

    val isComplete: Boolean
        get() = host.isNotBlank() && user.isNotBlank() && secret.isNotBlank() &&
            (authType != ProxmoxAuthType.API_TOKEN || tokenName.isNotBlank())
}
