package com.offlinefiles.explorer.ops

import com.offlinefiles.explorer.core.Conflict
import com.offlinefiles.explorer.core.ConflictDecision
import com.offlinefiles.explorer.core.OperationListener
import com.offlinefiles.explorer.core.OperationResult
import com.offlinefiles.explorer.core.Progress
import com.offlinefiles.explorer.core.archive.ArchiveType
import com.offlinefiles.explorer.core.archive.CompressionLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the user asked for; kept with the result so the UI can react (retry with password, open, …). */
sealed interface OpRequest {
    data class Transfer(val sources: List<String>, val destDir: String, val move: Boolean) : OpRequest
    data class Delete(val paths: List<String>) : OpRequest
    data class Compress(
        val sources: List<String>,
        val output: String,
        val type: ArchiveType,
        val level: CompressionLevel,
        val password: CharArray?,
    ) : OpRequest

    data class Extract(
        val archive: String,
        val destDir: String,
        val password: CharArray?,
        val selection: Set<String>? = null,
        val stripPrefix: String = "",
        /** Set when a single entry is extracted to the cache just to open it. */
        val openAfter: String? = null,
        /** Extract into a new folder named after the archive (unless it already has one top folder of that name). */
        val smartFolder: Boolean = false,
    ) : OpRequest
}

sealed interface OpOutcome {
    data class Done(val result: OperationResult) : OpOutcome
    data class Failed(val error: Throwable) : OpOutcome
    data object Cancelled : OpOutcome
}

data class OpEvent(val request: OpRequest, val outcome: OpOutcome)

data class RunningOp(
    val id: Long,
    val request: OpRequest,
    val progress: Progress = Progress(),
    val hidden: Boolean = false,
)

class PendingConflict(val conflict: Conflict, internal val answer: CompletableDeferred<ConflictDecision>)

/**
 * Runs one file operation at a time in an app-wide scope, so it keeps going when the screen
 * rotates or the user leaves the app.
 */
class OperationManager(private val scope: CoroutineScope) {
    private val _running = MutableStateFlow<RunningOp?>(null)
    val running: StateFlow<RunningOp?> = _running.asStateFlow()

    private val _conflict = MutableStateFlow<PendingConflict?>(null)
    val conflict: StateFlow<PendingConflict?> = _conflict.asStateFlow()

    private val _events = MutableSharedFlow<OpEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<OpEvent> = _events.asSharedFlow()

    private var job: Job? = null
    private var nextId = 1L

    val isBusy: Boolean get() = _running.value != null

    fun start(request: OpRequest, block: suspend (OperationListener) -> OperationResult): Boolean {
        if (_running.value != null) return false
        val id = nextId++
        _running.value = RunningOp(id, request)
        val listener = object : OperationListener {
            override fun onProgress(progress: Progress) {
                _running.update { op -> if (op?.id == id) op.copy(progress = progress) else op }
            }

            override suspend fun onConflict(conflict: Conflict): ConflictDecision {
                val answer = CompletableDeferred<ConflictDecision>()
                _conflict.value = PendingConflict(conflict, answer)
                try {
                    return answer.await()
                } finally {
                    _conflict.value = null
                }
            }
        }
        job = scope.launch(Dispatchers.IO) {
            val outcome = try {
                OpOutcome.Done(block(listener))
            } catch (e: CancellationException) {
                OpOutcome.Cancelled
            } catch (e: OutOfMemoryError) {
                OpOutcome.Failed(e)
            } catch (e: Exception) {
                OpOutcome.Failed(e)
            }
            _running.value = null
            _conflict.value = null
            _events.tryEmit(OpEvent(request, outcome))
        }
        return true
    }

    fun cancel() {
        job?.cancel()
    }

    fun answerConflict(decision: ConflictDecision) {
        _conflict.value?.answer?.complete(decision)
    }

    fun setHidden(hidden: Boolean) {
        _running.update { it?.copy(hidden = hidden) }
    }
}
