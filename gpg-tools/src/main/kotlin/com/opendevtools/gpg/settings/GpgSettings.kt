package com.opendevtools.gpg.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.XmlSerializerUtil
import com.opendevtools.gpg.cli.GpgProcess
import com.opendevtools.gpg.service.GpgLocator

/**
 * Where gpg is and how the plugin should call it.
 *
 * There is deliberately no credential store here and no password safe entry: passphrases are
 * gpg-agent's business, asked for through its own pinentry dialog, so the plugin has nothing
 * secret to keep.
 */
@Service(Service.Level.APP)
@State(name = "GpgToolsSettings", storages = [Storage("gpg-tools.xml")])
class GpgSettings : PersistentStateComponent<GpgSettings> {

    /** Empty means "find it", which is what a fresh install does on first use. */
    var executablePath: String = ""

    /** An alternative `GNUPGHOME`; empty uses whatever the executable defaults to. */
    var gnupgHome: String = ""

    var keyserver: String = DEFAULT_KEYSERVER

    /** Fingerprint of the key that pre-fills the signing dialogs. */
    var defaultSigningKey: String = ""

    /** ASCII armor rather than binary, which is what most people want to paste or mail. */
    var armorByDefault: Boolean = true

    /** Adds the default signing key as a recipient, so encrypted files stay readable to the user. */
    var encryptToSelf: Boolean = true

    /** Certifications made here stay in this keyring and are never pushed to a keyserver. */
    var localCertificationsOnly: Boolean = false

    var confirmDestructiveActions: Boolean = true

    /** Narrows the tree to certificates whose private half this keyring holds. */
    var showOnlySecretKeys: Boolean = false

    /** Revoked and expired certificates are still shown by default; they are often the point. */
    var showInvalidCertificates: Boolean = true

    /** Reads certifications when listing, which is slow on a large keyring and off by default. */
    var loadSignatures: Boolean = false

    override fun getState(): GpgSettings = this

    override fun loadState(state: GpgSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    /**
     * The executable to run: the configured one, or whatever detection turns up.
     *
     * Detection is not written back into the settings, so a machine that later installs GnuPG
     * somewhere else is picked up without the user having to clear a stale path.
     */
    fun resolveExecutable(): String? =
        executablePath.trim().takeIf { GpgProcess.isExecutable(it) } ?: GpgLocator.detect()

    fun homeOrNull(): String? = gnupgHome.trim().takeIf { it.isNotEmpty() }

    val isConfigured: Boolean get() = resolveExecutable() != null

    companion object {
        const val DEFAULT_KEYSERVER = "hkps://keys.openpgp.org"

        fun getInstance(): GpgSettings =
            ApplicationManager.getApplication().getService(GpgSettings::class.java)

        /** Fired when the executable, the keyring or a preference changes. */
        @JvmField
        val TOPIC: Topic<GpgSettingsListener> =
            Topic.create("GPG settings", GpgSettingsListener::class.java)

        fun fireChanged() {
            ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).gpgSettingsChanged()
        }
    }
}

fun interface GpgSettingsListener {
    fun gpgSettingsChanged()
}
