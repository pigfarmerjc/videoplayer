package com.example.videoplayer.playback

data class PlaybackSession(
    val mediaUri: String,
    val positionMs: Long = 0L,
    val playWhenReady: Boolean = false,
    val speed: Float = 1f,
    val selectedAudioTrackId: String? = null,
    val selectedSubtitleTrackId: String? = null
) {
    init {
        require(mediaUri.isNotBlank()) { "Media URI must not be blank" }
        require(positionMs >= 0L) { "Playback position must not be negative" }
        require(speed.isFinite() && speed > 0f) { "Playback speed must be positive and finite" }
    }
}

data class PlaybackSnapshot(
    val positionMs: Long = 0L,
    val playWhenReady: Boolean = false,
    val isPlaying: Boolean = false,
    val speed: Float = 1f,
    val selectedAudioTrackId: String? = null,
    val selectedSubtitleTrackId: String? = null
)

data class PlaybackState(
    val session: PlaybackSession? = null,
    val engineChoice: EngineChoice? = null,
    val generation: Long = 0L,
    val positionMs: Long = 0L,
    val playWhenReady: Boolean = false,
    val isPlaying: Boolean = false,
    val speed: Float = 1f,
    val selectedAudioTrackId: String? = null,
    val selectedSubtitleTrackId: String? = null,
    val hasUsableEngine: Boolean = false,
    val isReleased: Boolean = false,
    val error: Throwable? = null
)
