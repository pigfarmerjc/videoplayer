package com.example.videoplayer.playback

enum class EngineChoice {
    EXO,
    VLC
}

data class PlayerTrack(
    val id: String,
    val label: String,
    val mimeType: String? = null,
    val language: String? = null,
    val isSelected: Boolean = false
)

class EngineFallbackRequiredException(
    val fallbackChoice: EngineChoice,
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

sealed interface EngineEvent {
    data class Ready(val durationMs: Long) : EngineEvent
    data class DurationChanged(val durationMs: Long) : EngineEvent
    data class VideoSizeChanged(val width: Int, val height: Int) : EngineEvent
    data class AudioTracksChanged(val tracks: List<PlayerTrack>) : EngineEvent
    data class SubtitleTracksChanged(val tracks: List<PlayerTrack>) : EngineEvent
    data object Ended : EngineEvent
    data class FallbackRequired(
        val choice: EngineChoice,
        val cause: Throwable
    ) : EngineEvent
    data class PositionChanged(val positionMs: Long) : EngineEvent
    data class PlayWhenReadyChanged(val playWhenReady: Boolean) : EngineEvent
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

    /**
     * Releases all engine resources. A successful call must be idempotent; the controller will
     * never call it again after success. If this call throws or is cancelled, resources are
     * considered quarantined and the controller may retry release without issuing other commands.
     */
    suspend fun release()

    fun interface Listener {
        fun onEvent(generation: Long, event: EngineEvent)
    }
}
