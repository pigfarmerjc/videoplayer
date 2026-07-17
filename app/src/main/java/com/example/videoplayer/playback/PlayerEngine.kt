package com.example.videoplayer.playback

enum class EngineChoice {
    EXO,
    VLC
}

sealed interface EngineEvent {
    data class PositionChanged(val positionMs: Long) : EngineEvent
    data class PlayingChanged(val isPlaying: Boolean) : EngineEvent
    data class SpeedChanged(val speed: Float) : EngineEvent
    data class AudioTrackChanged(val trackId: String?) : EngineEvent
    data class SubtitleTrackChanged(val trackId: String?) : EngineEvent
    data class Error(val cause: Throwable) : EngineEvent
}

fun interface PlayerEngineFactory {
    fun create(choice: EngineChoice): PlayerEngine
}

interface PlayerEngine {
    val choice: EngineChoice

    fun setListener(listener: Listener?)

    suspend fun load(session: PlaybackSession, generation: Long)

    suspend fun play()

    suspend fun pause()

    suspend fun seekTo(positionMs: Long)

    suspend fun setPlaybackSpeed(speed: Float)

    suspend fun selectAudioTrack(trackId: String?)

    suspend fun selectSubtitleTrack(trackId: String?)

    suspend fun snapshot(): PlaybackSnapshot

    suspend fun release()

    fun interface Listener {
        fun onEvent(generation: Long, event: EngineEvent)
    }
}
