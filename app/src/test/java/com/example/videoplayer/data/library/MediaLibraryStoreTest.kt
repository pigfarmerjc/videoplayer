package com.example.videoplayer.data.library

import com.example.videoplayer.data.model.MediaItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
        scanner.complete(1, success(videos = mediaItems(2)))
        second.await()
        scanner.complete(0, success(videos = mediaItems(1)))
        first.await()

        assertEquals(2, store.state.value.generation)
        assertEquals(2, store.state.value.videos.size)
        assertFalse(store.state.value.isRefreshing)
        assertEquals(listOf(false, true), scanner.calls)
    }

    @Test
    fun concurrentNonForcedRefreshesShareOneScan() = runBlocking {
        val scanner = DeferredScanner()
        val store = MediaLibraryStore(scanner)
        val first = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(force = false) }

        scanner.started(0).await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(force = false) }

        assertEquals(listOf(false), scanner.calls)
        scanner.complete(0, success(videos = mediaItems(1)))
        first.await()
        second.await()

        assertEquals(listOf(false), scanner.calls)
        assertEquals(1, store.state.value.videos.size)
    }

    @Test
    fun videoQueryFailurePublishesAvailablePhotosWithTypedPartialError() = runBlocking {
        val scanner = DeferredScanner()
        val store = MediaLibraryStore(scanner)
        val failure = IllegalStateException("video query failed")
        val refresh = async(Dispatchers.Default) { store.refresh(force = false) }

        scanner.started(0).await()
        scanner.complete(
            0,
            MediaLibraryScanResult(
                videos = MediaQueryResult.Failure(failure),
                photos = MediaQueryResult.Success(mediaItems(2))
            )
        )
        refresh.await()

        val error = store.state.value.error as LibraryError.PartialQueryFailure
        assertSame(failure, error.video)
        assertNull(error.photo)
        assertEquals(2, store.state.value.photos.size)
        assertEquals(emptyList<MediaItem>(), store.state.value.videos)
    }

    private fun success(
        videos: List<MediaItem> = emptyList(),
        photos: List<MediaItem> = emptyList()
    ) = MediaLibraryScanResult(
        videos = MediaQueryResult.Success(videos),
        photos = MediaQueryResult.Success(photos)
    )

    @Suppress("UNCHECKED_CAST")
    private fun mediaItems(count: Int): List<MediaItem> =
        java.util.Collections.nCopies<Any?>(count, null) as List<MediaItem>

    private class DeferredScanner : MediaLibraryScanner {
        val calls = mutableListOf<Boolean>()
        private val results = MutableList(4) { CompletableDeferred<MediaLibraryScanResult>() }
        private val started = MutableList(4) { CompletableDeferred<Unit>() }

        override suspend fun scan(force: Boolean): MediaLibraryScanResult {
            val index = synchronized(this) {
                calls += force
                calls.lastIndex
            }
            started[index].complete(Unit)
            return results[index].await()
        }

        fun started(index: Int): CompletableDeferred<Unit> = started[index]

        fun complete(index: Int, result: MediaLibraryScanResult) {
            results[index].complete(result)
        }
    }
}
