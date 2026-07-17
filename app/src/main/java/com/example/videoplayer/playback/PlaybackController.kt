package com.example.videoplayer.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PlaybackController(
    private val engineFactory: PlayerEngineFactory,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    initialEngineChoice: EngineChoice = EngineChoice.EXO
) {
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(PlaybackState(engineChoice = initialEngineChoice))
    private val engineListener = PlayerEngine.Listener { generation, event ->
        commands.trySend(Command.EngineCallback(generation, event))
    }

    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    init {
        scope.launch { processCommands() }
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
        commands.send(command(completion))
        completion.await()
    }

    private suspend fun processCommands() {
        var activeEngine: PlayerEngine? = null
        var generation = 0L
        var currentSession: PlaybackSession? = null
        var currentChoice = _state.value.engineChoice
        var released = false

        suspend fun restore(engine: PlayerEngine, session: PlaybackSession, nextGeneration: Long) {
            engine.load(session, nextGeneration)
            engine.seekTo(session.positionMs)
            engine.setPlaybackSpeed(session.speed)
            engine.selectAudioTrack(session.selectedAudioTrackId)
            engine.selectSubtitleTrack(session.selectedSubtitleTrackId)
            if (session.playWhenReady) engine.play() else engine.pause()
        }

        fun publish(session: PlaybackSession, choice: EngineChoice, nextGeneration: Long) {
            _state.value = PlaybackState(
                session = session,
                engineChoice = choice,
                generation = nextGeneration,
                positionMs = session.positionMs,
                isPlaying = session.playWhenReady,
                speed = session.speed,
                selectedAudioTrackId = session.selectedAudioTrackId,
                selectedSubtitleTrackId = session.selectedSubtitleTrackId
            )
        }

        suspend fun activate(choice: EngineChoice, session: PlaybackSession, nextGeneration: Long): PlayerEngine {
            val engine = engineFactory.create(choice)
            engine.setListener(engineListener)
            return try {
                restore(engine, session, nextGeneration)
                engine
            } catch (error: Throwable) {
                runCatching { engine.release() }
                throw error
            }
        }

        fun takeActiveEngine(): PlayerEngine? = activeEngine.also { activeEngine = null }

        for (command in commands) {
            try {
                when (command) {
                    is Command.Load -> {
                        check(!released) { "PlaybackController has been released" }
                        generation++
                        val engine = activeEngine
                        if (engine == null) {
                            activeEngine = activate(currentChoice, command.session, generation)
                        } else {
                            restore(engine, command.session, generation)
                        }
                        currentSession = command.session
                        publish(command.session, currentChoice, generation)
                        command.completion.complete(Unit)
                    }

                    is Command.Play -> {
                        check(!released) { "PlaybackController has been released" }
                        activeEngine?.play()
                        currentSession = currentSession?.copy(playWhenReady = true)
                        _state.value = _state.value.copy(
                            session = currentSession,
                            isPlaying = true,
                            error = null
                        )
                        command.completion.complete(Unit)
                    }

                    is Command.Pause -> {
                        check(!released) { "PlaybackController has been released" }
                        activeEngine?.pause()
                        currentSession = currentSession?.copy(playWhenReady = false)
                        _state.value = _state.value.copy(
                            session = currentSession,
                            isPlaying = false,
                            error = null
                        )
                        command.completion.complete(Unit)
                    }

                    is Command.SeekTo -> {
                        check(!released) { "PlaybackController has been released" }
                        activeEngine?.seekTo(command.positionMs)
                        currentSession = currentSession?.copy(positionMs = command.positionMs)
                        _state.value = _state.value.copy(
                            session = currentSession,
                            positionMs = command.positionMs,
                            error = null
                        )
                        command.completion.complete(Unit)
                    }

                    is Command.SelectAudioTrack -> {
                        check(!released) { "PlaybackController has been released" }
                        activeEngine?.selectAudioTrack(command.trackId)
                        currentSession = currentSession?.copy(selectedAudioTrackId = command.trackId)
                        _state.value = _state.value.copy(
                            session = currentSession,
                            selectedAudioTrackId = command.trackId,
                            error = null
                        )
                        command.completion.complete(Unit)
                    }

                    is Command.SwitchEngine -> {
                        check(!released) { "PlaybackController has been released" }
                        val oldEngine = activeEngine
                        val session = currentSession
                        if (oldEngine == null || session == null) {
                            currentChoice = command.choice
                            _state.value = _state.value.copy(engineChoice = command.choice, error = null)
                        } else if (oldEngine.choice != command.choice) {
                            val snapshot = oldEngine.snapshot().normalized(session)
                            val restoredSession = session.copy(
                                positionMs = snapshot.positionMs,
                                playWhenReady = snapshot.isPlaying,
                                speed = snapshot.speed,
                                selectedAudioTrackId = snapshot.selectedAudioTrackId,
                                selectedSubtitleTrackId = snapshot.selectedSubtitleTrackId
                            )
                            takeActiveEngine()?.release()
                            generation++
                            currentChoice = command.choice
                            activeEngine = activate(command.choice, restoredSession, generation)
                            currentSession = restoredSession
                            publish(restoredSession, command.choice, generation)
                        }
                        command.completion.complete(Unit)
                    }

                    is Command.Release -> {
                        if (!released) {
                            released = true
                            val engine = takeActiveEngine()
                            try {
                                engine?.release()
                            } finally {
                                currentSession = null
                                _state.value = _state.value.copy(
                                    session = null,
                                    isPlaying = false,
                                    isReleased = true
                                )
                            }
                        }
                        command.completion.complete(Unit)
                    }

                    is Command.EngineCallback -> {
                        if (!released && activeEngine != null && command.generation == generation) {
                            when (val event = command.event) {
                                is EngineEvent.PositionChanged -> {
                                    val positionMs = event.positionMs.coerceAtLeast(0L)
                                    currentSession = currentSession?.copy(positionMs = positionMs)
                                    _state.value = _state.value.copy(session = currentSession, positionMs = positionMs)
                                }

                                is EngineEvent.PlayingChanged -> {
                                    currentSession = currentSession?.copy(playWhenReady = event.isPlaying)
                                    _state.value = _state.value.copy(session = currentSession, isPlaying = event.isPlaying)
                                }

                                is EngineEvent.SpeedChanged -> if (event.speed.isFinite() && event.speed > 0f) {
                                    currentSession = currentSession?.copy(speed = event.speed)
                                    _state.value = _state.value.copy(session = currentSession, speed = event.speed)
                                }

                                is EngineEvent.AudioTrackChanged -> {
                                    currentSession = currentSession?.copy(selectedAudioTrackId = event.trackId)
                                    _state.value = _state.value.copy(session = currentSession, selectedAudioTrackId = event.trackId)
                                }

                                is EngineEvent.SubtitleTrackChanged -> {
                                    currentSession = currentSession?.copy(selectedSubtitleTrackId = event.trackId)
                                    _state.value = _state.value.copy(session = currentSession, selectedSubtitleTrackId = event.trackId)
                                }

                                is EngineEvent.Error -> _state.value = _state.value.copy(error = event.cause)
                            }
                        }
                    }
                }
            } catch (error: Throwable) {
                _state.value = _state.value.copy(error = error)
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
