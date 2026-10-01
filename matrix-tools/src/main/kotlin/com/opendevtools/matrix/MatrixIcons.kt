package com.opendevtools.matrix

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/** Icons of the plugin; each has a `_dark.svg` sibling that the loader picks up automatically. */
object MatrixIcons {

    @JvmField
    val ToolWindow: Icon = load("/icons/matrix13.svg")

    @JvmField
    val Room: Icon = load("/icons/room.svg")

    @JvmField
    val RoomUnread: Icon = load("/icons/room_unread.svg")

    @JvmField
    val RoomEncrypted: Icon = load("/icons/room_encrypted.svg")

    @JvmField
    val DirectMessage: Icon = load("/icons/direct.svg")

    @JvmField
    val Invite: Icon = load("/icons/invite.svg")

    @JvmField
    val Send: Icon = load("/icons/send.svg")

    private fun load(path: String): Icon = IconLoader.getIcon(path, MatrixIcons::class.java)
}
