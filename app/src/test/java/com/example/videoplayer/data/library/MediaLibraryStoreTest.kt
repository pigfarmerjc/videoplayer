package com.example.videoplayer.data.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaLibraryStoreTest {
    @Test
    fun forcedRefreshPublishesNewerGenerationWhenOlderScanFinishesLast() = runBlocking {
        val scanner = DeferredScanner()
        val store = MediaLibraryStore(scanner)
        val first = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(force = false) }

        scanner.started(0).await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(force = true) }
        scanner.started(1).await()

        scanner.complete(1, success(videos = listOf(media("newer"))))
        second.await()
        first.join()

        assertEquals(2, store.state.value.generation)
        assertEquals(listOf(media("newer")), store.state.value.videos)
        assertFalse(store.state.value.isRefreshing)
        assertTrue(first.isCancelled)
    }

    @Test
    fun ordinaryRefreshAfterForcedRefreshJoinsForcedGeneration() = runBlocking {
        val scanner = DeferredScanner()
        val store = MediaLibraryStore(scanner)
        val ordinaryA = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(force = false) }

        scanner.started(0).await()
        val forcedB = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(force = true) }
        scanner.started(1).await()
        val ordinaryC = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(force = false) }

        assertEquals(listOf(false, true), scanner.calls)
        scanner.complete(1, success(videos = listOf(media("forced"))))
        forcedB.await()
        ordinaryC.await()
        ordinaryA.join()

        assertEquals(2, store.state.value.generation)
        assertEquals(listOf(media("forced")), store.state.value.videos)
        assertTrue(ordinaryA.isCancelled)
    }

    @Test
    fun ordinaryRefreshAfterCompletedForcedRefreshDoesNotJoinStaleOrdinaryScan() = runBlocking {
        val scanner = DeferredScanner()
        scanner.ignoreCancellationFor(0)
        val store = MediaLibraryStore(scanner)
        val ordinaryA = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(force = false) }

        scanner.started(0).await()
        val forcedB = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(force = true) }
        scanner.started(1).await()
        scanner.complete(1, success(videos = listOf(media("forced"))))
        forcedB.await()

        val ordinaryC = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(force = false) }

        assertEquals(listOf(false, true), scanner.calls)
        assertTrue(ordinaryC.isCompleted)
        scanner.complete(0, success(videos = listOf(media("ordinary"))))
        ordinaryA.join()
        ordinaryC.await()
    }

    @Test
    fun ordinaryRefreshAfterCancelledForcedRefreshDoesNotJoinStaleOrdinaryScan() = runBlocking {
        val scanner = DeferredScanner()
        val store = MediaLibraryStore(scanner)
        val ordinaryA = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(force = false) }

        scanner.started(0).await()
        val forcedB = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(force = true) }
        scanner.started(1).await()
        forcedB.cancelAndJoin()

        val ordinaryC = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(force = false) }

        assertEquals(listOf(false, true, false), scanner.calls)
        assertEquals(1, scanner.maxActiveNonForcedScans)
        scanner.complete(2, success(videos = listOf(media("current"))))
        ordinaryC.await()
        ordinaryA.join()

        assertEquals(3, store.state.value.generation)
        assertEquals(listOf(media("current")), store.state.value.videos)
    }

    @Test
    fun concurrentNonForcedRefreshesShareOneScan() = runBlocking {
        val scanner = DeferredScanner()
        val store = MediaLibraryStore(scanner)
        val first = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(force = false) }

        scanner.started(0).await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(force = false) }

        assertEquals(listOf(false), scanner.calls)
        scanner.complete(0, success(videos = listOf(media("one"))))
        first.await()
        second.await()

        assertEquals(listOf(false), scanner.calls)
        assertEquals(listOf(media("one")), store.state.value.videos)
    }

    @Test
    fun videoQueryFailurePublishesAvailablePhotosWithTypedPartialError() = runBlocking {
        val scanner = DeferredScanner()
        val store = MediaLibraryStore(scanner)
        val failure = IllegalStateException("video query failed")
        val refresh = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(force = false) }

        scanner.started(0).await()
        scanner.complete(
            0,
            MediaLibraryScanResult(
                videos = MediaQueryResult.Failure(failure),
                photos = MediaQueryResult.Success(listOf(media("photo")))
            )
        )
        refresh.await()

        val error = store.state.value.error as LibraryError.PartialQueryFailure
        assertSame(failure, error.video)
        assertNull(error.photo)
        assertEquals(listOf(media("photo")), store.state.value.photos)
        assertEquals(emptyList<LibraryMedia>(), store.state.value.videos)
    }

    @Test
    fun photoQueryFailurePublishesAvailableVideosWithTypedPartialError() = runBlocking {
        val scanner = DeferredScanner()
        val store = MediaLibraryStore(scanner)
        val failure = IllegalStateException("photo query failed")
        val refresh = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(force = false) }

        scanner.started(0).await()
        scanner.complete(
            0,
            MediaLibraryScanResult(
                videos = MediaQueryResult.Success(listOf(media("video"))),
                photos = MediaQueryResult.Failure(failure)
            )
        )
        refresh.await()

        val error = store.state.value.error as LibraryError.PartialQueryFailure
        assertNull(error.video)
        assertSame(failure, error.photo)
        assertEquals(listOf(media("video")), store.state.value.videos)
        assertEquals(emptyList<LibraryMedia>(), store.state.value.photos)
    }

    @Test
    fun scanFailurePublishesTypedErrorAndFinishesRefreshing() = runBlocking {
        val scanner = DeferredScanner()
        val store = MediaLibraryStore(scanner)
        val failure = IllegalStateException("scanner failed")
        val refresh = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(force = false) }

        scanner.started(0).await()
        scanner.fail(0, failure)
        refresh.await()

        val error = store.state.value.error as LibraryError.ScanFailure
        assertSame(failure, error.cause)
        assertFalse(store.state.value.isRefreshing)
    }

    @Test
    fun cancellingScanOwnerConvergesRefreshingState() = runBlocking {
        val scanner = DeferredScanner()
        val store = MediaLibraryStore(scanner)
        val refresh = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(force = false) }

        scanner.started(0).await()
        refresh.cancelAndJoin()

        assertFalse(store.state.value.isRefreshing)
        assertEquals(1, store.state.value.generation)
    }

    private fun success(
        videos: List<LibraryMedia> = emptyList(),
        photos: List<LibraryMedia> = emptyList()
    ) = MediaLibraryScanResult(
        videos = MediaQueryResult.Success(videos),
        photos = MediaQueryResult.Success(photos)
    )

    private fun media(id: String) = LibraryMedia(id = id)

    private class DeferredScanner : MediaLibraryScanner {
        val calls = mutableListOf<Boolean>()
        var maxActiveNonForcedScans = 0
            private set
        private var activeNonForcedScans = 0
        private val nonCancellableCalls = mutableSetOf<Int>()
        private val results = MutableList(4) { CompletableDeferred<MediaLibraryScanResult>() }
        private val started = MutableList(4) { CompletableDeferred<Unit>() }

        override suspend fun scan(force: Boolean): MediaLibraryScanResult {
            val index = synchronized(this) {
                calls += force
                if (!force) {
                    activeNonForcedScans++
                    maxActiveNonForcedScans = maxOf(maxActiveNonForcedScans, activeNonForcedScans)
                }
                calls.lastIndex
            }
            started[index].complete(Unit)
            return try {
                if (index in nonCancellableCalls) {
                    withContext(NonCancellable) { results[index].await() }
                } else {
                    results[index].await()
                }
            } finally {
                if (!force) {
                    synchronized(this) {
                        activeNonForcedScans--
                    }
                }
            }
        }

        fun started(index: Int): CompletableDeferred<Unit> = started[index]

        fun complete(index: Int, result: MediaLibraryScanResult) {
            results[index].complete(result)
        }

        fun fail(index: Int, error: Throwable) {
            results[index].completeExceptionally(error)
        }

        fun ignoreCancellationFor(index: Int) {
            nonCancellableCalls += index
        }
    }
}
