package com.opendevtools.vaultwarden

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/** Icons of the plugin; each has a `_dark.svg` sibling that the loader picks up automatically. */
object VaultwardenIcons {

    @JvmField
    val ToolWindow: Icon = load("/icons/vaultwarden13.svg")

    @JvmField
    val Login: Icon = load("/icons/login.svg")

    @JvmField
    val Note: Icon = load("/icons/note.svg")

    @JvmField
    val Card: Icon = load("/icons/card.svg")

    @JvmField
    val Identity: Icon = load("/icons/identity.svg")

    @JvmField
    val SshKey: Icon = load("/icons/sshkey.svg")

    @JvmField
    val Send: Icon = load("/icons/send.svg")

    @JvmField
    val Generator: Icon = load("/icons/generator.svg")

    @JvmField
    val Report: Icon = load("/icons/report.svg")

    @JvmField
    val Attachment: Icon = load("/icons/attachment.svg")

    private fun load(path: String): Icon = IconLoader.getIcon(path, VaultwardenIcons::class.java)
}
