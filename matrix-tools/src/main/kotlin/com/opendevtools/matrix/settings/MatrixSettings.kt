package com.opendevtools.matrix.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.XmlSerializerUtil
import com.opendevtools.core.secrets.SecretStore
import com.opendevtools.matrix.api.MatrixConfig
import com.opendevtools.matrix.model.RoomGrouping

/**
 * Connection preferences. The access token lives in the IDE password safe; the master password
 * used to obtain it is never stored at all.
 */
@Service(Service.Level.APP)
@State(name = "MatrixToolsSettings", storages = [Storage("matrix-tools.xml")])
class MatrixSettings : PersistentStateComponent<MatrixSettings> {

    var homeserverUrl: String = ""
    var userId: String = ""
    var deviceId: String = ""
    var verifySsl: Boolean = true

    var grouping: RoomGrouping = RoomGrouping.BY_ACTIVITY
    var showEncryptedRooms: Boolean = true

    /** Seconds the sync request parks on the server before returning empty. */
    var syncTimeoutSeconds: Int = 30

    /** How many messages a room's initial sync and each "load earlier" page fetch. */
    var messagePageSize: Int = 40

    var notifyOnMention: Boolean = true
    var notifyOnAnyMessage: Boolean = false

    /** Marks a room read as soon as it is opened, the way most clients behave. */
    var markReadOnOpen: Boolean = true

    override fun getState(): MatrixSettings = this

    override fun loadState(state: MatrixSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    fun credentialKey(): String = homeserverUrl.trim() + "|" + userId.trim()

    fun toConfig(): MatrixConfig = MatrixConfig(
        homeserverUrl = homeserverUrl.trim(),
        userId = userId.trim(),
        verifySsl = verifySsl,
    )

    val isComplete: Boolean
        get() = homeserverUrl.isNotBlank() && !MatrixCredentials.get(credentialKey()).isNullOrBlank()

    companion object {
        fun getInstance(): MatrixSettings =
            ApplicationManager.getApplication().getService(MatrixSettings::class.java)

        /** Fired when the session or the preferences change. */
        @JvmField
        val TOPIC: Topic<MatrixStateListener> =
            Topic.create("Matrix state", MatrixStateListener::class.java)

        fun fireStateChanged() {
            ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).matrixStateChanged()
        }
    }
}

fun interface MatrixStateListener {
    fun matrixStateChanged()
}

object MatrixCredentials {

    private val store = SecretStore("Matrix Tools")

    fun get(key: String): String? = store.get(key)

    fun set(key: String, secret: String?) = store.set(key, secret)
}
