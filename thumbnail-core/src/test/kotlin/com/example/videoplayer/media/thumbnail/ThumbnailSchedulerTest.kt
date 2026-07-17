package com.example.videoplayer.media.thumbnail

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailSchedulerTest {
    @Test
    fun duplicateRequestsForSameResourceVersionShareOneDecode() = runBlocking {
        val cache = DeferredThumbnailCache()
        val scheduler = scheduler(cache)
        val resource = resource("video-1", version = 10)
        val size = ThumbnailSize(180, 120)

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            scheduler.request(resource, "first-source", size, ThumbnailPriority.VISIBLE).single()
        }
        cache.awaitDecodeStart(resource, size, 1)
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            scheduler.request(resource, "second-source", size, ThumbnailPriority.PREFETCH).single()
        }

        cache.completeDecode(resource, size, 1, "first-frame")

        assertEquals("first-frame", first.await().value)
        assertEquals("first-frame", second.await().value)
        assertEquals(1, cache.decodeStarts.size)
    }

    @Test
    fun diskHitPublishesWithoutStartingDecode() = runBlocking {
        val cache = DeferredThumbnailCache()
        val scheduler = scheduler(cache)
        val resource = resource("video-1", version = 10)
        val size = ThumbnailSize(180, 120)
        cache.disk[ThumbnailKey(resource, size)] = "disk-frame"

        val result = scheduler.request(resource, "source", size, ThumbnailPriority.VISIBLE).single()

        assertEquals("disk-frame", result.value)
        assertTrue(cache.decodeStarts.isEmpty())
    }

    @Test
    fun slowDiskLookupDoesNotBlockCancellationOrVisibleDecode() = runBlocking {
        val cache = DeferredThumbnailCache()
        val scheduler = scheduler(cache)
        val slowResource = resource("slow", version = 1)
        val visibleResource = resource("visible", version = 1)
        val size = ThumbnailSize(180, 120)
        cache.delayDisk(slowResource, size)

        val slowRequest = launch(start = CoroutineStart.UNDISPATCHED) {
            scheduler.request(slowResource, "slow-source", size, ThumbnailPriority.BACKGROUND).collect { }
        }
        cache.awaitDiskStart(slowResource, size)
        slowRequest.cancelAndJoin()
        val visible = async(start = CoroutineStart.UNDISPATCHED) {
            scheduler.request(visibleResource, "visible-source", size, ThumbnailPriority.VISIBLE).single()
        }

        cache.awaitDecodeStart(visibleResource, size, 1)
        cache.completeDecode(visibleResource, size, 1, "visible-frame")

        assertEquals("visible-frame", visible.await().value)
        assertTrue(cache.decodeStarts.none { it.key.resource == slowResource })
    }

    @Test
    fun visibleUpgradeRunsBeforeQueuedBackgroundWorkAfterScrollingSettles() = runBlocking {
        val cache = DeferredThumbnailCache()
        val scheduler = scheduler(cache)
        val background = resource("background", version = 1)
        val upgraded = resource("upgraded", version = 1)
        val size = ThumbnailSize(180, 120)
        cache.delayDisk(background, size)
        cache.delayDisk(upgraded, size)

        scheduler.setFastScrolling(true)
        val backgroundRequest = async(start = CoroutineStart.UNDISPATCHED) {
            scheduler.request(background, "background-source", size, ThumbnailPriority.BACKGROUND).single()
        }
        val upgradeRequest = async(start = CoroutineStart.UNDISPATCHED) {
            scheduler.request(upgraded, "prefetch-source", size, ThumbnailPriority.BACKGROUND).single()
        }
        val visibleUpgrade = async(start = CoroutineStart.UNDISPATCHED) {
            scheduler.request(upgraded, "visible-source", size, ThumbnailPriority.VISIBLE).single()
        }
        cache.awaitMemoryLookups(3)
        cache.awaitDiskStart(background, size)
        cache.awaitDiskStart(upgraded, size)
        cache.completeDisk(background, size, null)
        cache.completeDisk(upgraded, size, null)
        cache.awaitDiskCompletions(2)
        kotlinx.coroutines.yield()
        scheduler.setFastScrolling(false)

        cache.awaitDecodeStart(upgraded, size, 1)
        assertEquals(upgraded, cache.decodeStarts.single().key.resource)
        cache.completeDecode(upgraded, size, 1, "upgraded-frame")
        cache.awaitDecodeStart(background, size, 1)
        cache.completeDecode(background, size, 1, "background-frame")

        assertEquals("upgraded-frame", upgradeRequest.await().value)
        assertEquals("upgraded-frame", visibleUpgrade.await().value)
        assertEquals("background-frame", backgroundRequest.await().value)
    }

    @Test
    fun cancellationResistantOldFlightCannotPublishOverNewRequestForSameKey() = runBlocking {
        val cache = DeferredThumbnailCache()
        val scheduler = scheduler(cache)
        val resource = resource("video-1", version = 10)
        val size = ThumbnailSize(180, 120)
        cache.ignoreDecodeCancellation(resource, size, attempt = 1)

        val old = launch(start = CoroutineStart.UNDISPATCHED) {
            scheduler.request(resource, "old-source", size, ThumbnailPriority.VISIBLE).collect { }
        }
        cache.awaitDecodeStart(resource, size, 1)
        old.cancelAndJoin()
        val replacement = async(start = CoroutineStart.UNDISPATCHED) {
            scheduler.request(resource, "new-source", size, ThumbnailPriority.VISIBLE).single()
        }
        cache.awaitDecodeStart(resource, size, 2)
        cache.completeDecode(resource, size, 2, "new-frame")
        assertEquals("new-frame", replacement.await().value)

        cache.completeDecode(resource, size, 1, "stale-frame")
        cache.awaitDecodeCompletion(resource, size, 1)

        assertEquals(listOf("new-frame"), cache.memoryWrites.map { it.value })
    }

    @Test
    fun scrollControllerForwardsGridScrollStateToSchedulerProvider() {
        val calls = mutableListOf<Boolean>()
        val controller = ThumbnailScrollController { calls += it }

        controller.onScrollInProgressChanged(true)
        controller.onScrollInProgressChanged(true)
        controller.onScrollInProgressChanged(false)

        assertEquals(listOf(true, false), calls)
    }

    private fun resource(name: String, version: Long) = ThumbnailResourceIdentity(
        storageKey = "file:/$name.mp4",
        uri = "content://media/external/video/$name",
        dateModified = version
    )

    private fun scheduler(cache: DeferredThumbnailCache): ThumbnailScheduler<String, String> =
        ThumbnailScheduler(
            cache = cache,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            decodeWorkerCount = 1
        )

    private class DeferredThumbnailCache : ThumbnailCache<String, String> {
        val memory = mutableMapOf<ThumbnailKey, String>()
        val disk = mutableMapOf<ThumbnailKey, String>()
        val decodeStarts = mutableListOf<DecodeStart>()
        val memoryWrites = mutableListOf<MemoryWrite>()
        private var memoryLookups = 0
        private var diskCompletions = 0
        private val diskStarted = mutableMapOf<ThumbnailKey, CompletableDeferred<Unit>>()
        private val delayedDisk = mutableMapOf<ThumbnailKey, CompletableDeferred<String?>>()
        private val decodeStarted = mutableMapOf<DecodeAttempt, CompletableDeferred<Unit>>()
        private val decodeResults = mutableMapOf<DecodeAttempt, CompletableDeferred<String?>>()
        private val decodeCompletions = mutableMapOf<DecodeAttempt, CompletableDeferred<Unit>>()
        private val cancellationResistantAttempts = mutableSetOf<DecodeAttempt>()

        override suspend fun loadMemory(key: ThumbnailKey): String? = synchronized(this) {
            memoryLookups++
            memory[key]
        }

        override suspend fun loadDisk(key: ThumbnailKey): String? {
            val delayed = synchronized(this) {
                diskStarted.getOrPut(key) { CompletableDeferred() }.complete(Unit)
                delayedDisk[key]
            }
            return (delayed?.await() ?: synchronized(this) { disk[key] }).also {
                synchronized(this) { diskCompletions++ }
            }
        }

        override suspend fun decode(key: ThumbnailKey, source: String): String? {
            val attempt = synchronized(this) {
                val next = decodeStarts.count { it.key == key } + 1
                DecodeAttempt(key, next).also {
                    decodeStarts += DecodeStart(it.key, source)
                    decodeStarted.getOrPut(it) { CompletableDeferred() }.complete(Unit)
                }
            }
            val result = synchronized(this) { decodeResults.getOrPut(attempt) { CompletableDeferred() } }
            return try {
                if (attempt in cancellationResistantAttempts) {
                    withContext(NonCancellable) { result.await() }
                } else {
                    result.await()
                }
            } finally {
                synchronized(this) { decodeCompletions.getOrPut(attempt) { CompletableDeferred() }.complete(Unit) }
            }
        }

        override suspend fun putMemory(key: ThumbnailKey, value: String) {
            synchronized(this) {
                memory[key] = value
                memoryWrites += MemoryWrite(key, value)
            }
        }

        override suspend fun writeDisk(key: ThumbnailKey, value: String) = Unit

        fun delayDisk(resource: ThumbnailResourceIdentity, size: ThumbnailSize) {
            synchronized(this) { delayedDisk[ThumbnailKey(resource, size)] = CompletableDeferred() }
        }

        fun completeDisk(resource: ThumbnailResourceIdentity, size: ThumbnailSize, value: String?) {
            synchronized(this) { delayedDisk.getValue(ThumbnailKey(resource, size)).complete(value) }
        }

        fun ignoreDecodeCancellation(resource: ThumbnailResourceIdentity, size: ThumbnailSize, attempt: Int) {
            synchronized(this) { cancellationResistantAttempts += DecodeAttempt(ThumbnailKey(resource, size), attempt) }
        }

        suspend fun awaitDiskStart(resource: ThumbnailResourceIdentity, size: ThumbnailSize) {
            withTimeout(1_000) { synchronized(this) { diskStarted.getOrPut(ThumbnailKey(resource, size)) { CompletableDeferred() } }.await() }
        }

        suspend fun awaitMemoryLookups(count: Int) {
            withTimeout(1_000) {
                while (synchronized(this) { memoryLookups } < count) kotlinx.coroutines.yield()
            }
        }

        suspend fun awaitDiskCompletions(count: Int) {
            withTimeout(1_000) {
                while (synchronized(this) { diskCompletions } < count) kotlinx.coroutines.yield()
            }
        }

        suspend fun awaitDecodeStart(resource: ThumbnailResourceIdentity, size: ThumbnailSize, attempt: Int) {
            withTimeout(1_000) { synchronized(this) { decodeStarted.getOrPut(DecodeAttempt(ThumbnailKey(resource, size), attempt)) { CompletableDeferred() } }.await() }
        }

        suspend fun awaitDecodeCompletion(resource: ThumbnailResourceIdentity, size: ThumbnailSize, attempt: Int) {
            withTimeout(1_000) { synchronized(this) { decodeCompletions.getOrPut(DecodeAttempt(ThumbnailKey(resource, size), attempt)) { CompletableDeferred() } }.await() }
        }

        fun completeDecode(resource: ThumbnailResourceIdentity, size: ThumbnailSize, attempt: Int, value: String) {
            val decodeAttempt = DecodeAttempt(ThumbnailKey(resource, size), attempt)
            synchronized(this) { decodeResults.getOrPut(decodeAttempt) { CompletableDeferred() }.complete(value) }
        }
    }

    private data class DecodeStart(val key: ThumbnailKey, val source: String)
    private data class DecodeAttempt(val key: ThumbnailKey, val attempt: Int)
    private data class MemoryWrite(val key: ThumbnailKey, val value: String)
}
