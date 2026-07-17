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

class ThumbnailScheduler<T : Any, S : Any>(
    private val cache: ThumbnailCache<T, S>,
    private val scope: CoroutineScope,
    private val decodeWorkerCount: Int = 2
) {
    private val commands = Channel<Command<T, S>>(Channel.UNLIMITED)
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
        resource: ThumbnailResourceIdentity,
        source: S,
        size: ThumbnailSize,
        priority: ThumbnailPriority
    ): Flow<ThumbnailResult<T>> = flow {
        val response = Channel<ThumbnailResult<T>>(capacity = 1)
        val requestId = requestIds.incrementAndGet()
        val key = ThumbnailKey(resource, size)
        commands.send(Command.Request(requestId, key, source, priority, response))
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
        val queued = PriorityQueue<InFlight<T, S>>(compareBy<InFlight<T, S>> { it.priority.ordinal }.thenBy { it.order })
        val inFlight = mutableMapOf<ThumbnailKey, InFlight<T, S>>()
        var fastScrolling = false
        var activeDecodes = 0
        var nextOrder = 0L

        fun startDiskLookup(flight: InFlight<T, S>) {
            flight.diskLookupJob = scope.launch {
                val value = runCatching { cache.loadDisk(flight.key) }.getOrNull()
                withContext(NonCancellable) {
                    commands.send(Command.DiskLookupCompleted(flight, value))
                }
            }
        }

        fun startDecode(flight: InFlight<T, S>) {
            flight.started = true
            activeDecodes++
            flight.decodeJob = scope.launch {
                val value = runCatching { cache.decode(flight.key, flight.source) }.getOrNull()
                withContext(NonCancellable) {
                    commands.send(Command.DecodeCompleted(flight, value))
                }
            }
        }

        fun releaseDecodePermit(flight: InFlight<T, S>) {
            if (flight.started && !flight.decodePermitReleased) {
                flight.decodePermitReleased = true
                activeDecodes--
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

        fun deliver(flight: InFlight<T, S>, value: T?) {
            inFlight.remove(flight.key, flight)
            if (value != null) {
                val result = ThumbnailResult(flight.key, value)
                flight.requesters.values.forEach { response ->
                    response.trySend(result)
                    response.close()
                }
                diskWrites.trySend(DiskWrite(flight.key, value))
            } else {
                flight.requesters.values.forEach { it.close() }
            }
            flight.requesters.clear()
        }

        for (command in commands) {
            when (command) {
                is Command.Request -> {
                    val memory = cache.loadMemory(command.key)
                    if (memory != null) {
                        command.response.trySend(ThumbnailResult(command.key, memory))
                        command.response.close()
                    } else {
                        val existing = inFlight[command.key]
                        if (existing == null) {
                            val flight = InFlight<T, S>(
                                key = command.key,
                                source = command.source,
                                priority = command.priority,
                                order = nextOrder++
                            )
                            flight.requesters[command.requestId] = command.response
                            inFlight[command.key] = flight
                            startDiskLookup(flight)
                        } else {
                            existing.requesters[command.requestId] = command.response
                            if (!existing.started && command.priority.ordinal < existing.priority.ordinal) {
                                if (!existing.diskLookupPending) queued.remove(existing)
                                existing.priority = command.priority
                                if (!existing.diskLookupPending) queued += existing
                            }
                        }
                    }
                    startQueuedDecodes()
                }

                is Command.DiskLookupCompleted -> {
                    val flight = command.flight
                    if (inFlight[flight.key] === flight) {
                        flight.diskLookupPending = false
                        if (command.value != null) {
                            cache.putMemory(flight.key, command.value)
                            deliver(flight, command.value)
                        } else if (flight.requesters.isNotEmpty()) {
                            queued += flight
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
                        if (!flight.started && !flight.diskLookupPending) queued.remove(flight)
                        inFlight.remove(command.key, flight)
                        flight.diskLookupJob?.cancel()
                        flight.decodeJob?.cancel()
                        releaseDecodePermit(flight)
                    }
                    startQueuedDecodes()
                }

                is Command.DecodeCompleted -> {
                    val flight = command.flight
                    releaseDecodePermit(flight)
                    if (inFlight[flight.key] === flight) {
                        command.value?.let { cache.putMemory(flight.key, it) }
                        deliver(flight, command.value)
                    }
                    startQueuedDecodes()
                }
            }
        }
    }

    private class InFlight<T : Any, S : Any>(
        val key: ThumbnailKey,
        val source: S,
        var priority: ThumbnailPriority,
        val order: Long,
        val requesters: MutableMap<Long, Channel<ThumbnailResult<T>>> = linkedMapOf(),
        var diskLookupPending: Boolean = true,
        var started: Boolean = false,
        var decodePermitReleased: Boolean = false,
        var diskLookupJob: Job? = null,
        var decodeJob: Job? = null
    )

    private data class DiskWrite<T : Any>(val key: ThumbnailKey, val value: T)

    private sealed interface Command<T : Any, S : Any> {
        data class Request<T : Any, S : Any>(
            val requestId: Long,
            val key: ThumbnailKey,
            val source: S,
            val priority: ThumbnailPriority,
            val response: Channel<ThumbnailResult<T>>
        ) : Command<T, S>

        data class DiskLookupCompleted<T : Any, S : Any>(
            val flight: InFlight<T, S>,
            val value: T?
        ) : Command<T, S>

        data class SetFastScrolling<T : Any, S : Any>(val value: Boolean) : Command<T, S>
        data class Cancel<T : Any, S : Any>(val requestId: Long, val key: ThumbnailKey) : Command<T, S>
        data class DecodeCompleted<T : Any, S : Any>(val flight: InFlight<T, S>, val value: T?) : Command<T, S>
    }
}
