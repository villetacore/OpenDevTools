package com.opendevtools.core.ui

import com.intellij.ui.treeStructure.Tree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/** A tree user object with an identity that survives a refresh rebuilding the whole model. */
interface KeyedTreeItem {
    val key: String
}

/**
 * Captures which rows are expanded and selected, so a periodic refresh that rebuilds the model
 * does not collapse the tree under the user or drop their selection.
 */
class TreeStateSnapshot(val expanded: Set<String>, val selected: Set<String>) {
    val isEmpty: Boolean get() = expanded.isEmpty() && selected.isEmpty()
}

object TreeState {

    fun capture(tree: Tree): TreeStateSnapshot {
        val expanded = LinkedHashSet<String>()
        for (row in 0 until tree.rowCount) {
            if (!tree.isExpanded(row)) continue
            keyOf(tree.getPathForRow(row))?.let { expanded.add(it) }
        }
        val selected = tree.selectionPaths.orEmpty().mapNotNull { keyOf(it) }.toSet()
        return TreeStateSnapshot(expanded, selected)
    }

    fun restore(tree: Tree, snapshot: TreeStateSnapshot) {
        if (snapshot.isEmpty) return
        val root = tree.model.root as? DefaultMutableTreeNode ?: return
        val selection = ArrayList<TreePath>()
        walk(root) { node, item ->
            val path = TreePath(node.path)
            if (item.key in snapshot.expanded) tree.expandPath(path)
            if (item.key in snapshot.selected) selection.add(path)
        }
        if (selection.isNotEmpty()) {
            tree.selectionPaths = selection.toTypedArray()
            tree.scrollPathToVisible(selection.first())
        }
    }

    /** Expands the first [levels] levels below the invisible root; the usual "sensible default". */
    fun expandLevels(tree: Tree, levels: Int) {
        val root = tree.model.root as? DefaultMutableTreeNode ?: return
        expandRecursively(tree, root, levels)
    }

    fun selectByKey(tree: Tree, key: String): Boolean {
        val root = tree.model.root as? DefaultMutableTreeNode ?: return false
        var found: TreePath? = null
        walk(root) { node, item ->
            if (found == null && item.key == key) found = TreePath(node.path)
        }
        return found?.let {
            tree.selectionPath = it
            tree.scrollPathToVisible(it)
            true
        } ?: false
    }

    fun walk(root: DefaultMutableTreeNode, consumer: (DefaultMutableTreeNode, KeyedTreeItem) -> Unit) {
        val queue = ArrayDeque<DefaultMutableTreeNode>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            (current.userObject as? KeyedTreeItem)?.let { consumer(current, it) }
            for (i in 0 until current.childCount) {
                queue.add(current.getChildAt(i) as DefaultMutableTreeNode)
            }
        }
    }

    private fun expandRecursively(tree: Tree, node: DefaultMutableTreeNode, remaining: Int) {
        if (remaining <= 0) return
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as DefaultMutableTreeNode
            if (child.childCount == 0) continue
            tree.expandPath(TreePath(child.path))
            expandRecursively(tree, child, remaining - 1)
        }
    }

    private fun keyOf(path: TreePath?): String? =
        ((path?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? KeyedTreeItem)?.key
}
