package com.example.videoplayer.data.library

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MediaLibraryStore(
    private val scanner: MediaLibraryScanner
) {
    private val refreshMutex = Mutex()
    private val generation = AtomicLong(0L)
    private val _state = MutableStateFlow(MediaLibraryState())
    private var activeNonForcedRefresh: RefreshRequest? = null
    private var activeForcedRefresh: RefreshRequest? = null

    val state: StateFlow<MediaLibraryState> = _state.asStateFlow()

    suspend fun refresh(force: Boolean) {
        val selection = selectRequest(force)
        try {
            if (selection.startsScan) {
                completeRequest(selection.request, force)
            }
            publishIfCurrent(selection.request)
        } catch (error: CancellationException) {
            if (selection.startsScan) {
                settleCancelledRequest(selection.request)
            }
            throw error
        } finally {
            if (selection.startsScan) {
                releaseRequest(selection.request)
            }
        }
    }

    private suspend fun selectRequest(force: Boolean): RequestSelection = refreshMutex.withLock {
        if (!force) {
            val currentGeneration = generation.get()
            activeForcedRefresh
                ?.takeIf { it.generation == currentGeneration }
                ?.let { return@withLock RequestSelection(it, startsScan = false) }
            activeNonForcedRefresh
                ?.takeIf { it.generation == currentGeneration }
                ?.let { return@withLock RequestSelection(it, startsScan = false) }
        }

        val request = RefreshRequest(
            generation = generation.incrementAndGet(),
            completion = CompletableDeferred()
        )
        if (force) {
            activeForcedRefresh = request
        } else {
            activeNonForcedRefresh = request
        }
        _state.value = _state.value.copy(
            generation = request.generation,
            isRefreshing = true,
            error = null
        )
        RequestSelection(request, startsScan = true)
    }

    private suspend fun completeRequest(request: RefreshRequest, force: Boolean) {
        val outcome = try {
            RefreshOutcome.ScanResult(scanner.scan(force))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            RefreshOutcome.ScanFailure(error)
        }
        request.completion.complete(outcome)
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

    private suspend fun settleCancelledRequest(request: RefreshRequest) {
        refreshMutex.withLock {
            request.completion.cancel()
            clearActiveRequest(request)
            if (request.generation == generation.get()) {
                _state.value = _state.value.copy(isRefreshing = false)
            }
        }
    }

    private suspend fun releaseRequest(request: RefreshRequest) {
        refreshMutex.withLock {
            clearActiveRequest(request)
        }
    }

    private fun clearActiveRequest(request: RefreshRequest) {
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

    private data class RequestSelection(
        val request: RefreshRequest,
        val startsScan: Boolean
    )

    private data class RefreshRequest(
        val generation: Long,
        val completion: CompletableDeferred<RefreshOutcome>
    )

    private sealed interface RefreshOutcome {
        data class ScanResult(val result: MediaLibraryScanResult) : RefreshOutcome
        data class ScanFailure(val cause: Throwable) : RefreshOutcome
    }
}
