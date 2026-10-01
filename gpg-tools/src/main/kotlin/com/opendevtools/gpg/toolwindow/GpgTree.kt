package com.opendevtools.gpg.toolwindow

import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.JBColor
import com.opendevtools.core.format.Formats
import com.opendevtools.core.ui.KeyedTreeItem
import com.opendevtools.gpg.GpgIcons
import com.opendevtools.gpg.model.GpgKey
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

/** What a row of the certificate tree can be. */
sealed class GpgTreeItem : KeyedTreeItem {

    /** "My Certificates" and "Other Certificates", the split Kleopatra opens on. */
    class Group(val title: String, val count: Int) : GpgTreeItem() {
        override val key: String get() = "group:$title"
    }

    class Certificate(val certificate: GpgKey) : GpgTreeItem() {
        override val key: String get() = "key:" + certificate.fingerprint
    }

    /** A message where a tree would otherwise be empty: no gpg, no keys, a failed refresh. */
    class Message(val text: String) : GpgTreeItem() {
        override val key: String get() = "message:$text"
    }
}

/**
 * Draws a certificate row.
 *
 * A revoked or expired certificate still has to be visible - it is often exactly what the user
 * came to look at - so it is struck through rather than hidden, the way Kleopatra greys it out.
 */
class GpgTreeCellRenderer : ColoredTreeCellRenderer() {

    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        when (val item = (value as? DefaultMutableTreeNode)?.userObject as? GpgTreeItem) {
            is GpgTreeItem.Group -> {
                append(item.title, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append("  " + item.count, SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }

            is GpgTreeItem.Certificate -> renderCertificate(item.certificate)

            is GpgTreeItem.Message -> append(item.text, SimpleTextAttributes.GRAYED_ATTRIBUTES)

            null -> append(value?.toString().orEmpty())
        }
    }

    private fun renderCertificate(key: GpgKey) {
        val unusable = key.isRevoked || key.isExpired || key.disabled
        icon = when {
            unusable -> GpgIcons.InvalidCertificate
            key.hasSecret -> GpgIcons.SecretCertificate
            else -> GpgIcons.Certificate
        }

        val nameAttributes = when {
            key.isRevoked -> SimpleTextAttributes(
                SimpleTextAttributes.STYLE_STRIKEOUT,
                JBColor.RED,
            )

            unusable -> SimpleTextAttributes.GRAYED_ATTRIBUTES
            else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
        }
        append(key.displayName, nameAttributes)
        append("  " + key.shortKeyId, SimpleTextAttributes.GRAYED_ATTRIBUTES)

        // The one thing a reader needs beyond the name is why a row looks wrong.
        val note = when {
            key.isRevoked -> "revoked"
            key.disabled -> "disabled"
            key.isExpired -> "expired " + Formats.date(key.expires)
            key.expires > 0 -> "until " + Formats.date(key.expires)
            else -> null
        }
        note?.let { append("  $it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
        toolTipText = key.readableFingerprint
    }
}
