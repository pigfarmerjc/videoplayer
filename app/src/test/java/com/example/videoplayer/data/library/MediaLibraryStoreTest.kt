package com.example.videoplayer.data.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
        val store = store(scanner)
        val ordinaryA = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(false) }
        scanner.started(0).await()
        val forcedB = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(true) }
        scanner.started(1).await()

        scanner.complete(1, success(videos = listOf(media("newer"))))
        forcedB.await()
        ordinaryA.join()

        assertEquals(2, store.state.value.generation)
        assertEquals(listOf(media("newer")), store.state.value.videos)
        assertFalse(store.state.value.isRefreshing)
    }

    @Test
    fun ordinaryRefreshReusesCurrentForcedScan() = runBlocking {
        val scanner = DeferredScanner()
        val store = store(scanner)
        val ordinaryA = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(false) }
        scanner.started(0).await()
        val forcedB = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(true) }
        scanner.started(1).await()
        val ordinaryC = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(false) }

        assertEquals(listOf(false, true), scanner.calls)
        scanner.complete(1, success(videos = listOf(media("forced"))))
        forcedB.await()
        ordinaryC.await()
        ordinaryA.join()
        assertEquals(listOf(media("forced")), store.state.value.videos)
    }

    @Test
    fun completedForcedResultServesOrdinaryCallerWhileStaleNormalCleansUp() = runBlocking {
        val scanner = DeferredScanner().apply { ignoreCancellationFor(0) }
        val store = store(scanner)
        val ordinaryA = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(false) }
        scanner.started(0).await()
        val forcedB = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(true) }
        scanner.started(1).await()
        scanner.complete(1, success(videos = listOf(media("forced"))))
        forcedB.await()

        val ordinaryC = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(false) }

        assertTrue(ordinaryC.isCompleted)
        assertEquals(listOf(false, true), scanner.calls)
        ordinaryC.await()
        scanner.complete(0, success(videos = listOf(media("stale"))))
        ordinaryA.join()
    }

    @Test
    fun callerCancellationDoesNotCancelSharedForcedScan() = runBlocking {
        val scanner = DeferredScanner()
        val store = store(scanner)
        val ordinaryA = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(false) }
        scanner.started(0).await()
        val forcedB = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(true) }
        scanner.started(1).await()
        forcedB.cancelAndJoin()

        val ordinaryC = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(false) }

        assertEquals(listOf(false, true), scanner.calls)
        scanner.complete(1, success(videos = listOf(media("shared"))))
        ordinaryC.await()
        ordinaryA.join()
        assertEquals(listOf(media("shared")), store.state.value.videos)
    }

    @Test
    fun concurrentNonForcedRefreshesShareOneScan() = runBlocking {
        val scanner = DeferredScanner()
        val store = store(scanner)
        val first = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(false) }
        scanner.started(0).await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(false) }

        assertEquals(listOf(false), scanner.calls)
        scanner.complete(0, success(videos = listOf(media("one"))))
        first.await()
        second.await()
        assertEquals(1, scanner.maxActiveNonForcedScans)
    }

    @Test
    fun videoQueryFailurePublishesAvailablePhotosWithTypedPartialError() = runBlocking {
        val scanner = DeferredScanner()
        val store = store(scanner)
        val failure = IllegalStateException("video query failed")
        val refresh = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(false) }
        scanner.started(0).await()
        scanner.complete(0, MediaLibraryScanResult(MediaQueryResult.Failure(failure), MediaQueryResult.Success(listOf(media("photo")))))
        refresh.await()

        val error = store.state.value.error as LibraryError.PartialQueryFailure
        assertSame(failure, error.video)
        assertNull(error.photo)
        assertEquals(listOf(media("photo")), store.state.value.photos)
    }

    @Test
    fun photoQueryFailurePublishesAvailableVideosWithTypedPartialError() = runBlocking {
        val scanner = DeferredScanner()
        val store = store(scanner)
        val failure = IllegalStateException("photo query failed")
        val refresh = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(false) }
        scanner.started(0).await()
        scanner.complete(0, MediaLibraryScanResult(MediaQueryResult.Success(listOf(media("video"))), MediaQueryResult.Failure(failure)))
        refresh.await()

        val error = store.state.value.error as LibraryError.PartialQueryFailure
        assertNull(error.video)
        assertSame(failure, error.photo)
        assertEquals(listOf(media("video")), store.state.value.videos)
    }

    @Test
    fun scanFailurePublishesTypedErrorAndFinishesRefreshing() = runBlocking {
        val scanner = DeferredScanner()
        val store = store(scanner)
        val failure = IllegalStateException("scanner failed")
        val refresh = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(false) }
        scanner.started(0).await()
        scanner.fail(0, failure)
        refresh.await()

        assertSame(failure, (store.state.value.error as LibraryError.ScanFailure).cause)
        assertFalse(store.state.value.isRefreshing)
    }

    @Test
    fun callerCancellationDoesNotCancelSharedOrdinaryScan() = runBlocking {
        val scanner = DeferredScanner()
        val store = store(scanner)
        val first = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(false) }
        scanner.started(0).await()
        first.cancelAndJoin()
        val second = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(false) }

        assertEquals(listOf(false), scanner.calls)
        scanner.complete(0, success(videos = listOf(media("shared"))))
        second.await()
    }

    @Test
    fun cancellationDuringStaleCleanupDoesNotBlockActor() = runBlocking {
        val scanner = DeferredScanner().apply { ignoreCancellationFor(0) }
        val store = store(scanner)
        val ordinaryA = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(false) }
        scanner.started(0).await()
        val forcedB = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(true) }
        scanner.started(1).await()
        forcedB.cancelAndJoin()
        val forcedC = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(true) }
        scanner.started(2).await()

        scanner.complete(2, success(videos = listOf(media("current"))))
        forcedC.await()
        assertEquals(listOf(media("current")), store.state.value.videos)
        scanner.complete(0, success(videos = listOf(media("stale"))))
        ordinaryA.join()
    }

    private fun success(videos: List<LibraryMedia> = emptyList(), photos: List<LibraryMedia> = emptyList()) =
        MediaLibraryScanResult(MediaQueryResult.Success(videos), MediaQueryResult.Success(photos))

    private fun media(id: String) = LibraryMedia(id = id)

    private fun store(scanner: MediaLibraryScanner): MediaLibraryStore =
        MediaLibraryStore(scanner, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

    private class DeferredScanner : MediaLibraryScanner {
        val calls = mutableListOf<Boolean>()
        var maxActiveNonForcedScans = 0
            private set
        private var activeNonForcedScans = 0
        private val nonCancellableCalls = mutableSetOf<Int>()
        private val results = MutableList(6) { CompletableDeferred<MediaLibraryScanResult>() }
        private val started = MutableList(6) { CompletableDeferred<Unit>() }

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
                if (index in nonCancellableCalls) withContext(NonCancellable) { results[index].await() } else results[index].await()
            } finally {
                if (!force) synchronized(this) { activeNonForcedScans-- }
            }
        }

        fun started(index: Int): CompletableDeferred<Unit> = started[index]
        fun complete(index: Int, result: MediaLibraryScanResult) { results[index].complete(result) }
        fun fail(index: Int, error: Throwable) { results[index].completeExceptionally(error) }
        fun ignoreCancellationFor(index: Int) { nonCancellableCalls += index }
    }
}
