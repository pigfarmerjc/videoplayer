package com.example.videoplayer.data.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MediaLibraryStore(
    private val scanner: MediaLibraryScanner,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(MediaLibraryState())

    val state: StateFlow<MediaLibraryState> = _state.asStateFlow()

    init {
        scope.launch { processCommands() }
    }

    suspend fun refresh(force: Boolean) {
        val completion = CompletableDeferred<Unit>()
        commands.send(Command.Refresh(force, completion))
        try {
            completion.await()
        } catch (error: CancellationException) {
            commands.trySend(Command.CallerCancelled(completion))
            throw error
        }
    }

    private suspend fun processCommands() {
        var generation = 0L
        var active: ActiveScan? = null
        var normalLane: ActiveScan? = null
        var completedForced: CompletedForced? = null
        val pendingNormalCallers = mutableListOf<CompletableDeferred<Unit>>()

        fun beginScan(force: Boolean, callers: List<CompletableDeferred<Unit>>) {
            generation++
            val scan = startScan(generation, force, callers.first())
            callers.drop(1).forEach { scan.callers += it }
            active = scan
            if (!force) normalLane = scan
            _state.value = _state.value.copy(
                generation = generation,
                isRefreshing = true,
                error = null
            )
        }

        fun startPendingNormalIfPossible() {
            if (active != null || normalLane != null || pendingNormalCallers.isEmpty()) return
            val callers = pendingNormalCallers.toList()
            pendingNormalCallers.clear()
            beginScan(force = false, callers)
        }

        for (command in commands) {
            when (command) {
                is Command.Refresh -> {
                    val current = active
                    when {
                        !command.force && current != null -> {
                            current.callers += command.completion
                        }

                        !command.force && completedForced != null && normalLane != null -> {
                            command.completion.complete(Unit)
                        }

                        !command.force && normalLane != null -> {
                            pendingNormalCallers += command.completion
                        }

                        else -> {
                            if (command.force) {
                                active?.let { superseded ->
                                    superseded.job.cancel()
                                    superseded.callers.forEach {
                                        it.completeExceptionally(CancellationException("Superseded by a newer refresh"))
                                    }
                                    superseded.callers.clear()
                                }
                                normalLane?.takeIf { it !== active }?.job?.cancel()
                                completedForced = null
                            }
                            beginScan(command.force, listOf(command.completion))
                        }
                    }
                }

                is Command.ScanResult -> {
                    if (active?.generation == command.generation) {
                        val completed = active ?: continue
                        _state.value = command.result.toState(command.generation)
                        completed.callers.forEach { it.complete(Unit) }
                        completed.callers.clear()
                        active = null
                        if (completed.force && normalLane != null) {
                            completedForced = CompletedForced(command.generation)
                        }
                        if (!completed.force && normalLane?.generation == command.generation) {
                            normalLane = null
                            completedForced = null
                            startPendingNormalIfPossible()
                        }
                    } else if (normalLane?.generation == command.generation) {
                        normalLane = null
                        completedForced = null
                        startPendingNormalIfPossible()
                    }
                }

                is Command.ScanFailed -> {
                    if (active?.generation == command.generation) {
                        val failed = active ?: continue
                        _state.value = MediaLibraryState(
                            generation = command.generation,
                            error = LibraryError.ScanFailure(command.error)
                        )
                        failed.callers.forEach { it.complete(Unit) }
                        failed.callers.clear()
                        active = null
                        if (!failed.force && normalLane?.generation == command.generation) {
                            normalLane = null
                            completedForced = null
                            startPendingNormalIfPossible()
                        }
                    } else if (normalLane?.generation == command.generation) {
                        normalLane = null
                        completedForced = null
                        startPendingNormalIfPossible()
                    }
                }

                is Command.ScanCancelled -> {
                    if (active?.generation == command.generation) {
                        val cancelled = active ?: continue
                        active = null
                        if (state.value.generation == command.generation) {
                            _state.value = _state.value.copy(isRefreshing = false)
                        }
                        cancelled.callers.forEach {
                            it.completeExceptionally(CancellationException("Media scan cancelled"))
                        }
                        cancelled.callers.clear()
                    }
                    if (normalLane?.generation == command.generation) {
                        normalLane = null
                        completedForced = null
                        startPendingNormalIfPossible()
                    }
                }

                is Command.CallerCancelled -> {
                    active?.callers?.remove(command.completion)
                    pendingNormalCallers.remove(command.completion)
                }
            }
        }
    }

    private fun startScan(
        generation: Long,
        force: Boolean,
        completion: CompletableDeferred<Unit>
    ): ActiveScan {
        lateinit var job: Job
        val callers = linkedSetOf(completion)
        job = scope.launch {
            try {
                commands.send(Command.ScanResult(generation, scanner.scan(force)))
            } catch (_: CancellationException) {
                commands.send(Command.ScanCancelled(generation))
            } catch (error: Throwable) {
                commands.send(Command.ScanFailed(generation, error))
            }
        }
        return ActiveScan(generation, force, job, callers)
    }

    private fun MediaLibraryScanResult.toState(generation: Long): MediaLibraryState {
        val videoFailure = (videos as? MediaQueryResult.Failure)?.cause
        val photoFailure = (photos as? MediaQueryResult.Failure)?.cause
        return MediaLibraryState(
            generation = generation,
            videos = videos.itemsOrEmpty(),
            photos = photos.itemsOrEmpty(),
            error = if (videoFailure == null && photoFailure == null) null
            else LibraryError.PartialQueryFailure(videoFailure, photoFailure)
        )
    }

    private fun MediaQueryResult.itemsOrEmpty(): List<LibraryMedia> = when (this) {
        is MediaQueryResult.Success -> items
        is MediaQueryResult.Failure -> emptyList()
    }

    private data class ActiveScan(
        val generation: Long,
        val force: Boolean,
        val job: Job,
        val callers: MutableSet<CompletableDeferred<Unit>>
    )

    private data class CompletedForced(val generation: Long)

    private sealed interface Command {
        data class Refresh(
            val force: Boolean,
            val completion: CompletableDeferred<Unit>
        ) : Command

        data class ScanResult(val generation: Long, val result: MediaLibraryScanResult) : Command
        data class ScanFailed(val generation: Long, val error: Throwable) : Command
        data class ScanCancelled(val generation: Long) : Command
        data class CallerCancelled(val completion: CompletableDeferred<Unit>) : Command
    }
}
