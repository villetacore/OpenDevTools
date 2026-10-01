package com.opendevtools.gpg

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/** Icons of the plugin; each has a `_dark.svg` sibling that the loader picks up automatically. */
object GpgIcons {

    @JvmField
    val ToolWindow: Icon = load("/icons/gpg13.svg")

    /** A certificate whose private half is not in this keyring. */
    @JvmField
    val Certificate: Icon = load("/icons/certificate.svg")

    /** One of the user's own certificates - the solid bow is what tells the two apart. */
    @JvmField
    val SecretCertificate: Icon = load("/icons/certificate_secret.svg")

    /** Revoked, expired or disabled; the tree strikes the text through as well. */
    @JvmField
    val InvalidCertificate: Icon = load("/icons/certificate_invalid.svg")

    @JvmField
    val Subkey: Icon = load("/icons/subkey.svg")

    @JvmField
    val UserId: Icon = load("/icons/userid.svg")

    @JvmField
    val Signature: Icon = load("/icons/signature.svg")

    @JvmField
    val Keyserver: Icon = load("/icons/keyserver.svg")

    private fun load(path: String): Icon = IconLoader.getIcon(path, GpgIcons::class.java)
}
