package com.opendevtools.vaultwarden.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.XmlSerializerUtil
import com.opendevtools.core.secrets.SecretStore
import com.opendevtools.vaultwarden.api.ApiConfig
import com.opendevtools.vaultwarden.model.VaultGrouping
import java.util.UUID

/**
 * Plugin preferences. The API key secret lives in the IDE password safe; the master password is
 * never stored anywhere, and neither are the vault keys.
 */
@Service(Service.Level.APP)
@State(name = "VaultwardenToolsSettings", storages = [Storage("vaultwarden-tools.xml")])
class VaultwardenSettings : PersistentStateComponent<VaultwardenSettings> {

    var serverUrl: String = ""

    /** Personal API key client id, of the form `user.<uuid>`. */
    var clientId: String = ""

    /** Stable device id reported to the server; generated once. */
    var deviceIdentifier: String = ""

    var verifySsl: Boolean = true
    var clipboardClearSeconds: Int = 30
    var autoLockMinutes: Int = 15

    var grouping: VaultGrouping = VaultGrouping.BY_FOLDER
    var showFavoritesFirst: Boolean = true
    var confirmDestructiveActions: Boolean = true

    /** Where "Save Attachment" and "Export" start; empty means the user's home directory. */
    var lastDownloadDirectory: String = ""

    // Defaults of the password generator, so a person's preferred shape survives a restart.
    var generatorLength: Int = 16
    var generatorUppercase: Boolean = true
    var generatorLowercase: Boolean = true
    var generatorDigits: Boolean = true
    var generatorSpecial: Boolean = true
    var generatorMinDigits: Int = 1
    var generatorMinSpecial: Int = 1
    var generatorAvoidAmbiguous: Boolean = false
    var generatorPassphraseWords: Int = 4
    var generatorPassphraseSeparator: String = "-"
    var generatorPassphraseCapitalize: Boolean = true
    var generatorPassphraseNumber: Boolean = true

    override fun getState(): VaultwardenSettings = this

    override fun loadState(state: VaultwardenSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    fun credentialKey(): String = serverUrl.trim() + "|" + clientId.trim()

    fun deviceId(): String {
        if (deviceIdentifier.isBlank()) {
            deviceIdentifier = UUID.randomUUID().toString()
        }
        return deviceIdentifier
    }

    fun toApiConfig(secret: String = VaultwardenCredentials.get(credentialKey()).orEmpty()): ApiConfig =
        ApiConfig(
            serverUrl = serverUrl.trim(),
            clientId = clientId.trim(),
            clientSecret = secret,
            deviceIdentifier = deviceId(),
            verifySsl = verifySsl,
        )

    companion object {
        fun getInstance(): VaultwardenSettings =
            ApplicationManager.getApplication().getService(VaultwardenSettings::class.java)

        /** Fired when the vault is unlocked, locked, reloaded, or the settings change. */
        @JvmField
        val TOPIC: Topic<VaultStateListener> =
            Topic.create("Vaultwarden state", VaultStateListener::class.java)

        fun fireStateChanged() {
            ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).vaultStateChanged()
        }
    }
}

fun interface VaultStateListener {
    fun vaultStateChanged()
}

object VaultwardenCredentials {

    private val store = SecretStore("Vaultwarden Tools")

    fun get(key: String): String? = store.get(key)

    fun set(key: String, secret: String?) = store.set(key, secret)
}
