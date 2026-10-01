package com.opendevtools.core.ui

import com.intellij.ui.SimpleListCellRenderer

/**
 * Renders a combo box entry by its display text, with [nullText] for the null entry that a
 * "no folder" or "same as source" choice is modelled as.
 *
 * `SimpleListCellRenderer.create(String, Function)` would do the same, but it is scheduled for
 * removal from the platform; this goes through the customizer overload that replaces it.
 */
fun <T> listRenderer(nullText: String = "", text: (T) -> String?): SimpleListCellRenderer<T> =
    SimpleListCellRenderer.create { label, value, _ ->
        label.text = if (value == null) nullText else text(value).orEmpty()
    }
