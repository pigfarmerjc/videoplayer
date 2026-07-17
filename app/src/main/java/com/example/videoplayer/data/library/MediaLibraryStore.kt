package com.example.videoplayer.data.library

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MediaLibraryStore(
    private val scanner: MediaLibraryScanner,
    private val requestScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val refreshMutex = Mutex()
    private val generation = AtomicLong(0L)
    private val _state = MutableStateFlow(MediaLibraryState())
    private var activeNonForcedRefresh: RefreshRequest? = null
    private var activeForcedRefresh: RefreshRequest? = null

    val state: StateFlow<MediaLibraryState> = _state.asStateFlow()

    suspend fun refresh(force: Boolean) {
        val selection = selectRequest(force)
        if (selection is RequestSelection.WaitForNormalLane) {
            selection.request.finished.await()
            refresh(force)
            return
        }

        val request = selection.request
        if (selection.startsScan) {
            request.job.start()
        }

        try {
            publishIfCurrent(request)
        } catch (error: CancellationException) {
            if (selection.startsScan) {
                request.job.cancel(error)
                withContext(NonCancellable) {
                    request.finished.await()
                }
            }
            throw error
        }
    }

    private suspend fun selectRequest(force: Boolean): RequestSelection = refreshMutex.withLock {
        val currentGeneration = generation.get()
        if (!force) {
            activeForcedRefresh
                ?.takeIf { it.generation == currentGeneration }
                ?.let { return@withLock RequestSelection.Reuse(it) }
            activeNonForcedRefresh
                ?.takeIf { it.generation == currentGeneration }
                ?.let { return@withLock RequestSelection.Reuse(it) }
            activeNonForcedRefresh?.let { return@withLock RequestSelection.WaitForNormalLane(it) }
        }

        val request = newRequest(force)
        if (force) {
            activeNonForcedRefresh?.takeIf { it.generation < request.generation }?.job?.cancel()
            activeForcedRefresh = request
        } else {
            activeNonForcedRefresh = request
        }
        _state.value = _state.value.copy(
            generation = request.generation,
            isRefreshing = true,
            error = null
        )
        RequestSelection.Start(request)
    }

    private fun newRequest(force: Boolean): RefreshRequest {
        val request = RefreshRequest(generation.incrementAndGet(), force)
        request.job = requestScope.launch(start = CoroutineStart.LAZY) {
            runRequest(request)
        }
        return request
    }

    private suspend fun runRequest(request: RefreshRequest) {
        try {
            val outcome = try {
                RefreshOutcome.ScanResult(scanner.scan(request.force))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                RefreshOutcome.ScanFailure(error)
            }
            request.completion.complete(outcome)
        } catch (error: CancellationException) {
            request.completion.cancel(error)
        } finally {
            finishRequest(request)
        }
    }

    private suspend fun finishRequest(request: RefreshRequest) {
        refreshMutex.withLock {
            if (activeNonForcedRefresh === request) {
                activeNonForcedRefresh = null
            }
            if (activeForcedRefresh === request && (
                    activeNonForcedRefresh == null || request.completion.isCancelled
                )
            ) {
                activeForcedRefresh = null
            }
            if (activeNonForcedRefresh == null && activeForcedRefresh?.completion?.isCompleted == true) {
                activeForcedRefresh = null
            }
            if (request.generation == generation.get() && request.completion.isCancelled) {
                _state.value = _state.value.copy(isRefreshing = false)
            }
            request.finished.complete(Unit)
        }
    }

    private suspend fun publishIfCurrent(request: RefreshRequest) {
        val outcome = request.completion.await()
        refreshMutex.withLock {
            if (request.generation != generation.get()) return
            _state.value = when (outcome) {
                is RefreshOutcome.ScanResult -> outcome.result.toState(request.generation)
                is RefreshOutcome.ScanFailure -> MediaLibraryState(
                    generation = request.generation,
                    error = LibraryError.ScanFailure(outcome.cause)
                )
            }
        }
    }

    private fun MediaLibraryScanResult.toState(generation: Long): MediaLibraryState {
        val videoFailure = (videos as? MediaQueryResult.Failure)?.cause
        val photoFailure = (photos as? MediaQueryResult.Failure)?.cause
        return MediaLibraryState(
            generation = generation,
            videos = videos.itemsOrEmpty(),
            photos = photos.itemsOrEmpty(),
            error = if (videoFailure == null && photoFailure == null) {
                null
            } else {
                LibraryError.PartialQueryFailure(videoFailure, photoFailure)
            }
        )
    }

    private fun MediaQueryResult.itemsOrEmpty(): List<LibraryMedia> = when (this) {
        is MediaQueryResult.Success -> items
        is MediaQueryResult.Failure -> emptyList()
    }

    private sealed interface RequestSelection {
        val request: RefreshRequest
        val startsScan: Boolean

        data class Start(override val request: RefreshRequest) : RequestSelection {
            override val startsScan: Boolean = true
        }

        data class Reuse(override val request: RefreshRequest) : RequestSelection {
            override val startsScan: Boolean = false
        }

        data class WaitForNormalLane(override val request: RefreshRequest) : RequestSelection {
            override val startsScan: Boolean = false
        }
    }

    private class RefreshRequest(
        val generation: Long,
        val force: Boolean
    ) {
        val completion = CompletableDeferred<RefreshOutcome>()
        val finished = CompletableDeferred<Unit>()
        lateinit var job: Job
    }

    private sealed interface RefreshOutcome {
        data class ScanResult(val result: MediaLibraryScanResult) : RefreshOutcome
        data class ScanFailure(val cause: Throwable) : RefreshOutcome
    }
}
