package com.opendevtools.proxmox.toolwindow.details

import com.intellij.openapi.project.Project
import com.intellij.ui.PopupHandler
import com.intellij.util.ui.ColumnInfo
import com.opendevtools.core.format.Formats
import com.opendevtools.proxmox.ProxmoxIcons
import com.opendevtools.proxmox.model.GuestSnapshot
import com.opendevtools.proxmox.model.ProxmoxGuest
import com.opendevtools.proxmox.service.ProxmoxService

/**
 * Snapshots of the selected guest, in chain order with the depth shown by indentation, which is
 * how the web UI makes a branched chain readable in a flat table.
 */
class SnapshotsTab(project: Project) : TableDetailTab<SnapshotsTab.Row>(
    project,
    "Snapshots",
    ProxmoxIcons.Snapshot,
    "Select a virtual machine or a container",
) {

    class Row(val snapshot: GuestSnapshot, val depth: Int) {
        val indentedName: String get() = "    ".repeat(depth) + snapshot.name
    }

    private var currentGuest: ProxmoxGuest? = null

    init {
        PopupHandler.installPopupMenu(table, "ProxmoxTools.SnapshotMenu", "ProxmoxSnapshotPopup")
    }

    override fun supports(target: DetailTarget): Boolean = target is DetailTarget.Guest

    override fun columns(): Array<ColumnInfo<Row, *>> = arrayOf(
        column("Name", 20) { it.indentedName },
        column("Created", 18) {
            if (it.snapshot.isCurrentState) "-" else Formats.timestamp(it.snapshot.createdAt)
        },
        column("RAM", 5) { if (it.snapshot.withRam) "yes" else "" },
        column("Description", 40) { it.snapshot.description },
    )

    override fun loadData(target: DetailTarget) {
        val guest = (target as? DetailTarget.Guest)?.guest ?: run {
            currentGuest = null
            setRows(emptyList())
            return
        }
        currentGuest = guest
        if (guest.template) {
            setRows(emptyList(), "Templates have no snapshots")
            return
        }
        showLoading()
        load(
            compute = { ProxmoxService.getInstance().snapshots(guest) },
            onError = { showError(it) },
            onSuccess = { setRows(flatten(it), "No snapshots") },
        )
    }

    fun currentGuest(): ProxmoxGuest? = currentGuest

    fun selectedSnapshot(): GuestSnapshot? = selectedRow()?.snapshot

    /** Walks the parent chain so every snapshot appears under the one it was taken from. */
    private fun flatten(snapshots: List<GuestSnapshot>): List<Row> {
        val byParent = snapshots.groupBy { it.parent }
        val result = mutableListOf<Row>()

        fun emit(parent: String?, depth: Int) {
            byParent[parent].orEmpty()
                .sortedBy { if (it.isCurrentState) Long.MAX_VALUE else it.createdAt }
                .forEach { snapshot ->
                    result.add(Row(snapshot, depth))
                    emit(snapshot.name, depth + 1)
                }
        }
        emit(null, 0)

        // A snapshot whose parent is missing from the list would otherwise disappear silently.
        val emitted = result.map { it.snapshot.name }.toSet()
        snapshots.filterNot { it.name in emitted }.forEach { result.add(Row(it, 0)) }
        return result
    }
}
