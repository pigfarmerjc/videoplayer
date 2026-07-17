package com.example.videoplayer.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

@UnstableApi
class ExoPlayerEngine(
    context: Context,
    configuration: Configuration = Configuration()
) : PlayerEngine {
    data class Configuration(
        val minBufferMs: Int = 15_000,
        val maxBufferMs: Int = 45_000,
        val bufferForPlaybackMs: Int = 1_000,
        val bufferForPlaybackAfterRebufferMs: Int = 2_000,
        val prepareTimeoutMs: Long = 15_000L,
        val onAudioSessionChanged: (Int) -> Unit = {}
    )

    override val choice: EngineChoice = EngineChoice.EXO

    val player: ExoPlayer

    private val config = configuration
    private val released = AtomicBoolean(false)
    private var listener: PlayerEngine.Listener? = null
    private var generation = 0L
    private var preparing: CompletableDeferred<Unit>? = null
    private var selectedAudioTrackId: String? = null
    private var selectedSubtitleTrackId: String? = null

    init {
        val renderersFactory = DefaultRenderersFactory(context.applicationContext)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                configuration.minBufferMs,
                configuration.maxBufferMs,
                configuration.bufferForPlaybackMs,
                configuration.bufferForPlaybackAfterRebufferMs
            )
            .build()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        player = ExoPlayer.Builder(context.applicationContext)
            .setRenderersFactory(renderersFactory)
            .setAudioAttributes(audioAttributes, true)
            .setSeekParameters(SeekParameters.CLOSEST_SYNC)
            .setHandleAudioBecomingNoisy(true)
            .setLoadControl(loadControl)
            .build()
            .also { it.addListener(createPlayerListener()) }
    }

    override fun setListener(listener: PlayerEngine.Listener?) {
        this.listener = listener
    }

    override suspend fun load(session: PlaybackSession, generation: Long) {
        ensureOpen()
        this.generation = generation
        selectedAudioTrackId = session.selectedAudioTrackId
        selectedSubtitleTrackId = session.selectedSubtitleTrackId
        val ready = CompletableDeferred<Unit>()
        preparing = ready

        withContext(Dispatchers.Main.immediate) {
            player.playWhenReady = false
            player.volume = 0f
            player.stop()
            player.clearMediaItems()
            player.setMediaItem(MediaItem.fromUri(session.mediaUri))
            player.prepare()
        }

        try {
            withTimeout(config.prepareTimeoutMs) { ready.await() }
        } catch (error: EngineFallbackRequiredException) {
            throw error
        } catch (error: TimeoutCancellationException) {
            throw EngineFallbackRequiredException(
                EngineChoice.VLC,
                "ExoPlayer could not inspect the container before playback",
                error
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw EngineFallbackRequiredException(
                EngineChoice.VLC,
                "ExoPlayer failed while preparing media",
                error
            )
        } finally {
            preparing = null
        }
    }

    override suspend fun play() = onMain {
        ensureOpen()
        player.play()
    }

    override suspend fun pause() = onMain {
        ensureOpen()
        player.pause()
    }

    override suspend fun seekTo(positionMs: Long) = onMain {
        ensureOpen()
        player.seekTo(positionMs.coerceAtLeast(0L))
        emit(EngineEvent.PositionChanged(positionMs.coerceAtLeast(0L)))
    }

    override suspend fun setPlaybackSpeed(speed: Float) = onMain {
        ensureOpen()
        player.setPlaybackSpeed(speed)
        emit(EngineEvent.SpeedChanged(speed))
    }

    override suspend fun selectAudioTrack(trackId: String?) = onMain {
        ensureOpen()
        selectedAudioTrackId = trackId
        applyTrackSelection(C.TRACK_TYPE_AUDIO, trackId)
        publishTracks(player.currentTracks)
    }

    override suspend fun selectSubtitleTrack(trackId: String?) = onMain {
        ensureOpen()
        selectedSubtitleTrackId = trackId
        val builder = player.trackSelectionParameters.buildUpon()
        if (trackId == null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            findTrack(C.TRACK_TYPE_TEXT, trackId)?.let { (group, index) ->
                builder.setOverrideForType(TrackSelectionOverride(group, index))
            }
        }
        player.trackSelectionParameters = builder.build()
        publishTracks(player.currentTracks)
    }

    override suspend fun snapshot(): PlaybackSnapshot = withContext(Dispatchers.Main.immediate) {
        PlaybackSnapshot(
            positionMs = player.currentPosition.coerceAtLeast(0L),
            playWhenReady = player.playWhenReady,
            isPlaying = player.isPlaying,
            speed = player.playbackParameters.speed,
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
            player.release()
        }
    }

    private fun createPlayerListener() = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> inspectReadyTracks()
                Player.STATE_ENDED -> emit(EngineEvent.Ended)
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            publishTracks(tracks)
            val incompatible = tracks.highPrecisionPcmDescriptor()
            if (incompatible != null) {
                val cause = EngineFallbackRequiredException(
                    EngineChoice.VLC,
                    "High precision PCM requires VLC: $incompatible"
                )
                player.playWhenReady = false
                player.volume = 0f
                if (preparing?.completeExceptionally(cause) != true) {
                    emit(EngineEvent.FallbackRequired(EngineChoice.VLC, cause))
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            emit(EngineEvent.PlayingChanged(isPlaying))
            emit(EngineEvent.PositionChanged(player.currentPosition.coerceAtLeast(0L)))
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            emit(EngineEvent.PlayWhenReadyChanged(playWhenReady))
        }

        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
            emit(EngineEvent.SpeedChanged(playbackParameters.speed))
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            emit(EngineEvent.PositionChanged(newPosition.positionMs.coerceAtLeast(0L)))
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            emit(EngineEvent.VideoSizeChanged(videoSize.width, videoSize.height))
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            config.onAudioSessionChanged(audioSessionId)
        }

        override fun onPlayerError(error: PlaybackException) {
            val fallback = EngineFallbackRequiredException(
                EngineChoice.VLC,
                "ExoPlayer could not decode this media",
                error
            )
            if (preparing?.completeExceptionally(fallback) != true) {
                emit(EngineEvent.Error(error))
            }
        }
    }

    private fun inspectReadyTracks() {
        val tracks = player.currentTracks
        publishTracks(tracks)
        val incompatible = tracks.highPrecisionPcmDescriptor()
        if (incompatible != null) {
            val fallback = EngineFallbackRequiredException(
                EngineChoice.VLC,
                "High precision PCM requires VLC: $incompatible"
            )
            player.playWhenReady = false
            player.volume = 0f
            preparing?.completeExceptionally(fallback)
            return
        }
        player.volume = 1f
        val duration = player.duration.coerceAtLeast(0L)
        preparing?.complete(Unit)
        emit(EngineEvent.Ready(duration))
        emit(EngineEvent.DurationChanged(duration))
    }

    private fun publishTracks(tracks: Tracks) {
        val audio = tracks.toPlayerTracks(C.TRACK_TYPE_AUDIO, "audio")
        val subtitles = tracks.toPlayerTracks(C.TRACK_TYPE_TEXT, "subtitle")
        selectedAudioTrackId = audio.firstOrNull { it.isSelected }?.id ?: selectedAudioTrackId
        selectedSubtitleTrackId = subtitles.firstOrNull { it.isSelected }?.id
        emit(EngineEvent.AudioTracksChanged(audio))
        emit(EngineEvent.SubtitleTracksChanged(subtitles))
    }

    private fun Tracks.toPlayerTracks(type: Int, prefix: String): List<PlayerTrack> {
        var ordinal = 0
        return buildList {
            groups.filter { it.type == type }.forEach { group ->
                repeat(group.length) { index ->
                    val format = group.getTrackFormat(index)
                    add(
                        PlayerTrack(
                            id = "$prefix:${ordinal++}",
                            label = format.trackLabel(ordinal),
                            mimeType = format.sampleMimeType,
                            language = format.language,
                            isSelected = group.isTrackSelected(index)
                        )
                    )
                }
            }
        }
    }

    private fun applyTrackSelection(type: Int, trackId: String?) {
        if (trackId == null) return
        findTrack(type, trackId)?.let { (group, index) ->
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setOverrideForType(TrackSelectionOverride(group, index))
                .build()
        }
    }

    private fun findTrack(type: Int, trackId: String): Pair<androidx.media3.common.TrackGroup, Int>? {
        val targetOrdinal = trackId.substringAfter(':', "").toIntOrNull() ?: return null
        var ordinal = 0
        player.currentTracks.groups.filter { it.type == type }.forEach { group ->
            repeat(group.length) { index ->
                if (ordinal++ == targetOrdinal) return group.mediaTrackGroup to index
            }
        }
        return null
    }

    private fun Tracks.highPrecisionPcmDescriptor(): AudioFormatDescriptor? {
        groups.filter { it.type == C.TRACK_TYPE_AUDIO }.forEach { group ->
            repeat(group.length) { index ->
                val descriptor = group.getTrackFormat(index).toAudioFormatDescriptor() ?: return@repeat
                if (PcmCompatibilityPolicy.choose(descriptor) == EngineChoice.VLC) return descriptor
            }
        }
        return null
    }

    private fun Format.toAudioFormatDescriptor(): AudioFormatDescriptor? {
        if (sampleMimeType != MimeTypes.AUDIO_RAW) return null
        return when (pcmEncoding) {
            C.ENCODING_PCM_8BIT -> AudioFormatDescriptor.pcmInteger(8)
            C.ENCODING_PCM_16BIT -> AudioFormatDescriptor.pcmInteger(16)
            C.ENCODING_PCM_24BIT -> AudioFormatDescriptor.pcmInteger(24)
            C.ENCODING_PCM_32BIT -> AudioFormatDescriptor.pcmInteger(32)
            C.ENCODING_PCM_FLOAT -> AudioFormatDescriptor.pcmFloat(32)
            else -> null
        }
    }

    private fun Format.trackLabel(ordinal: Int): String = buildString {
        append(label?.takeIf { it.isNotBlank() } ?: language?.uppercase(Locale.ROOT) ?: "Track $ordinal")
        sampleMimeType?.let { append(" · ").append(it.substringAfter('/').uppercase(Locale.ROOT)) }
        if (channelCount > 0) append(" · ").append(channelCount).append("ch")
    }

    private fun emit(event: EngineEvent) {
        listener?.onEvent(generation, event)
    }

    private fun ensureOpen() {
        check(!released.get()) { "ExoPlayerEngine is released" }
    }

    private suspend inline fun onMain(crossinline block: () -> Unit) {
        withContext(Dispatchers.Main.immediate) { block() }
    }
}

@UnstableApi
class AndroidPlayerEngineFactory(
    private val context: Context,
    private val exoConfiguration: ExoPlayerEngine.Configuration = ExoPlayerEngine.Configuration(),
    private val onCreated: (PlayerEngine) -> Unit = {}
) : PlayerEngineFactory {
    override fun create(choice: EngineChoice): PlayerEngine = when (choice) {
        EngineChoice.EXO -> ExoPlayerEngine(context, exoConfiguration)
        EngineChoice.VLC -> VlcPlayerEngine(context)
    }.also(onCreated)
}
