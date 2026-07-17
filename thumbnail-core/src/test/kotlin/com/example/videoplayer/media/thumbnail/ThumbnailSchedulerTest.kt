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
import kotlinx.coroutines.withTimeoutOrNull
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
    fun cancellationResistantOldFlightKeepsDecodePermitUntilPhysicalCompletion() = runBlocking {
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
        assertEquals(1, cache.maxActiveDecodes)
        assertEquals(null, withTimeoutOrNull(100) { cache.awaitDecodeStart(resource, size, 2) })

        cache.completeDecode(resource, size, 1, "stale-frame")
        cache.awaitDecodeCompletion(resource, size, 1)
        cache.awaitDecodeStart(resource, size, 2)
        cache.completeDecode(resource, size, 2, "new-frame")
        assertEquals("new-frame", replacement.await().value)

        assertTrue(cache.maxActiveDecodes <= 1)
        assertEquals(listOf("new-frame"), cache.memoryWrites.map { it.value })
    }

    @Test
    fun boundedDiskWriteQueueDropsOldestPendingWriteWithoutBlockingResults() = runBlocking {
        val cache = DeferredThumbnailCache().apply { holdDiskWrites() }
        val scheduler = scheduler(cache, diskWriteCapacity = 1)
        val size = ThumbnailSize(180, 120)
        val first = resource("first", 1)
        val second = resource("second", 1)
        val third = resource("third", 1)

        val firstResult = async(start = CoroutineStart.UNDISPATCHED) {
            scheduler.request(first, "first", size, ThumbnailPriority.VISIBLE).single()
        }
        cache.awaitDecodeStart(first, size, 1)
        cache.completeDecode(first, size, 1, "first-frame")
        firstResult.await()
        cache.awaitDiskWriteStart()

        val secondResult = async(start = CoroutineStart.UNDISPATCHED) {
            scheduler.request(second, "second", size, ThumbnailPriority.VISIBLE).single()
        }
        cache.awaitDecodeStart(second, size, 1)
        cache.completeDecode(second, size, 1, "second-frame")
        secondResult.await()

        val thirdResult = async(start = CoroutineStart.UNDISPATCHED) {
            scheduler.request(third, "third", size, ThumbnailPriority.VISIBLE).single()
        }
        cache.awaitDecodeStart(third, size, 1)
        cache.completeDecode(third, size, 1, "third-frame")
        thirdResult.await()

        cache.releaseDiskWrites()
        cache.awaitDiskWrites(2)

        assertEquals(listOf("first-frame", "third-frame"), cache.diskWrites)
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

    @Test
    fun fastScrollGateIgnoresSlowDragAndUsesHysteresisBeforeSettling() {
        val gate = FastScrollGate(
            enterVelocity = 1_800f,
            exitVelocity = 700f,
            slowSamplesToExit = 2
        )

        assertEquals(false, gate.update(isScrollInProgress = true, velocity = 500f))
        assertEquals(true, gate.update(isScrollInProgress = true, velocity = 2_200f))
        assertEquals(true, gate.update(isScrollInProgress = true, velocity = 600f))
        assertEquals(false, gate.update(isScrollInProgress = true, velocity = 500f))
        assertEquals(false, gate.update(isScrollInProgress = false, velocity = 3_000f))
    }

    @Test
    fun multiColumnSlowScrollAcrossARowDoesNotEnterFastMode() {
        val tracker = GridScrollVelocityTracker()
        val gate = FastScrollGate(enterVelocity = 1_800f)

        tracker.update(
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 190,
            columns = 4,
            averageLineSizePx = 200f,
            elapsedSeconds = 0f
        )
        val velocity = tracker.update(
            firstVisibleItemIndex = 4,
            firstVisibleItemScrollOffset = 10,
            columns = 4,
            averageLineSizePx = 200f,
            elapsedSeconds = 0.1f
        )

        assertEquals(200f, velocity, 0.01f)
        assertEquals(false, gate.update(isScrollInProgress = true, velocity = velocity))
    }

    @Test
    fun multiColumnFastFlingEntersFastMode() {
        val tracker = GridScrollVelocityTracker()
        val gate = FastScrollGate(enterVelocity = 1_800f)

        tracker.update(0, 0, columns = 4, averageLineSizePx = 200f, elapsedSeconds = 0f)
        val velocity = tracker.update(
            firstVisibleItemIndex = 12,
            firstVisibleItemScrollOffset = 20,
            columns = 4,
            averageLineSizePx = 200f,
            elapsedSeconds = 0.1f
        )

        assertEquals(6_200f, velocity, 0.01f)
        assertEquals(true, gate.update(isScrollInProgress = true, velocity = velocity))
    }

    private fun resource(name: String, version: Long) = ThumbnailResourceIdentity(
        storageKey = "file:/$name.mp4",
        uri = "content://media/external/video/$name",
        dateModified = version
    )

    private fun scheduler(
        cache: DeferredThumbnailCache,
        diskWriteCapacity: Int = 16
    ): ThumbnailScheduler<String, String> =
        ThumbnailScheduler(
            cache = cache,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            decodeWorkerCount = 1,
            diskWriteCapacity = diskWriteCapacity
        )

    private class DeferredThumbnailCache : ThumbnailCache<String, String> {
        val memory = mutableMapOf<ThumbnailKey, String>()
        val disk = mutableMapOf<ThumbnailKey, String>()
        val decodeStarts = mutableListOf<DecodeStart>()
        val memoryWrites = mutableListOf<MemoryWrite>()
        val diskWrites = mutableListOf<String>()
        var maxActiveDecodes = 0
            private set
        private var memoryLookups = 0
        private var diskCompletions = 0
        private var activeDecodes = 0
        private var holdDiskWrites = false
        private val diskStarted = mutableMapOf<ThumbnailKey, CompletableDeferred<Unit>>()
        private val delayedDisk = mutableMapOf<ThumbnailKey, CompletableDeferred<String?>>()
        private val decodeStarted = mutableMapOf<DecodeAttempt, CompletableDeferred<Unit>>()
        private val decodeResults = mutableMapOf<DecodeAttempt, CompletableDeferred<String?>>()
        private val decodeCompletions = mutableMapOf<DecodeAttempt, CompletableDeferred<Unit>>()
        private val cancellationResistantAttempts = mutableSetOf<DecodeAttempt>()
        private val diskWriteStarted = CompletableDeferred<Unit>()
        private val diskWriteRelease = CompletableDeferred<Unit>()

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
                synchronized(this) {
                    activeDecodes++
                    maxActiveDecodes = maxOf(maxActiveDecodes, activeDecodes)
                }
                if (attempt in cancellationResistantAttempts) {
                    withContext(NonCancellable) { result.await() }
                } else {
                    result.await()
                }
            } finally {
                synchronized(this) {
                    activeDecodes--
                    decodeCompletions.getOrPut(attempt) { CompletableDeferred() }.complete(Unit)
                }
            }
        }

        override suspend fun putMemory(key: ThumbnailKey, value: String) {
            synchronized(this) {
                memory[key] = value
                memoryWrites += MemoryWrite(key, value)
            }
        }

        override suspend fun writeDisk(key: ThumbnailKey, value: String) {
            val waitForRelease = synchronized(this) {
                diskWrites += value
                holdDiskWrites
            }
            diskWriteStarted.complete(Unit)
            if (waitForRelease) diskWriteRelease.await()
        }

        fun delayDisk(resource: ThumbnailResourceIdentity, size: ThumbnailSize) {
            synchronized(this) { delayedDisk[ThumbnailKey(resource, size)] = CompletableDeferred() }
        }

        fun completeDisk(resource: ThumbnailResourceIdentity, size: ThumbnailSize, value: String?) {
            synchronized(this) { delayedDisk.getValue(ThumbnailKey(resource, size)).complete(value) }
        }

        fun ignoreDecodeCancellation(resource: ThumbnailResourceIdentity, size: ThumbnailSize, attempt: Int) {
            synchronized(this) { cancellationResistantAttempts += DecodeAttempt(ThumbnailKey(resource, size), attempt) }
        }

        fun holdDiskWrites() {
            holdDiskWrites = true
        }

        fun releaseDiskWrites() {
            diskWriteRelease.complete(Unit)
        }

        suspend fun awaitDiskWriteStart() {
            withTimeout(1_000) { diskWriteStarted.await() }
        }

        suspend fun awaitDiskWrites(count: Int) {
            withTimeout(1_000) {
                while (synchronized(this) { diskWrites.size } < count) kotlinx.coroutines.yield()
            }
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
