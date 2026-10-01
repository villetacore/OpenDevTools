package com.opendevtools.forge.toolwindow

import com.intellij.openapi.actionSystem.DataKey
import com.opendevtools.forge.model.ForgeBranch
import com.opendevtools.forge.model.ForgeIssue
import com.opendevtools.forge.model.ForgePipeline
import com.opendevtools.forge.model.ForgePullRequest
import com.opendevtools.forge.model.ForgeRelease
import com.opendevtools.forge.model.ForgeRepository

/** What the actions read out of the action context; all of it is filled by [ForgePanel]. */
object ForgeDataKeys {

    @JvmField
    val PANEL: DataKey<ForgePanel> = DataKey.create("forge.panel")

    @JvmField
    val REPOSITORY: DataKey<ForgeRepository> = DataKey.create("forge.selected.repository")

    @JvmField
    val ISSUE: DataKey<ForgeIssue> = DataKey.create("forge.selected.issue")

    @JvmField
    val PULL_REQUEST: DataKey<ForgePullRequest> = DataKey.create("forge.selected.pullRequest")

    @JvmField
    val PIPELINE: DataKey<ForgePipeline> = DataKey.create("forge.selected.pipeline")

    @JvmField
    val RELEASE: DataKey<ForgeRelease> = DataKey.create("forge.selected.release")

    @JvmField
    val BRANCH: DataKey<ForgeBranch> = DataKey.create("forge.selected.branch")
}
