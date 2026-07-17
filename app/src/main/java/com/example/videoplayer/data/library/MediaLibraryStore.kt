package com.example.videoplayer.data.library

import com.example.videoplayer.data.model.MediaItem
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

    val state: StateFlow<MediaLibraryState> = _state.asStateFlow()

    suspend fun refresh(force: Boolean) {
        val (request, startsScan) = refreshMutex.withLock {
            if (!force) {
                activeNonForcedRefresh?.let { return@withLock it to false }
            }

            RefreshRequest(
                generation = generation.incrementAndGet(),
                completion = CompletableDeferred()
            ).also { next ->
                if (!force) activeNonForcedRefresh = next
            } to true
        }

        if (startsScan) {
            executeScan(request, force)
        }

        publishIfCurrent(request)
    }

    private suspend fun executeScan(request: RefreshRequest, force: Boolean) {
        setRefreshingIfCurrent(request.generation)
        try {
            request.completion.complete(RefreshOutcome.ScanResult(scanner.scan(force)))
        } catch (error: CancellationException) {
            request.completion.cancel(error)
            throw error
        } catch (error: Throwable) {
            request.completion.complete(RefreshOutcome.ScanFailure(error))
        } finally {
            refreshMutex.withLock {
                if (activeNonForcedRefresh === request) {
                    activeNonForcedRefresh = null
                }
            }
        }
    }

    private suspend fun publishIfCurrent(request: RefreshRequest) {
        val outcome = request.completion.await()
        if (request.generation != generation.get()) return

        _state.value = when (outcome) {
            is RefreshOutcome.ScanResult -> outcome.result.toState(request.generation)
            is RefreshOutcome.ScanFailure -> MediaLibraryState(
                generation = request.generation,
                error = LibraryError.ScanFailure(outcome.cause)
            )
        }
    }

    private fun setRefreshingIfCurrent(requestGeneration: Long) {
        if (requestGeneration != generation.get()) return
        _state.value = _state.value.copy(
            generation = requestGeneration,
            isRefreshing = true,
            error = null
        )
    }

    private fun MediaLibraryScanResult.toState(generation: Long): MediaLibraryState {
        val videoQuery = videos
        val photoQuery = photos
        val videoFailure = (videoQuery as? MediaQueryResult.Failure)?.cause
        val photoFailure = (photoQuery as? MediaQueryResult.Failure)?.cause
        return MediaLibraryState(
            generation = generation,
            videos = videoQuery.itemsOrEmpty(),
            photos = photoQuery.itemsOrEmpty(),
            isRefreshing = false,
            error = if (videoFailure == null && photoFailure == null) {
                null
            } else {
                LibraryError.PartialQueryFailure(videoFailure, photoFailure)
            }
        )
    }

    private fun MediaQueryResult.itemsOrEmpty(): List<MediaItem> = when (this) {
        is MediaQueryResult.Success -> items
        is MediaQueryResult.Failure -> emptyList()
    }

    private data class RefreshRequest(
        val generation: Long,
        val completion: CompletableDeferred<RefreshOutcome>
    )

    private sealed interface RefreshOutcome {
        data class ScanResult(val result: MediaLibraryScanResult) : RefreshOutcome
        data class ScanFailure(val cause: Throwable) : RefreshOutcome
    }
}
