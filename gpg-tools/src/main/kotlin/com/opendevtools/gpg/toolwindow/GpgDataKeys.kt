package com.opendevtools.gpg.toolwindow

import com.intellij.openapi.actionSystem.DataKey
import com.opendevtools.gpg.model.GpgKey
import com.opendevtools.gpg.model.GpgSubkey
import com.opendevtools.gpg.model.GpgUserId

/** What the actions read out of the action context; all of it is filled by [GpgPanel]. */
object GpgDataKeys {

    @JvmField
    val PANEL: DataKey<GpgPanel> = DataKey.create("gpg.panel")

    @JvmField
    val CERTIFICATE: DataKey<GpgKey> = DataKey.create("gpg.selected.certificate")

    /** The row selected in the User IDs tab, for the actions that work on one identity. */
    @JvmField
    val USER_ID: DataKey<GpgUserId> = DataKey.create("gpg.selected.userid")

    /** The rows selected in the Subkeys tab, so an expiry change can name them. */
    @JvmField
    val SUBKEYS: DataKey<List<GpgSubkey>> = DataKey.create("gpg.selected.subkeys")
}
