package com.opendevtools.forge.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.XmlSerializerUtil
import com.opendevtools.core.secrets.SecretStore
import com.opendevtools.forge.model.ForgeAccount
import com.opendevtools.forge.model.ForgeKind
import com.opendevtools.forge.model.IssueState
import java.util.UUID

/**
 * The serialized form of one account. The settings framework persists plain beans, so this is a
 * mutable class with a no-argument constructor rather than the immutable [ForgeAccount] the rest
 * of the plugin passes around.
 */
class ForgeAccountState() {

    var id: String = ""
    var kind: ForgeKind = ForgeKind.GITHUB
    var displayName: String = ""
    var baseUrl: String = ""
    var verifySsl: Boolean = true
    var username: String = ""

    constructor(account: ForgeAccount) : this() {
        id = account.id
        kind = account.kind
        displayName = account.displayName
        baseUrl = account.baseUrl
        verifySsl = account.verifySsl
        username = account.username.orEmpty()
    }

    fun toAccount(): ForgeAccount = ForgeAccount(
        id = id,
        kind = kind,
        displayName = displayName,
        baseUrl = baseUrl,
        verifySsl = verifySsl,
        username = username.takeIf { it.isNotBlank() },
    )
}

/**
 * Plugin preferences. Several accounts can be configured at once - a work GitHub, a personal one,
 * a self-hosted Gitea - and each one's token lives in the IDE password safe under its own id.
 */
@Service(Service.Level.APP)
@State(name = "GitForgeToolsSettings", storages = [Storage("git-forge-tools.xml")])
class ForgeSettings : PersistentStateComponent<ForgeSettings> {

    var accounts: MutableList<ForgeAccountState> = mutableListOf()

    var defaultIssueState: IssueState = IssueState.OPEN
    var showForks: Boolean = true
    var showPrivate: Boolean = true

    /** Highlights the repository matching the open project's git remote. */
    var matchProjectRemote: Boolean = true

    var confirmDestructiveActions: Boolean = true

    override fun getState(): ForgeSettings = this

    override fun loadState(state: ForgeSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    fun accountList(): List<ForgeAccount> = accounts.map { it.toAccount() }

    fun account(id: String): ForgeAccount? = accounts.firstOrNull { it.id == id }?.toAccount()

    /** Replaces the whole set, which is what the settings table hands back on apply. */
    fun replaceAccounts(updated: List<ForgeAccount>) {
        val removed = accounts.map { it.id }.toSet() - updated.map { it.id }.toSet()
        removed.forEach { id ->
            accounts.firstOrNull { it.id == id }?.let { ForgeCredentials.set(it.toAccount().credentialKey, null) }
        }
        accounts = updated.map { ForgeAccountState(it) }.toMutableList()
    }

    companion object {
        fun getInstance(): ForgeSettings =
            ApplicationManager.getApplication().getService(ForgeSettings::class.java)

        fun newAccountId(): String = UUID.randomUUID().toString()

        /** Fired when accounts or preferences change. */
        @JvmField
        val TOPIC: Topic<ForgeSettingsListener> =
            Topic.create("Git forge settings", ForgeSettingsListener::class.java)

        fun fireChanged() {
            ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).forgeSettingsChanged()
        }
    }
}

fun interface ForgeSettingsListener {
    fun forgeSettingsChanged()
}

object ForgeCredentials {

    private val store = SecretStore("Git Forge Tools")

    fun get(key: String): String? = store.get(key)

    fun set(key: String, secret: String?) = store.set(key, secret)
}
