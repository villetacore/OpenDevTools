package com.opendevtools.core.async

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project

/** Outcome of background work, so the EDT continuation handles success and failure in one place. */
sealed class Outcome<out T> {
    class Success<T>(val value: T) : Outcome<T>()
    class Failure(val error: Throwable) : Outcome<Nothing>() {
        val message: String get() = error.message?.takeIf { it.isNotBlank() } ?: error.toString()
    }
}

/**
 * Every server call in both plugins blocks, so all of them go through these helpers rather than
 * touching `executeOnPooledThread` directly. Results are delivered on the EDT with
 * [ModalityState.any] so a refresh still lands while a modal dialog is open.
 */
object Background {

    /** Fire-and-forget work whose result updates the UI; nothing is shown in the progress bar. */
    fun <T> run(compute: () -> T, onDone: (Outcome<T>) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val outcome = try {
                Outcome.Success(compute())
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Throwable) {
                Outcome.Failure(e)
            }
            onEdt { onDone(outcome) }
        }
    }

    /** Work the user should see in the progress bar, for example a power action or a sync. */
    fun <T> runVisible(
        project: Project?,
        title: String,
        cancellable: Boolean = true,
        compute: (ProgressIndicator) -> T,
        onDone: (Outcome<T>) -> Unit = {},
    ) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, cancellable) {
            override fun run(indicator: ProgressIndicator) {
                val outcome = try {
                    Outcome.Success(compute(indicator))
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Throwable) {
                    Outcome.Failure(e)
                }
                onEdt { onDone(outcome) }
            }
        })
    }

    fun onEdt(block: () -> Unit) =
        ApplicationManager.getApplication().invokeLater({ block() }, ModalityState.any())
}
