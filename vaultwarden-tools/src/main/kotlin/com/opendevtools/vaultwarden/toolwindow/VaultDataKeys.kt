package com.opendevtools.vaultwarden.toolwindow

import com.intellij.openapi.actionSystem.DataKey
import com.opendevtools.vaultwarden.model.VaultFolder
import com.opendevtools.vaultwarden.model.VaultItem
import com.opendevtools.vaultwarden.model.VaultSend

/** What the actions read out of the action context; all of it is filled by [VaultwardenPanel]. */
object VaultDataKeys {

    @JvmField
    val PANEL: DataKey<VaultwardenPanel> = DataKey.create("vaultwarden.panel")

    @JvmField
    val ITEMS: DataKey<List<VaultItem>> = DataKey.create("vaultwarden.selected.items")

    /** The folder branch the selection sits in, for rename and delete. */
    @JvmField
    val FOLDER: DataKey<VaultFolder> = DataKey.create("vaultwarden.selected.folder")

    @JvmField
    val SEND: DataKey<VaultSend> = DataKey.create("vaultwarden.selected.send")
}
