package com.opendevtools.matrix.toolwindow

import com.intellij.openapi.actionSystem.DataKey
import com.opendevtools.matrix.model.MatrixRoom

/** What the actions read out of the action context; all of it is filled by [MatrixPanel]. */
object MatrixDataKeys {

    @JvmField
    val PANEL: DataKey<MatrixPanel> = DataKey.create("matrix.panel")

    @JvmField
    val ROOM: DataKey<MatrixRoom> = DataKey.create("matrix.selected.room")
}
