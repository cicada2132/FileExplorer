package com.offlinefiles.explorer.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class Phase { PREPARING, RUNNING }

/** Snapshot of a long-running operation. Byte totals may be 0 when not known yet. */
data class Progress(
    val phase: Phase = Phase.PREPARING,
    val totalBytes: Long = 0,
    val doneBytes: Long = 0,
    val totalItems: Int = 0,
    val doneItems: Int = 0,
    val currentName: String? = null,
) {
    /** 0f..1f, or null when the operation can't estimate (indeterminate bar). */
    val fraction: Float?
        get() = when {
            phase == Phase.PREPARING -> null
            totalBytes > 0 -> (doneBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
            totalItems > 0 -> (doneItems.toFloat() / totalItems).coerceIn(0f, 1f)
            else -> null
        }
}

/** A name clash while copying, moving or extracting. */
data class Conflict(
    val name: String,
    val targetPath: String,
    val sourceIsDirectory: Boolean,
    val targetIsDirectory: Boolean,
    val sourceSize: Long,
    val sourceModified: Long,
    val targetSize: Long,
    val targetModified: Long,
)

enum class ConflictAction {
    /** Replace the existing file (for two folders: merge their contents). */
    OVERWRITE,
    SKIP,
    /** Keep both by giving the new item a numbered name, e.g. "photo (1).jpg". */
    KEEP_BOTH,
}

data class ConflictDecision(val action: ConflictAction, val applyToAll: Boolean)

interface OperationListener {
    fun onProgress(progress: Progress)
    suspend fun onConflict(conflict: Conflict): ConflictDecision
}

enum class FailureReason { NOT_FOUND, ACCESS_DENIED, INTO_ITSELF, ALREADY_EXISTS, NO_SPACE, UNSAFE_PATH, IO_ERROR }

data class Failure(val path: String, val reason: FailureReason, val detail: String? = null)

data class OperationResult(
    /** Items finished successfully (top-level items for copy/move/delete, files for archives). */
    val succeeded: Int,
    val skipped: Int = 0,
    val failures: List<Failure> = emptyList(),
    /** Newly created top-level items, used to highlight them in the list. */
    val outputs: List<java.io.File> = emptyList(),
)

/** Remembers an "apply to all" answer for the rest of one operation. */
internal class ConflictPolicy(private val listener: OperationListener) {
    private var remembered: ConflictAction? = null

    suspend fun decide(conflict: Conflict): ConflictAction {
        remembered?.let { return it }
        val decision = listener.onConflict(conflict)
        if (decision.applyToAll) remembered = decision.action
        return decision.action
    }
}

/** Tracks progress, throttles callbacks and checks for cancellation. */
internal class ProgressTracker(private val listener: OperationListener) {
    private var state = Progress()
    private var lastEmit = 0L

    fun preparing(name: String? = null) {
        state = state.copy(phase = Phase.PREPARING, currentName = name)
        emit(force = false)
    }

    fun start(totalBytes: Long, totalItems: Int) {
        state = state.copy(phase = Phase.RUNNING, totalBytes = totalBytes, totalItems = totalItems)
        emit(force = true)
    }

    fun current(name: String) {
        state = state.copy(currentName = name)
        emit(force = false)
    }

    suspend fun addBytes(n: Long) {
        currentCoroutineContext().ensureActive()
        state = state.copy(doneBytes = state.doneBytes + n)
        emit(force = false)
    }

    suspend fun itemDone(bytesIfSkipped: Long = 0) {
        currentCoroutineContext().ensureActive()
        state = state.copy(doneItems = state.doneItems + 1, doneBytes = state.doneBytes + bytesIfSkipped)
        emit(force = false)
    }

    fun finish() = emit(force = true)

    private fun emit(force: Boolean) {
        val now = System.nanoTime()
        if (force || now - lastEmit > 60_000_000L) {
            lastEmit = now
            listener.onProgress(state)
        }
    }
}
