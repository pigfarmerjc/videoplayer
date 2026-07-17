package com.example.videoplayer.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.concurrent.atomic.AtomicBoolean

@UnstableApi
class VlcPlayerEngine(
    context: Context,
    private val prepareTimeoutMs: Long = 15_000L
) : PlayerEngine {
    override val choice: EngineChoice = EngineChoice.VLC

    val libVlc = LibVLC(
        context.applicationContext,
        arrayListOf(
            "--audio-time-stretch",
            "--network-caching=3000",
            "--file-caching=1000",
            "--live-caching=1500",
            "--codec=all",
            "--avcodec-hw=any"
        )
    )
    val mediaPlayer = MediaPlayer(libVlc)

    private val released = AtomicBoolean(false)
    private var listener: PlayerEngine.Listener? = null
    private var generation = 0L
    private var preparing: CompletableDeferred<Unit>? = null
    private var attachedLayout: VLCVideoLayout? = null
    private var selectedAudioTrackId: String? = null
    private var selectedSubtitleTrackId: String? = null
    private var audioTrackIds: List<Int> = emptyList()
    private var subtitleTrackIds: List<Int> = emptyList()
    private var requestedVolume = 100

    init {
        mediaPlayer.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    publishTracks()
                    preparing?.complete(Unit)
                    emit(EngineEvent.Ready(mediaPlayer.length.coerceAtLeast(0L)))
                    emit(EngineEvent.PlayingChanged(true))
                }
                MediaPlayer.Event.Paused -> emit(EngineEvent.PlayingChanged(false))
                MediaPlayer.Event.Stopped -> emit(EngineEvent.PlayingChanged(false))
                MediaPlayer.Event.TimeChanged -> emit(
                    EngineEvent.PositionChanged(mediaPlayer.time.coerceAtLeast(0L))
                )
                MediaPlayer.Event.LengthChanged -> emit(
                    EngineEvent.DurationChanged(mediaPlayer.length.coerceAtLeast(0L))
                )
                MediaPlayer.Event.EndReached -> emit(EngineEvent.Ended)
                MediaPlayer.Event.Vout -> publishVideoSize()
                MediaPlayer.Event.EncounteredError -> {
                    val error = IllegalStateException("VLC encountered a playback error")
                    if (preparing?.completeExceptionally(error) != true) emit(EngineEvent.Error(error))
                }
            }
        }
    }

    override fun setListener(listener: PlayerEngine.Listener?) {
        this.listener = listener
    }

    suspend fun attachViews(layout: VLCVideoLayout) = withContext(Dispatchers.Main.immediate) {
        ensureOpen()
        if (attachedLayout === layout) return@withContext
        attachedLayout?.let { runCatching { mediaPlayer.detachViews() } }
        mediaPlayer.attachViews(layout, null, true, false)
        attachedLayout = layout
    }

    suspend fun detachViews(layout: VLCVideoLayout? = null) = withContext(Dispatchers.Main.immediate) {
        if (layout == null || attachedLayout === layout) {
            runCatching { mediaPlayer.detachViews() }
            attachedLayout = null
        }
    }

    suspend fun setVolume(volume: Int) = withContext(Dispatchers.Main.immediate) {
        requestedVolume = volume.coerceIn(0, 200)
        mediaPlayer.volume = requestedVolume
    }

    override suspend fun load(session: PlaybackSession, generation: Long) {
        ensureOpen()
        this.generation = generation
        selectedAudioTrackId = session.selectedAudioTrackId
        selectedSubtitleTrackId = session.selectedSubtitleTrackId
        val ready = CompletableDeferred<Unit>()
        preparing = ready
        withContext(Dispatchers.Main.immediate) {
            mediaPlayer.volume = 0
            mediaPlayer.stop()
            val media = Media(libVlc, Uri.parse(session.mediaUri)).apply {
                setHWDecoderEnabled(true, false)
                if (session.positionMs > 0L) addOption(":start-time=${session.positionMs / 1000f}")
            }
            mediaPlayer.media = media
            media.release()
            mediaPlayer.play()
        }

        try {
            withTimeout(prepareTimeoutMs) { ready.await() }
        } catch (error: TimeoutCancellationException) {
            throw IllegalStateException("VLC timed out while preparing media", error)
        } catch (error: CancellationException) {
            throw error
        } finally {
            preparing = null
        }
        withContext(Dispatchers.Main.immediate) {
            mediaPlayer.pause()
            mediaPlayer.volume = requestedVolume
            publishTracks()
        }
    }

    override suspend fun play() = onMain {
        ensureOpen()
        mediaPlayer.play()
        emit(EngineEvent.PlayWhenReadyChanged(true))
    }

    override suspend fun pause() = onMain {
        ensureOpen()
        mediaPlayer.pause()
        emit(EngineEvent.PlayWhenReadyChanged(false))
    }

    override suspend fun seekTo(positionMs: Long) = onMain {
        ensureOpen()
        mediaPlayer.time = positionMs.coerceAtLeast(0L)
        emit(EngineEvent.PositionChanged(positionMs.coerceAtLeast(0L)))
    }

    override suspend fun setPlaybackSpeed(speed: Float) = onMain {
        ensureOpen()
        mediaPlayer.setRate(speed)
        emit(EngineEvent.SpeedChanged(speed))
    }

    override suspend fun selectAudioTrack(trackId: String?) = onMain {
        ensureOpen()
        val ordinal = trackId?.substringAfter(':', "")?.toIntOrNull()
        if (ordinal != null) {
            val nativeId = audioTrackIds.getOrNull(ordinal)
                ?: throw IllegalArgumentException("Unknown VLC audio track: $trackId")
            check(mediaPlayer.setAudioTrack(nativeId)) { "VLC rejected audio track: $trackId" }
            selectedAudioTrackId = trackId
        }
        publishTracks()
    }

    override suspend fun selectSubtitleTrack(trackId: String?) = onMain {
        ensureOpen()
        if (trackId == null) {
            mediaPlayer.setSpuTrack(-1)
            selectedSubtitleTrackId = null
        } else {
            val ordinal = trackId.substringAfter(':', "").toIntOrNull()
                ?: throw IllegalArgumentException("Unknown VLC subtitle track: $trackId")
            val nativeId = subtitleTrackIds.getOrNull(ordinal)
                ?: throw IllegalArgumentException("Unknown VLC subtitle track: $trackId")
            check(mediaPlayer.setSpuTrack(nativeId)) { "VLC rejected subtitle track: $trackId" }
            selectedSubtitleTrackId = trackId
        }
        publishTracks()
    }

    override suspend fun snapshot(): PlaybackSnapshot = withContext(Dispatchers.Main.immediate) {
        PlaybackSnapshot(
            positionMs = mediaPlayer.time.coerceAtLeast(0L),
            playWhenReady = mediaPlayer.isPlaying,
            isPlaying = mediaPlayer.isPlaying,
            speed = mediaPlayer.rate.takeIf { it.isFinite() && it > 0f } ?: 1f,
            selectedAudioTrackId = selectedAudioTrackId,
            selectedSubtitleTrackId = selectedSubtitleTrackId
        )
    }

    override suspend fun release() {
        if (!released.compareAndSet(false, true)) return
        withContext(Dispatchers.Main.immediate) {
            preparing?.cancel()
            preparing = null
            listener = null
            runCatching { mediaPlayer.setEventListener(null) }
            runCatching { mediaPlayer.detachViews() }
            attachedLayout = null
            runCatching { mediaPlayer.stop() }
            mediaPlayer.release()
            libVlc.release()
        }
    }

    private fun publishTracks() {
        val audioDescriptions = mediaPlayer.audioTracks.orEmpty().filter { it.id >= 0 }
        audioTrackIds = audioDescriptions.map { it.id }
        val activeAudioId = mediaPlayer.audioTrack
        val audio = audioDescriptions.mapIndexed { index, description ->
            PlayerTrack(
                id = "audio:$index",
                label = description.name?.takeIf { it.isNotBlank() } ?: "Audio ${index + 1}",
                mimeType = description.name?.let(::inferMimeType),
                isSelected = description.id == activeAudioId
            )
        }
        selectedAudioTrackId = audio.firstOrNull { it.isSelected }?.id ?: selectedAudioTrackId

        val subtitleDescriptions = mediaPlayer.spuTracks.orEmpty().filter { it.id >= 0 }
        subtitleTrackIds = subtitleDescriptions.map { it.id }
        val activeSubtitleId = mediaPlayer.spuTrack
        val subtitles = subtitleDescriptions.mapIndexed { index, description ->
            PlayerTrack(
                id = "subtitle:$index",
                label = description.name?.takeIf { it.isNotBlank() } ?: "Subtitle ${index + 1}",
                isSelected = description.id == activeSubtitleId
            )
        }
        selectedSubtitleTrackId = subtitles.firstOrNull { it.isSelected }?.id
        emit(EngineEvent.AudioTracksChanged(audio))
        emit(EngineEvent.SubtitleTracksChanged(subtitles))
    }

    private fun inferMimeType(name: String): String? {
        val normalized = name.lowercase()
        return when {
            "pcm" in normalized -> "audio/pcm"
            "aac" in normalized || "mp4a" in normalized -> "audio/aac"
            "opus" in normalized -> "audio/opus"
            else -> null
        }
    }

    private fun publishVideoSize() {
        val track = runCatching { mediaPlayer.currentVideoTrack }.getOrNull() ?: return
        if (track.width > 0 && track.height > 0) {
            emit(EngineEvent.VideoSizeChanged(track.width, track.height))
        }
    }

    private fun emit(event: EngineEvent) {
        listener?.onEvent(generation, event)
    }

    private fun ensureOpen() {
        check(!released.get()) { "VlcPlayerEngine is released" }
    }

    private suspend inline fun onMain(crossinline block: () -> Unit) {
        withContext(Dispatchers.Main.immediate) { block() }
    }
}
