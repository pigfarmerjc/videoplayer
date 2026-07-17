package com.example.videoplayer.media.thumbnail

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicLong

class ThumbnailScheduler<T : Any>(
    private val cache: ThumbnailCache<T>,
    private val scope: CoroutineScope,
    private val decodeWorkerCount: Int = 2
) {
    private val commands = Channel<Command<T>>(Channel.UNLIMITED)
    private val diskWrites = Channel<DiskWrite<T>>(Channel.UNLIMITED)
    private val requestIds = AtomicLong()

    init {
        require(decodeWorkerCount > 0) { "decodeWorkerCount must be positive" }
        scope.launch { processCommands() }
        scope.launch {
            for (write in diskWrites) {
                runCatching { cache.writeDisk(write.key, write.value) }
            }
        }
    }

    fun request(
        mediaId: Long,
        size: ThumbnailSize,
        priority: ThumbnailPriority
    ): Flow<ThumbnailResult<T>> = flow {
        val response = Channel<ThumbnailResult<T>>(capacity = 1)
        val requestId = requestIds.incrementAndGet()
        val key = ThumbnailKey(mediaId, size)
        commands.send(Command.Request(requestId, key, priority, response))
        try {
            response.receiveCatching().getOrNull()?.let { emit(it) }
        } finally {
            commands.trySend(Command.Cancel(requestId, key))
            response.close()
        }
    }

    fun setFastScrolling(isFastScrolling: Boolean) {
        commands.trySend(Command.SetFastScrolling(isFastScrolling))
    }

    private suspend fun processCommands() {
        val queued = PriorityQueue<InFlight<T>>(compareBy<InFlight<T>> { it.priority.ordinal }.thenBy { it.order })
        val inFlight = mutableMapOf<ThumbnailKey, InFlight<T>>()
        var fastScrolling = false
        var activeDecodes = 0
        var nextOrder = 0L

        fun startDecode(flight: InFlight<T>) {
            flight.started = true
            activeDecodes++
            flight.job = scope.launch {
                val value = runCatching { cache.decode(flight.key) }.getOrNull()
                withContext(NonCancellable) {
                    commands.send(Command.DecodeCompleted(flight, value))
                }
            }
        }

        fun startQueuedDecodes() {
            while (!fastScrolling && activeDecodes < decodeWorkerCount && queued.isNotEmpty()) {
                val flight = queued.remove()
                if (inFlight[flight.key] === flight && flight.requesters.isNotEmpty()) {
                    startDecode(flight)
                }
            }
        }

        for (command in commands) {
            when (command) {
                is Command.Request -> {
                    val cached = cache.loadMemory(command.key) ?: cache.loadDisk(command.key)
                    if (cached != null) {
                        cache.putMemory(command.key, cached)
                        command.response.trySend(ThumbnailResult(command.key, cached))
                        command.response.close()
                    } else {
                        val existing = inFlight[command.key]
                        if (existing == null) {
                            val flight = InFlight<T>(
                                key = command.key,
                                priority = command.priority,
                                order = nextOrder++
                            )
                            flight.requesters[command.requestId] = command.response
                            inFlight[command.key] = flight
                            queued += flight
                        } else {
                            existing.requesters[command.requestId] = command.response
                            if (!existing.started && command.priority.ordinal < existing.priority.ordinal) {
                                queued.remove(existing)
                                existing.priority = command.priority
                                queued += existing
                            }
                        }
                    }
                    startQueuedDecodes()
                }

                is Command.SetFastScrolling -> {
                    fastScrolling = command.value
                    startQueuedDecodes()
                }

                is Command.Cancel -> {
                    val flight = inFlight[command.key] ?: continue
                    flight.requesters.remove(command.requestId)
                    if (flight.requesters.isEmpty()) {
                        if (!flight.started) queued.remove(flight)
                        inFlight.remove(command.key, flight)
                        flight.job?.cancel()
                    }
                    startQueuedDecodes()
                }

                is Command.DecodeCompleted -> {
                    val flight = command.flight
                    if (flight.started) activeDecodes--
                    if (inFlight[flight.key] === flight) {
                        inFlight.remove(flight.key)
                        command.value?.let { value ->
                            cache.putMemory(flight.key, value)
                            val result = ThumbnailResult(flight.key, value)
                            flight.requesters.values.forEach { response ->
                                response.trySend(result)
                                response.close()
                            }
                            diskWrites.trySend(DiskWrite(flight.key, value))
                        } ?: flight.requesters.values.forEach { it.close() }
                        flight.requesters.clear()
                    }
                    startQueuedDecodes()
                }
            }
        }
    }

    private class InFlight<T : Any>(
        val key: ThumbnailKey,
        var priority: ThumbnailPriority,
        val order: Long,
        val requesters: MutableMap<Long, Channel<ThumbnailResult<T>>> = linkedMapOf(),
        var started: Boolean = false,
        var job: Job? = null
    )

    private data class DiskWrite<T : Any>(val key: ThumbnailKey, val value: T)

    private sealed interface Command<T : Any> {
        data class Request<T : Any>(
            val requestId: Long,
            val key: ThumbnailKey,
            val priority: ThumbnailPriority,
            val response: Channel<ThumbnailResult<T>>
        ) : Command<T>

        data class SetFastScrolling<T : Any>(val value: Boolean) : Command<T>
        data class Cancel<T : Any>(val requestId: Long, val key: ThumbnailKey) : Command<T>
        data class DecodeCompleted<T : Any>(val flight: InFlight<T>, val value: T?) : Command<T>
    }
}
