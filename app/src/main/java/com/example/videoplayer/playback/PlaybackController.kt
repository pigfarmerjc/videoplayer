package com.example.videoplayer.playback

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

class PlaybackControllerClosedException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

class PlaybackController(
    private val engineFactory: PlayerEngineFactory,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    initialEngineChoice: EngineChoice = EngineChoice.EXO
) {
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(PlaybackState())
    private val preferredInitialChoice = initialEngineChoice
    private val engineListener = PlayerEngine.Listener { generation, event ->
        commands.trySend(Command.EngineCallback(generation, event))
    }
    private val actorJob: Job

    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    init {
        actorJob = scope.launch { processCommands() }
        actorJob.invokeOnCompletion { cause ->
            closeAndDrain(
                PlaybackControllerClosedException(
                    message = if (_state.value.isReleased) {
                        "PlaybackController has been released"
                    } else {
                        "PlaybackController actor has stopped"
                    },
                    cause = cause
                )
            )
        }
    }

    suspend fun load(session: PlaybackSession) {
        submit { Command.Load(session, it) }
    }

    suspend fun play() {
        submit { Command.Play(it) }
    }

    suspend fun pause() {
        submit { Command.Pause(it) }
    }

    suspend fun seekTo(positionMs: Long) {
        require(positionMs >= 0L) { "Playback position must not be negative" }
        submit { Command.SeekTo(positionMs, it) }
    }

    suspend fun selectAudioTrack(trackId: String?) {
        submit { Command.SelectAudioTrack(trackId, it) }
    }

    suspend fun switchEngine(choice: EngineChoice) {
        submit { Command.SwitchEngine(choice, it) }
    }

    suspend fun release() {
        submit { Command.Release(it) }
    }

    private suspend fun submit(command: (CompletableDeferred<Unit>) -> Command) {
        val completion = CompletableDeferred<Unit>()
        val result = commands.trySend(command(completion))
        if (result.isFailure) {
            throw PlaybackControllerClosedException(
                "PlaybackController is not accepting commands",
                result.exceptionOrNull()
            )
        }
        completion.await()
    }

    private fun closeAndDrain(error: PlaybackControllerClosedException) {
        commands.close(error)
        while (true) {
            val command = commands.tryReceive().getOrNull() ?: break
            command.completion?.completeExceptionally(error)
        }
    }

    private suspend fun processCommands() {
        var activeEngine: PlayerEngine? = null
        var hasUsableEngine = false
        var acceptedGeneration: Long? = null
        var generation = 0L
        var currentSession: PlaybackSession? = null
        var preferredChoice = preferredInitialChoice

        fun stateFor(
            session: PlaybackSession?,
            engine: PlayerEngine?,
            usable: Boolean,
            error: Throwable?
        ) = PlaybackState(
            session = session,
            engineChoice = engine?.choice,
            generation = generation,
            positionMs = session?.positionMs ?: 0L,
            playWhenReady = session?.playWhenReady ?: false,
            isPlaying = false,
            speed = session?.speed ?: 1f,
            selectedAudioTrackId = session?.selectedAudioTrackId,
            selectedSubtitleTrackId = session?.selectedSubtitleTrackId,
            hasUsableEngine = usable,
            error = error
        )

        fun publish(error: Throwable? = null) {
            _state.value = stateFor(currentSession, activeEngine, hasUsableEngine, error)
        }

        suspend fun restore(engine: PlayerEngine, session: PlaybackSession, nextGeneration: Long) {
            engine.load(session, nextGeneration)
            engine.seekTo(session.positionMs)
            engine.setPlaybackSpeed(session.speed)
            engine.selectAudioTrack(session.selectedAudioTrackId)
            engine.selectSubtitleTrack(session.selectedSubtitleTrackId)
            if (session.playWhenReady) engine.play() else engine.pause()
        }

        suspend fun releaseQuarantinedEngine(errorOnSuccess: Throwable? = null) {
            val engine = checkNotNull(activeEngine)
            hasUsableEngine = false
            acceptedGeneration = null
            publish()
            try {
                engine.release()
                activeEngine = null
                publish(errorOnSuccess)
            } catch (releaseError: Throwable) {
                publish(releaseError)
                throw releaseError
            }
        }

        suspend fun cleanupAfterRestoreFailure(restoreError: Throwable) {
            val engine = checkNotNull(activeEngine)
            hasUsableEngine = false
            acceptedGeneration = null
            publish(restoreError)
            try {
                engine.release()
                activeEngine = null
                publish(restoreError)
            } catch (releaseError: Throwable) {
                restoreError.addSuppressed(releaseError)
                publish(restoreError)
            }
            throw restoreError
        }

        suspend fun activate(choice: EngineChoice, session: PlaybackSession) {
            check(activeEngine == null) { "Cannot activate a second player engine" }
            generation++
            currentSession = session
            acceptedGeneration = null
            hasUsableEngine = false
            val engine = try {
                engineFactory.create(choice)
            } catch (createError: Throwable) {
                publish(createError)
                throw createError
            }
            activeEngine = engine
            publish()
            try {
                engine.setListener(engineListener)
                restore(engine, session, generation)
                hasUsableEngine = true
                acceptedGeneration = generation
                publish()
            } catch (restoreError: Throwable) {
                cleanupAfterRestoreFailure(restoreError)
            }
        }

        suspend fun reloadActive(session: PlaybackSession) {
            val engine = checkNotNull(activeEngine)
            check(hasUsableEngine) { "Player engine is quarantined" }
            generation++
            currentSession = session
            hasUsableEngine = false
            acceptedGeneration = null
            publish()
            try {
                restore(engine, session, generation)
                hasUsableEngine = true
                acceptedGeneration = generation
                publish()
            } catch (restoreError: Throwable) {
                cleanupAfterRestoreFailure(restoreError)
            }
        }

        suspend fun requireUsableEngine(): PlayerEngine {
            check(hasUsableEngine) { "No usable player engine" }
            return checkNotNull(activeEngine) { "No active player engine" }
        }

        for (command in commands) {
            try {
                when (command) {
                    is Command.Load -> {
                        currentSession = command.session
                        if (activeEngine != null && !hasUsableEngine) {
                            releaseQuarantinedEngine()
                        }
                        if (activeEngine == null) {
                            activate(preferredChoice, command.session)
                        } else {
                            reloadActive(command.session)
                        }
                        command.completion.complete(Unit)
                    }

                    is Command.Play -> {
                        requireUsableEngine().play()
                        currentSession = currentSession?.copy(playWhenReady = true)
                        _state.value = _state.value.copy(
                            session = currentSession,
                            playWhenReady = true,
                            error = null
                        )
                        command.completion.complete(Unit)
                    }

                    is Command.Pause -> {
                        requireUsableEngine().pause()
                        currentSession = currentSession?.copy(playWhenReady = false)
                        _state.value = _state.value.copy(
                            session = currentSession,
                            playWhenReady = false,
                            error = null
                        )
                        command.completion.complete(Unit)
                    }

                    is Command.SeekTo -> {
                        requireUsableEngine().seekTo(command.positionMs)
                        currentSession = currentSession?.copy(positionMs = command.positionMs)
                        _state.value = _state.value.copy(
                            session = currentSession,
                            positionMs = command.positionMs,
                            error = null
                        )
                        command.completion.complete(Unit)
                    }

                    is Command.SelectAudioTrack -> {
                        requireUsableEngine().selectAudioTrack(command.trackId)
                        currentSession = currentSession?.copy(selectedAudioTrackId = command.trackId)
                        _state.value = _state.value.copy(
                            session = currentSession,
                            selectedAudioTrackId = command.trackId,
                            error = null
                        )
                        command.completion.complete(Unit)
                    }

                    is Command.SwitchEngine -> {
                        val engine = activeEngine
                        val session = currentSession
                        if (engine == null) {
                            preferredChoice = command.choice
                            if (session != null) activate(command.choice, session) else publish()
                        } else if (hasUsableEngine && engine.choice == command.choice) {
                            // Already using the requested engine; preserve observable state.
                        } else {
                            if (hasUsableEngine) {
                                val snapshot = engine.snapshot().normalized(checkNotNull(session))
                                currentSession = checkNotNull(session).copy(
                                    positionMs = snapshot.positionMs,
                                    playWhenReady = snapshot.playWhenReady,
                                    speed = snapshot.speed,
                                    selectedAudioTrackId = snapshot.selectedAudioTrackId,
                                    selectedSubtitleTrackId = snapshot.selectedSubtitleTrackId
                                )
                            }
                            releaseQuarantinedEngine()
                            preferredChoice = command.choice
                            activate(command.choice, checkNotNull(currentSession))
                        }
                        command.completion.complete(Unit)
                    }

                    is Command.Release -> {
                        if (activeEngine != null) {
                            releaseQuarantinedEngine()
                        }
                        currentSession = null
                        acceptedGeneration = null
                        hasUsableEngine = false
                        _state.value = PlaybackState(
                            generation = generation,
                            isReleased = true
                        )
                        command.completion.complete(Unit)
                        return
                    }

                    is Command.EngineCallback -> {
                        if (hasUsableEngine && command.generation == acceptedGeneration) {
                            when (val event = command.event) {
                                is EngineEvent.PositionChanged -> {
                                    val positionMs = event.positionMs.coerceAtLeast(0L)
                                    currentSession = currentSession?.copy(positionMs = positionMs)
                                    _state.value = _state.value.copy(session = currentSession, positionMs = positionMs)
                                }

                                is EngineEvent.PlayWhenReadyChanged -> {
                                    currentSession = currentSession?.copy(playWhenReady = event.playWhenReady)
                                    _state.value = _state.value.copy(
                                        session = currentSession,
                                        playWhenReady = event.playWhenReady
                                    )
                                }

                                is EngineEvent.PlayingChanged -> {
                                    _state.value = _state.value.copy(isPlaying = event.isPlaying)
                                }

                                is EngineEvent.SpeedChanged -> if (event.speed.isFinite() && event.speed > 0f) {
                                    currentSession = currentSession?.copy(speed = event.speed)
                                    _state.value = _state.value.copy(session = currentSession, speed = event.speed)
                                }

                                is EngineEvent.AudioTrackChanged -> {
                                    currentSession = currentSession?.copy(selectedAudioTrackId = event.trackId)
                                    _state.value = _state.value.copy(
                                        session = currentSession,
                                        selectedAudioTrackId = event.trackId
                                    )
                                }

                                is EngineEvent.SubtitleTrackChanged -> {
                                    currentSession = currentSession?.copy(selectedSubtitleTrackId = event.trackId)
                                    _state.value = _state.value.copy(
                                        session = currentSession,
                                        selectedSubtitleTrackId = event.trackId
                                    )
                                }

                                is EngineEvent.Error -> _state.value = _state.value.copy(error = event.cause)
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                command.completion?.completeExceptionally(error)
                throw error
            } catch (error: Throwable) {
                if (_state.value.error !== error) {
                    _state.value = _state.value.copy(error = error)
                }
                command.completion?.completeExceptionally(error)
            }
        }
    }

    private fun PlaybackSnapshot.normalized(fallback: PlaybackSession): PlaybackSnapshot = copy(
        positionMs = positionMs.coerceAtLeast(0L),
        speed = speed.takeIf { it.isFinite() && it > 0f } ?: fallback.speed
    )

    private sealed interface Command {
        val completion: CompletableDeferred<Unit>?

        data class Load(
            val session: PlaybackSession,
            override val completion: CompletableDeferred<Unit>
        ) : Command

        data class Play(override val completion: CompletableDeferred<Unit>) : Command
        data class Pause(override val completion: CompletableDeferred<Unit>) : Command

        data class SeekTo(
            val positionMs: Long,
            override val completion: CompletableDeferred<Unit>
        ) : Command

        data class SelectAudioTrack(
            val trackId: String?,
            override val completion: CompletableDeferred<Unit>
        ) : Command

        data class SwitchEngine(
            val choice: EngineChoice,
            override val completion: CompletableDeferred<Unit>
        ) : Command

        data class Release(override val completion: CompletableDeferred<Unit>) : Command

        data class EngineCallback(
            val generation: Long,
            val event: EngineEvent
        ) : Command {
            override val completion: CompletableDeferred<Unit>? = null
        }
    }
}
