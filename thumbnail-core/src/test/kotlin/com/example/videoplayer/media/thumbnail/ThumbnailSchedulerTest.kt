package com.example.videoplayer.media.thumbnail

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailSchedulerTest {
    @Test
    fun duplicateRequestsShareOneDecode() = runBlocking {
        val cache = DeferredThumbnailCache()
        val scheduler = scheduler(cache)
        val key = ThumbnailKey(mediaId = 1L, size = ThumbnailSize(180, 120))

        val first = async(start = CoroutineStart.UNDISPATCHED) { scheduler.request(key.mediaId, key.size, ThumbnailPriority.VISIBLE).single() }
        cache.awaitDecodeStart(key)
        val second = async(start = CoroutineStart.UNDISPATCHED) { scheduler.request(key.mediaId, key.size, ThumbnailPriority.PREFETCH).single() }

        assertEquals(listOf(key), cache.decodeStarts)
        cache.completeDecode(key, "first-frame")

        assertEquals("first-frame", first.await().value)
        assertEquals("first-frame", second.await().value)
        assertEquals(listOf(key), cache.decodeStarts)
    }

    @Test
    fun visibleRequestRunsBeforeQueuedPrefetch() = runBlocking {
        val cache = DeferredThumbnailCache()
        val scheduler = scheduler(cache)
        val prefetch = ThumbnailKey(1L, ThumbnailSize(180, 120))
        val visible = ThumbnailKey(2L, ThumbnailSize(180, 120))

        scheduler.setFastScrolling(true)
        val prefetchRequest = async(start = CoroutineStart.UNDISPATCHED) { scheduler.request(prefetch.mediaId, prefetch.size, ThumbnailPriority.PREFETCH).single() }
        val visibleRequest = async(start = CoroutineStart.UNDISPATCHED) { scheduler.request(visible.mediaId, visible.size, ThumbnailPriority.VISIBLE).single() }
        scheduler.setFastScrolling(false)

        cache.awaitDecodeStart(visible)
        assertEquals(listOf(visible), cache.decodeStarts)
        cache.completeDecode(visible, "visible-frame")
        cache.awaitDecodeStart(prefetch)
        cache.completeDecode(prefetch, "prefetch-frame")

        assertEquals("visible-frame", visibleRequest.await().value)
        assertEquals("prefetch-frame", prefetchRequest.await().value)
    }

    @Test
    fun fastScrollingAllowsMemoryHitWithoutStartingDecode() = runBlocking {
        val cache = DeferredThumbnailCache()
        val scheduler = scheduler(cache)
        val hit = ThumbnailKey(1L, ThumbnailSize(180, 120))
        val miss = ThumbnailKey(2L, ThumbnailSize(180, 120))
        cache.memory[hit] = "cached-frame"

        scheduler.setFastScrolling(true)
        val hitResult = scheduler.request(hit.mediaId, hit.size, ThumbnailPriority.VISIBLE).single()
        val pendingMiss = async(start = CoroutineStart.UNDISPATCHED) { scheduler.request(miss.mediaId, miss.size, ThumbnailPriority.VISIBLE).single() }

        assertEquals("cached-frame", hitResult.value)
        assertTrue(cache.decodeStarts.isEmpty())
        scheduler.setFastScrolling(false)
        cache.awaitDecodeStart(miss)
        cache.completeDecode(miss, "decoded-frame")
        assertEquals("decoded-frame", pendingMiss.await().value)
    }

    @Test
    fun cancelledRequesterNeverReceivesLateDecodeResult() = runBlocking {
        val cache = DeferredThumbnailCache()
        val scheduler = scheduler(cache)
        val key = ThumbnailKey(1L, ThumbnailSize(180, 120))
        val delivered = mutableListOf<ThumbnailResult<String>>()

        val request = launch(start = CoroutineStart.UNDISPATCHED) {
            scheduler.request(key.mediaId, key.size, ThumbnailPriority.VISIBLE).collect { delivered += it }
        }
        cache.awaitDecodeStart(key)
        request.cancelAndJoin()
        cache.completeDecode(key, "late-frame")

        withTimeout(1_000) {
            while (cache.decodeCompletions == 0) {
                kotlinx.coroutines.yield()
            }
        }
        assertTrue(delivered.isEmpty())
    }

    private fun scheduler(cache: DeferredThumbnailCache): ThumbnailScheduler<String> =
        ThumbnailScheduler(
            cache = cache,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            decodeWorkerCount = 1
        )

    private class DeferredThumbnailCache : ThumbnailCache<String> {
        val memory = mutableMapOf<ThumbnailKey, String>()
        val decodeStarts = mutableListOf<ThumbnailKey>()
        var decodeCompletions = 0
        private val decodeStarted = mutableMapOf<ThumbnailKey, CompletableDeferred<Unit>>()
        private val decodeResults = mutableMapOf<ThumbnailKey, CompletableDeferred<String?>>()

        override suspend fun loadMemory(key: ThumbnailKey): String? = synchronized(this) { memory[key] }

        override suspend fun loadDisk(key: ThumbnailKey): String? = null

        override suspend fun decode(key: ThumbnailKey): String? {
            val result = synchronized(this) {
                decodeStarts += key
                decodeStarted.getOrPut(key) { CompletableDeferred() }.complete(Unit)
                decodeResults.getOrPut(key) { CompletableDeferred() }
            }
            return try {
                result.await()
            } finally {
                synchronized(this) { decodeCompletions++ }
            }
        }

        override suspend fun putMemory(key: ThumbnailKey, value: String) {
            synchronized(this) { memory[key] = value }
        }

        override suspend fun writeDisk(key: ThumbnailKey, value: String) = Unit

        suspend fun awaitDecodeStart(key: ThumbnailKey) {
            withTimeout(1_000) { synchronized(this) { decodeStarted.getOrPut(key) { CompletableDeferred() } }.await() }
        }

        fun completeDecode(key: ThumbnailKey, value: String) {
            synchronized(this) { decodeResults.getOrPut(key) { CompletableDeferred() }.complete(value) }
        }
    }
}
