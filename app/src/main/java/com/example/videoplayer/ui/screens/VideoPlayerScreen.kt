package com.example.videoplayer.ui.screens

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.provider.MediaStore
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.activity.compose.BackHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import kotlin.math.abs
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.compose.runtime.mutableIntStateOf
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import android.os.Handler
import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.videoplayer.data.model.MediaItem as PlayerMediaItem
import com.example.videoplayer.MainActivity
import com.example.videoplayer.R
import com.example.videoplayer.data.repository.MediaRepository
import com.example.videoplayer.FloatingPlayerManager
import com.example.videoplayer.service.FloatingPlayerService
import com.example.videoplayer.ui.components.PlayerGestureOverlay
import com.example.videoplayer.ui.components.VideoThumbnailCache
import com.example.videoplayer.ui.components.formatDuration
import com.example.videoplayer.ui.components.formatFileSize
import com.example.videoplayer.ui.theme.*
import com.example.videoplayer.util.SimpleGifEncoder
import com.example.videoplayer.util.DlnaCastManager
import com.example.videoplayer.util.DlnaDevice
import com.example.videoplayer.util.AudioEffectManager
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media as VlcMedia
import org.videolan.libvlc.MediaPlayer as VlcMediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

// Playback tuning constants.
private const val SWITCH_READY_TIMEOUT_MS = 1200L

private data class SeekProfile(
    val dragDecodeIntervalMs: Long,
    val minPositionDeltaMs: Long,
    val allowExactFinalSeek: Boolean
)

private data class BufferProfile(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    itemId: Long,
    folderName: String,
    mediaItems: List<PlayerMediaItem>,
    repository: MediaRepository,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) {
        var ctx = context
        while (ctx is android.content.ContextWrapper) {
            if (ctx is Activity) break
            ctx = ctx.baseContext
        }
        ctx as? Activity
    }
    val playlist = remember(folderName, mediaItems) {
        repository.resolveFolderItems(folderName, mediaItems)
            .filter { it.type == com.example.videoplayer.data.model.MediaType.VIDEO }
    }
    var currentIndex by remember { mutableIntStateOf(playlist.indexOfFirst { it.id == itemId }.coerceAtLeast(0)) }
    val currentVideo = playlist.getOrNull(currentIndex)

    if (currentVideo == null) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text("未找到该视频", color = Color.White)
        }
        return
    }

    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    val seekQueue = remember(player) { player?.let { SeekQueue(it) } }
    val latestSeekQueue by rememberUpdatedState(seekQueue)
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var showControls by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var virtualVolumePercent by remember { mutableIntStateOf(100) }
    var currentEqPreset by remember { mutableStateOf("Normal") }
    var showInfo by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var skipSeconds by remember { mutableIntStateOf(repository.getSkipSeconds()) }
    var autoReplay by remember { mutableStateOf(repository.isAutoReplayEnabled()) }
    var autoPlayNext by remember { mutableStateOf(repository.isAutoPlayNextEnabled()) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var isFavorite by remember { mutableStateOf(false) }
    var isBackgroundPlayEnabled by remember { mutableStateOf(repository.isBackgroundPlayEnabled()) }
    var isPipEnabled by remember { mutableStateOf(repository.isPipEnabled()) }
    var videoWidth by remember { mutableIntStateOf(0) }
    var videoHeight by remember { mutableIntStateOf(0) }
    var sleepTimerMinutes by remember { mutableIntStateOf(0) }
    var sleepTimerRemainingSeconds by remember { mutableIntStateOf(0) }
    var lastScrubSeekAtMs by remember { mutableLongStateOf(0L) }
    var isScrubbing by remember { mutableStateOf(false) }
    var resumeAfterScrub by remember { mutableStateOf(false) }
    var useVlcFallback by remember { mutableStateOf(false) }
    var isScreenLocked by remember { mutableStateOf(false) }
    var isWaitingForFirstFrame by remember { mutableStateOf(false) }
    var isExitingScreen by remember { mutableStateOf(false) }
    // 下拉关闭的垂直位移（px），用于视差动画
    var pullDownOffsetPx by remember { mutableFloatStateOf(0f) }

    val scope = rememberCoroutineScope()
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var operationMessage by remember { mutableStateOf<String?>(null) }

    // VLC controls
    var vlcTogglePlayPause by remember { mutableStateOf<() -> Unit>({}) }
    var vlcSeekTo by remember { mutableStateOf<(Long) -> Unit>({}) }
    var vlcSetSpeed by remember { mutableStateOf<(Float) -> Unit>({}) }
    var vlcPause by remember { mutableStateOf<() -> Unit>({}) }
    var vlcSnapshot by remember { mutableStateOf<(String) -> Boolean>({ false }) }

    // Dialogs/Panels
    var showCastDialog by remember { mutableStateOf(false) }
    var showGifPanel by remember { mutableStateOf(false) }
    var showTutorial by remember { mutableStateOf(!repository.isPlayerTutorialShown()) }
    var isExportingGif by remember { mutableStateOf(false) }
    var gifExportProgress by remember { mutableFloatStateOf(0f) }

    // Casting state
    var isCasting by remember { mutableStateOf(false) }
    var isCastPlaying by remember { mutableStateOf(false) }
    var castDeviceName by remember { mutableStateOf("") }
    var castVolume by remember { mutableIntStateOf(50) }
    var castSpeed by remember { mutableFloatStateOf(1f) }
    var castPosition by remember { mutableLongStateOf(0L) }
    var castDuration by remember { mutableLongStateOf(0L) }
    var isCastOrientationLandscape by remember { mutableStateOf(true) }

    // Extra references for side effects
    var exoPlayerView by remember { mutableStateOf<PlayerView?>(null) }
    val initialBrightness = remember { activity?.window?.attributes?.screenBrightness ?: -1f }
    val latestCurrentVideo by rememberUpdatedState(currentVideo)
    val latestPlaybackSpeed by rememberUpdatedState(playbackSpeed)
    val latestAutoReplay by rememberUpdatedState(autoReplay)
    val latestAutoPlayNext by rememberUpdatedState(autoPlayNext)

    // Sync states from DlnaCastManager
    LaunchedEffect(DlnaCastManager.isCasting, DlnaCastManager.isPlaying, DlnaCastManager.position, DlnaCastManager.duration, DlnaCastManager.volume) {
        if (DlnaCastManager.isCasting) {
            isCasting = true
            isCastPlaying = DlnaCastManager.isPlaying
            castPosition = DlnaCastManager.position
            castDuration = DlnaCastManager.duration
            castVolume = DlnaCastManager.volume
        } else {
            isCasting = false
        }
    }

    LaunchedEffect(currentVideo, currentIndex, playlist, useVlcFallback) {
        FloatingPlayerManager.playlist = playlist
        FloatingPlayerManager.currentIndex = currentIndex
        FloatingPlayerManager.useVlcFallback = useVlcFallback
    }

    LaunchedEffect(currentPosition) {
        FloatingPlayerManager.currentPosition = currentPosition
    }

    val exitAlpha by animateFloatAsState(
        targetValue = if (isExitingScreen) 0f else 1f,
        animationSpec = tween(280),
        label = "exitAlpha"
    )
    val exitScale by animateFloatAsState(
        targetValue = if (isExitingScreen) 0.92f else 1f,
        animationSpec = tween(280),
        label = "exitScale"
    )

    // VLC track states
    var vlcAudioTracks by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    var vlcActiveAudioTrack by remember { mutableIntStateOf(-1) }
    var vlcSetAudioTrack by remember { mutableStateOf<(Int) -> Unit>({}) }

    var vlcSubtitleTracks by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    var vlcActiveSubtitleTrack by remember { mutableIntStateOf(-1) }
    var vlcSetSubtitleTrack by remember { mutableStateOf<(Int) -> Unit>({}) }

    // Swipe preview state.
    val swipeOffsetAnim = remember { androidx.compose.animation.core.Animatable(0f) }
    var swipeTargetIndex by remember { mutableStateOf<Int?>(null) }
    var isSwipeAnimating by remember { mutableStateOf(false) }
    var isWaitingForReady by remember { mutableStateOf(true) }
    var readyCoverVideo by remember { mutableStateOf<PlayerMediaItem?>(currentVideo) }
    var screenWidthPx by remember { mutableFloatStateOf(1080f) }

    // Notify switchToIndex when the new player is ready.
    val playerReadyFlow = remember { MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 64) }

    fun markIfWatched() {
        repository.savePlaybackProgress(currentVideo, currentPosition, duration)
        repository.markLastViewed(folderName, currentVideo)
        if (duration > 0 && currentPosition >= duration * 0.9f) repository.markWatched(currentVideo)
    }

    fun pauseCompat() {
        if (useVlcFallback) vlcPause() else player?.pause()
    }

    fun seekToPosition(positionMs: Long, exact: Boolean = false, force: Boolean = false) {
        if (useVlcFallback) {
            vlcSeekTo(positionMs)
        } else {
            seekQueue?.seekTo(positionMs, exact, force)
        }
    }

    fun handleScrubSeek(positionMs: Long, finished: Boolean) {
        val now = System.currentTimeMillis()
        val profile = seekProfileFor(currentVideo, duration)
        
        currentPosition = positionMs
        
        if (finished) {
            isScrubbing = false
            seekToPosition(positionMs, exact = profile.allowExactFinalSeek, force = true)
            if (resumeAfterScrub) {
                if (useVlcFallback) {
                    vlcTogglePlayPause()
                    resumeAfterScrub = false
                } else {
                    val hasVideo = player?.currentTracks?.isTypeSelected(androidx.media3.common.C.TRACK_TYPE_VIDEO) == true
                    if (hasVideo) {
                        isWaitingForFirstFrame = true
                        scope.launch {
                            delay(800)
                            if (isWaitingForFirstFrame) {
                                isWaitingForFirstFrame = false
                                resumeAfterScrub = false
                                player?.play()
                            }
                        }
                    } else {
                        resumeAfterScrub = false
                        player?.play()
                    }
                }
            }
        } else {
            if (!isScrubbing) {
                isScrubbing = true
                resumeAfterScrub = isPlaying
                pauseCompat()
            }
            // Throttle scrubbing seeks based on profile for better performance
            if (now - lastScrubSeekAtMs >= profile.dragDecodeIntervalMs) {
                lastScrubSeekAtMs = now
                seekToPosition(positionMs, exact = false)
            }
        }
    }

    val sleepTimerText = remember(sleepTimerRemainingSeconds) {
        if (sleepTimerRemainingSeconds > 0) {
            val m = sleepTimerRemainingSeconds / 60
            val s = sleepTimerRemainingSeconds % 60
            "%02d:%02d".format(java.util.Locale.US, m, s)
        } else {
            null
        }
    }

    LaunchedEffect(sleepTimerRemainingSeconds, isPlaying) {
        if (sleepTimerRemainingSeconds > 0 && isPlaying) {
            delay(1000L)
            sleepTimerRemainingSeconds--
            if (sleepTimerRemainingSeconds == 0) {
                pauseCompat()
                sleepTimerMinutes = 0
            }
        }
    }

    LaunchedEffect(isPlaying) {
        MainActivity.isVideoPlaying = isPlaying
        (activity as? MainActivity)?.updatePipParams(currentPipAspectRatio(videoWidth, videoHeight, currentVideo))
    }

    LaunchedEffect(videoWidth, videoHeight, currentVideo, currentIndex, playlist.size, autoPlayNext) {
        val act = activity as? MainActivity
        if (act != null) {
            MainActivity.pipCanPrev = currentIndex > 0 || autoPlayNext
            MainActivity.pipCanNext = currentIndex < playlist.lastIndex || autoPlayNext
            act.updatePipParams(currentPipAspectRatio(videoWidth, videoHeight, currentVideo))
        }
    }

    fun handleSwipeDrag(dragX: Float) {
        if (isSwipeAnimating) return
        var targetIndex = if (dragX > 0) currentIndex - 1 else currentIndex + 1
        if (autoPlayNext && playlist.isNotEmpty()) {
            if (targetIndex < 0) {
                targetIndex = playlist.lastIndex
            } else if (targetIndex > playlist.lastIndex) {
                targetIndex = 0
            }
        }
        if (targetIndex in playlist.indices) {
            swipeTargetIndex = targetIndex
            scope.launch {
                swipeOffsetAnim.snapTo(dragX)
            }
        } else {
            // Apply rubber-banding (drag resistance)
            swipeTargetIndex = null
            scope.launch {
                swipeOffsetAnim.snapTo(dragX * 0.25f)
            }
        }
    }

    fun handleSwipeRelease(totalDragX: Float) {
        val targetIndex = swipeTargetIndex
        if (isSwipeAnimating) return
        isSwipeAnimating = true

        scope.launch {
            val dragThreshold = screenWidthPx * 0.15f
            if (targetIndex != null && abs(totalDragX) > dragThreshold && targetIndex in playlist.indices) {
                val direction = if (totalDragX < 0) 1 else -1
                val targetOffset = -direction * screenWidthPx
                
                // Animate to full slide out
                swipeOffsetAnim.animateTo(
                    targetValue = targetOffset,
                    animationSpec = spring(dampingRatio = 0.85f, stiffness = 400f)
                )

                markIfWatched()
                pauseCompat()

                val targetVideo = playlist[targetIndex]
                readyCoverVideo = targetVideo
                isWaitingForReady = true
                currentIndex = targetIndex

                // Reset positions
                swipeOffsetAnim.snapTo(0f)
                swipeTargetIndex = null

                // Clear any stale ready signals
                playerReadyFlow.resetReplayCache()

                withTimeoutOrNull(SWITCH_READY_TIMEOUT_MS) {
                    playerReadyFlow.first()
                }
                delay(100L)
                isWaitingForReady = false
                delay(200L)
                readyCoverVideo = null
                isSwipeAnimating = false
            } else {
                // Cancel switch: spring back to 0
                swipeOffsetAnim.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f)
                )
                swipeTargetIndex = null
                isSwipeAnimating = false
            }
        }
    }

    fun switchToIndex(targetIndex: Int, direction: Int) {
        if (isSwipeAnimating || targetIndex !in playlist.indices) return
        if (targetIndex == currentIndex) {
            seekToPosition(0L, force = true)
            if (useVlcFallback) vlcTogglePlayPause() else player?.play()
            return
        }
        markIfWatched()
        pauseCompat()

        val targetVideo = playlist[targetIndex]
        
        // Skip slide animation while in PiP or background playback.
        val skipAnimation = MainActivity.isInPipMode.value || isBackgroundPlayEnabled
        if (skipAnimation) {
            currentIndex = targetIndex
            readyCoverVideo = null
            isWaitingForReady = false
            return
        }

        swipeTargetIndex = targetIndex
        isSwipeAnimating = true

        scope.launch {
            // Animate sliding out of the screen
            val targetOffset = -direction * screenWidthPx
            swipeOffsetAnim.animateTo(
                targetValue = targetOffset,
                animationSpec = tween(durationMillis = 350)
            )

            // Switch video index
            readyCoverVideo = targetVideo
            isWaitingForReady = true
            currentIndex = targetIndex

            // Snap back to 0 immediately for the new video
            swipeOffsetAnim.snapTo(0f)
            swipeTargetIndex = null

            // Clear any stale ready signals
            playerReadyFlow.resetReplayCache()

            // Wait for new video to be ready (timeout 1200ms)
            withTimeoutOrNull(SWITCH_READY_TIMEOUT_MS) {
                playerReadyFlow.first()
            }

            // Fade out the cover overlay
            delay(100L)
            isWaitingForReady = false
            delay(200L)
            readyCoverVideo = null
            isSwipeAnimating = false
        }
    }

    val playNext = rememberUpdatedState {
        if (currentIndex < playlist.lastIndex) {
            switchToIndex(currentIndex + 1, 1)
        } else if (autoPlayNext && playlist.isNotEmpty()) {
            switchToIndex(0, 1)
        }
    }

    val playPrev = rememberUpdatedState {
        if (currentIndex > 0) {
            switchToIndex(currentIndex - 1, -1)
        } else if (autoPlayNext && playlist.isNotEmpty()) {
            switchToIndex(playlist.lastIndex, -1)
        }
    }

    fun togglePlayPause() {
        if (useVlcFallback) {
            vlcTogglePlayPause()
        } else {
            player?.let { if (it.isPlaying) it.pause() else it.play() }
        }
    }

    fun setPlaybackSpeedCompat(speed: Float) {
        if (useVlcFallback) {
            vlcSetSpeed(speed)
        } else {
            player?.setPlaybackSpeed(speed)
        }
    }

    val mainActivity = activity as? MainActivity

    DisposableEffect(mainActivity) {
        if (mainActivity != null) {
            mainActivity.onPipPlayPause = { togglePlayPause() }
            mainActivity.onPipPrev = { playPrev.value() }
            mainActivity.onPipNext = { playNext.value() }
        }
        onDispose {
            mainActivity?.onPipPlayPause = null
            mainActivity?.onPipPrev = null
            mainActivity?.onPipNext = null
            MainActivity.pipCanPrev = false
            MainActivity.pipCanNext = false
        }
    }

    fun showMessage(text: String) {
        operationMessage = text
        scope.launch {
            delay(2200)
            if (operationMessage == text) operationMessage = null
        }
    }

    fun captureScreenshot() {
        val video = currentVideo
        val position = currentPosition
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (useVlcFallback) {
                        val temp = File(context.cacheDir, "snapshot_${System.currentTimeMillis()}.png")
                        if (vlcSnapshot(temp.absolutePath) && temp.exists() && temp.length() > 0L) {
                            saveFileToGallery(
                                context = context,
                                source = temp,
                                displayName = galleryName("screenshot", "png"),
                                mimeType = "image/png"
                            ).also { temp.delete() }
                        } else {
                            if (latestCurrentVideo != video) error("播放视频已切换")
                            val bitmap = extractVideoFrame(context, video, position)
                                ?: error("无法获取当前帧")
                            try {
                                saveBitmapToGallery(context, bitmap, galleryName("screenshot", "jpg"))
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    } else {
                        if (latestCurrentVideo != video) error("播放视频已切换")
                        val bitmap = captureTextureBitmap(exoPlayerView, videoWidth, videoHeight)
                            ?: extractVideoFrame(context, video, position)
                            ?: error("无法获取当前帧")
                        try {
                            saveBitmapToGallery(context, bitmap, galleryName("screenshot", "jpg"))
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
            showMessage(result.fold(
                onSuccess = { "已保存截图：$it" },
                onFailure = { "截图失败：${it.message ?: "未知错误"}" }
            ))
        }
    }

    fun selectCustomCover() {
        val video = currentVideo
        val position = currentPosition
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bitmap = if (useVlcFallback) {
                        extractVideoFrame(context, video, position)
                    } else {
                        captureTextureBitmap(exoPlayerView, videoWidth, videoHeight)
                            ?: extractVideoFrame(context, video, position)
                    } ?: error("无法获取当前帧")
                    
                    try {
                        val targetWidth = 360
                        val targetHeight = 220
                        val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
                        val finalBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.RGB_565).also { dst ->
                            android.graphics.Canvas(dst).drawBitmap(scaled, 0f, 0f, null)
                            if (scaled !== bitmap) {
                                scaled.recycle()
                            }
                        }
                        val saved = try {
                            VideoThumbnailCache.setCustomCover(context, video.storageKey, finalBitmap)
                        } finally {
                            finalBitmap.recycle()
                        }
                        if (!saved) error("封面写入失败")
                        "封面设置成功"
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
            showMessage(result.fold(
                onSuccess = { it },
                onFailure = { "设置封面失败：${it.message ?: "未知错误"}" }
            ))
        }
    }

    fun exportGif(startMs: Long, lengthSeconds: Int, cropX: Float, cropY: Float, cropW: Float, cropH: Float) {
        if (isExportingGif) return
        val video = currentVideo
        isExportingGif = true
        gifExportProgress = 0f
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    exportGifToGallery(
                        context = context,
                        item = video,
                        startMs = startMs.coerceAtLeast(0L),
                        durationMs = (lengthSeconds.coerceIn(1, 10) * 1000L),
                        widthPx = 480,
                        fps = 10,
                        cropX = cropX,
                        cropY = cropY,
                        cropW = cropW,
                        cropH = cropH
                    ) { progress ->
                        scope.launch {
                            gifExportProgress = progress
                        }
                    }
                }
            }
            isExportingGif = false
            showGifPanel = false
            showMessage(result.fold(
                onSuccess = { "GIF 已保存：$it" },
                onFailure = { "GIF 导出失败：${it.message ?: "未知错误"}" }
            ))
        }
    }

    BackHandler(enabled = true) {
        if (isScreenLocked) {
            showControls = true
        } else {
            isExitingScreen = true
        }
    }

    LaunchedEffect(isExitingScreen) {
        if (isExitingScreen && pullDownOffsetPx == 0f) {
            pauseCompat()
            markIfWatched()
            delay(280)
            onBackClick()
        }
    }

    LaunchedEffect(currentVideo) {
        val savedPosition = repository.getPlaybackPosition(currentVideo)
        val savedDuration = repository.getPlaybackDuration(currentVideo).coerceAtLeast(0L)
        val startPos = restorePlaybackPosition(savedPosition, savedDuration)
        currentPosition = startPos
        duration = savedDuration
        isFavorite = repository.isFavorite(currentVideo)
        playbackError = null
        useVlcFallback = repository.getPreferredVlcDecoder(currentVideo) ?: shouldPreferVlcEngine(currentVideo)
        repository.markLastViewed(folderName, currentVideo)
        
        // Reset VLC tracks
        vlcAudioTracks = emptyList()
        vlcActiveAudioTrack = -1
        vlcSubtitleTracks = emptyList()
        vlcActiveSubtitleTrack = -1
    }

    // 3. Handle fading out the cover overlay for initial load or player fallback toggle
    LaunchedEffect(useVlcFallback) {
        playerReadyFlow.resetReplayCache()
        
        isWaitingForReady = true
        readyCoverVideo = currentVideo
        
        withTimeoutOrNull(SWITCH_READY_TIMEOUT_MS) {
            playerReadyFlow.first()
        }
        delay(100L)
        isWaitingForReady = false
        delay(200L)
        readyCoverVideo = null
    }

    // Preload adjacent thumbnails when safe for memory.
    LaunchedEffect(currentIndex, playlist, currentVideo.size, currentVideo.resolution) {
        if (canPreloadAdjacentThumbnails(currentVideo)) {
            listOf(currentIndex - 1, currentIndex + 1)
                .mapNotNull { playlist.getOrNull(it) }
                .filter { canPreloadAdjacentThumbnails(it) }
                .forEach { VideoThumbnailCache.load(context, it) }
        }
    }

    // Keep UI scrubbing responsive; throttle real seeks for 4K and large files.
    // 1. Create and manage the ExoPlayer instance
    LaunchedEffect(useVlcFallback) {
        if (useVlcFallback) {
            val playerToRelease = player
            if (playerToRelease != null) {
                player = null
                runCatching { playerToRelease.release() }
            }
        } else {
            playbackError = null
            tracks = Tracks.EMPTY
            repository.markLastViewed(folderName, currentVideo)

            try {
                val renderersFactory = object : DefaultRenderersFactory(context) {
                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun buildAudioRenderers(
                        ctx: Context,
                        extensionRendererMode: Int,
                        mediaCodecSelector: MediaCodecSelector,
                        enableDecoderFallback: Boolean,
                        audioSink: AudioSink,
                        eventHandler: Handler,
                        eventListener: AudioRendererEventListener,
                        out: ArrayList<androidx.media3.exoplayer.Renderer>
                    ) {
                        val floatSink = DefaultAudioSink.Builder(context)
                            .setEnableFloatOutput(true)
                            .setEnableAudioTrackPlaybackParams(true)
                            .build()
                        super.buildAudioRenderers(
                            ctx, extensionRendererMode, mediaCodecSelector,
                            enableDecoderFallback, floatSink, eventHandler, eventListener, out
                        )
                    }
                }.setEnableDecoderFallback(false)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

                val audioAttrs = AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build()

                val bufferProfile = bufferProfileFor(currentVideo)
                val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        bufferProfile.minBufferMs,
                        bufferProfile.maxBufferMs,
                        bufferProfile.bufferForPlaybackMs,
                        bufferProfile.bufferForPlaybackAfterRebufferMs
                    )
                    .build()

                val exoPlayer = ExoPlayer.Builder(context)
                    .setRenderersFactory(renderersFactory)
                    .setAudioAttributes(audioAttrs, /* handleAudioFocus= */ true)
                    .setSeekParameters(SeekParameters.CLOSEST_SYNC)
                    .setHandleAudioBecomingNoisy(true)
                    .setLoadControl(loadControl)
                    .build()

                player = exoPlayer

                // Initialize audio effects immediately with active session ID
                val initialSessionId = exoPlayer.audioSessionId
                if (initialSessionId != androidx.media3.common.C.AUDIO_SESSION_ID_UNSET) {
                    AudioEffectManager.onSessionChanged(initialSessionId)
                    if (virtualVolumePercent > 100) {
                        AudioEffectManager.setVolumeBoost(initialSessionId, virtualVolumePercent)
                    }
                    if (currentEqPreset != "Normal") {
                        AudioEffectManager.applyPreset(initialSessionId, currentEqPreset)
                    }
                }

                val listener = object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        isPlaying = playing
                    }

                    override fun onTracksChanged(currentTracks: Tracks) {
                        tracks = currentTracks
                        // Auto switch to VLC for PCM formats ExoPlayer cannot handle.
                        if (!useVlcFallback) {
                            var hasUnsupportedPcm = false
                            for (i in 0 until currentTracks.groups.size) {
                                val group = currentTracks.groups[i]
                                if (group.type == androidx.media3.common.C.TRACK_TYPE_AUDIO) {
                                    for (j in 0 until group.length) {
                                        val format = group.getTrackFormat(j)
                                        val mimeType = format.sampleMimeType
                                        if (mimeType == androidx.media3.common.MimeTypes.AUDIO_RAW) {
                                            val encoding = format.pcmEncoding
                                            // ExoPlayer supports 8/16-bit PCM; use VLC for 24/32-bit or float PCM.
                                            if (encoding != androidx.media3.common.C.ENCODING_PCM_16BIT &&
                                                encoding != androidx.media3.common.C.ENCODING_PCM_8BIT
                                            ) {
                                                hasUnsupportedPcm = true
                                                break
                                            }
                                        }
                                    }
                                }
                                if (hasUnsupportedPcm) break
                            }
                            if (hasUnsupportedPcm) {
                                repository.setPreferredVlcDecoder(latestCurrentVideo, true)
                                android.widget.Toast.makeText(
                                    context,
                                    "检测到高精度 PCM 音轨，已自动切换至 VLC 解码以支持声音输出",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                                useVlcFallback = true
                            }
                        }
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            duration = exoPlayer.duration.coerceAtLeast(0L)
                            playerReadyFlow.tryEmit(Unit)
                        }
                        if (state == Player.STATE_ENDED) {
                            repository.markWatched(latestCurrentVideo)
                            when {
                                latestAutoReplay -> {
                                    exoPlayer.seekTo(0L)
                                    exoPlayer.play()
                                }
                                latestAutoPlayNext -> playNext.value()
                            }
                        }
                    }

                    override fun onRenderedFirstFrame() {
                        playerReadyFlow.tryEmit(Unit)
                        if (isWaitingForFirstFrame && latestSeekQueue?.isSeeking == false) {
                            isWaitingForFirstFrame = false
                            resumeAfterScrub = false
                            player?.play()
                        }
                    }

                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        if (videoSize.width > 0 && videoSize.height > 0) {
                            videoWidth = videoSize.width
                            videoHeight = videoSize.height
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        playbackError = null
                        repository.setPreferredVlcDecoder(latestCurrentVideo, true)
                        useVlcFallback = true
                    }

                    override fun onAudioSessionIdChanged(audioSessionId: Int) {
                        AudioEffectManager.onSessionChanged(audioSessionId)
                    }

                    override fun onPositionDiscontinuity(
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int
                    ) {
                        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                            latestSeekQueue?.onSeekProcessed()
                        }
                    }
                }
                exoPlayer.addListener(listener)

                while (true) {
                    val playing = exoPlayer.isPlaying
                    val isCurrentMedia = exoPlayer.currentMediaItem?.localConfiguration?.uri == latestCurrentVideo.uri
                    var isSeeking = latestSeekQueue?.isSeeking == true
                    if (isSeeking && System.currentTimeMillis() - (latestSeekQueue?.lastSeekTimeMs ?: 0L) > 1000L) {
                        latestSeekQueue?.reset()
                        isSeeking = false
                    }
                    if (playing && isCurrentMedia && !isWaitingForReady && !isScrubbing && !isSeeking) {
                        currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
                        val dur = exoPlayer.duration.coerceAtLeast(0L)
                        if (dur > 0L) duration = dur
                        val video = latestCurrentVideo
                        if (shouldMarkWatched(currentPosition, dur)) {
                            repository.markWatched(video)
                        }
                        delay(200)
                    } else {
                        delay(1000)
                    }
                }
            } finally {
                player?.let { p ->
                    player = null
                    runCatching { p.release() }
                }
                AudioEffectManager.releaseAll()
            }
        }
    }

    // 2. Handle active media item switching on the player
    LaunchedEffect(currentVideo, player) {
        val p = player ?: return@LaunchedEffect
        p.setMediaItem(MediaItem.fromUri(currentVideo.uri))
        
        val savedPosition = repository.getPlaybackPosition(currentVideo)
        val savedDuration = repository.getPlaybackDuration(currentVideo).coerceAtLeast(0L)
        val startPos = restorePlaybackPosition(savedPosition, savedDuration)
        
        p.seekTo(startPos)
        currentPosition = startPos
        
        p.prepare()
        p.setPlaybackSpeed(latestPlaybackSpeed)
        p.playWhenReady = true
    }

    // Periodically save playback progress for both ExoPlayer and VLC.
    LaunchedEffect(currentVideo, isPlaying) {
        if (isPlaying) {
            while (true) {
                delay(2000)
                repository.savePlaybackProgress(currentVideo, currentPosition, duration)
            }
        }
    }

    // Run fullscreen window updates without relying on snapshotFlow.
    LaunchedEffect(showControls, isPlaying) {
        if (showControls && isPlaying) {
            delay(3500)
            showControls = false
        }
    }

    DisposableEffect(Unit) {
        activity?.window?.let { win ->
            // Hide system bars for immersive fullscreen playback.
            val controller = androidx.core.view.WindowCompat.getInsetsController(win, win.decorView)
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            MainActivity.isVideoPlaying = false
            markIfWatched()
            AudioEffectManager.releaseAll()
            activity?.let { act ->
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                // Restore screen brightness when leaving the player.
                val lp = act.window.attributes
                lp.screenBrightness = initialBrightness
                act.window.attributes = lp
                // Restore system bars when leaving.
                act.window?.let { win ->
                    val controller = androidx.core.view.WindowCompat.getInsetsController(win, win.decorView)
                    controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                }
            }
            val playerToRelease = player
            if (playerToRelease != null) {
                player = null
                runCatching { playerToRelease.release() }
            }
        }
    }

    // Pause when the app goes to background unless background playback is enabled.
    val latestBackgroundPlay by rememberUpdatedState(isBackgroundPlayEnabled)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                val isPip = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    activity?.isInPictureInPictureMode ?: false
                } else {
                    false
                }
                if (FloatingPlayerManager.isFloating) {
                    pauseCompat()
                    val p = player
                    player = null
                    runCatching { p?.release() }
                } else if (!latestBackgroundPlay && !isPip && !MainActivity.isInPipMode.value) {
                    pauseCompat()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 下拉时的动态缩放（最多缩到 0.88）和位移
    val pullProgress = if (pullDownOffsetPx > 0f) (pullDownOffsetPx / 800f).coerceIn(0f, 1f) else 0f
    val pullScale = 1f - pullProgress * 0.12f
    // 下拉释放退出时，让透明度平滑过渡到 0f 消失
    val pullAlpha = if (isExitingScreen) {
        (1f - (pullDownOffsetPx / 1200f)).coerceIn(0f, 1f)
    } else {
        1f - pullProgress * 0.4f
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onGloballyPositioned { coordinates ->
                screenWidthPx = coordinates.size.width.toFloat()
            }
            .graphicsLayer(
                scaleX = if (isExitingScreen && pullDownOffsetPx == 0f) exitScale else pullScale,
                scaleY = if (isExitingScreen && pullDownOffsetPx == 0f) exitScale else pullScale,
                alpha = if (isExitingScreen && pullDownOffsetPx == 0f) exitAlpha else pullAlpha,
                translationY = if (isExitingScreen && pullDownOffsetPx == 0f) 0f else pullDownOffsetPx
            )
    ) {
        PlayerGestureOverlay(
            seekSeconds = skipSeconds,
            durationMs = duration,
            currentPositionMs = currentPosition,
            isLocked = isScreenLocked,
            isEnabled = !MainActivity.isInPipMode.value,
            onSingleTap = { showControls = !showControls },
            onCenterDoubleTap = { togglePlayPause() },
            onVolumePercentChange = { percent ->
                virtualVolumePercent = percent
                if (!useVlcFallback) {
                    val p = player
                    if (p != null) {
                        val id = p.audioSessionId
                        if (id != androidx.media3.common.C.AUDIO_SESSION_ID_UNSET) {
                            AudioEffectManager.setVolumeBoost(id, percent)
                        }
                    }
                }
            },
            onDoubleTapSeek = { delta ->
                val target = (currentPosition + delta).coerceIn(0L, duration.coerceAtLeast(0L))
                val wasPlaying = isPlaying
                currentPosition = target
                if (wasPlaying && !useVlcFallback) {
                    val hasVideo = player?.currentTracks?.isTypeSelected(androidx.media3.common.C.TRACK_TYPE_VIDEO) == true
                    if (hasVideo) {
                        pauseCompat()
                        isWaitingForFirstFrame = true
                        seekToPosition(target, force = true)
                        scope.launch {
                            delay(800)
                            if (isWaitingForFirstFrame) {
                                isWaitingForFirstFrame = false
                                player?.play()
                            }
                        }
                    } else {
                        seekToPosition(target, force = true)
                    }
                } else {
                    seekToPosition(target, force = true)
                }
            },
            onScrub = { targetPos ->
                handleScrubSeek(targetPos, finished = false)
            },
            onScrubFinished = { targetPos ->
                handleScrubSeek(targetPos, finished = true)
            },
            onLongPressSpeed = { active -> setPlaybackSpeedCompat(if (active) 2f else playbackSpeed) },
            onSwipeDrag = { dragX -> handleSwipeDrag(dragX) },
            onSwipeRelease = { totalDragX -> handleSwipeRelease(totalDragX) },
            onPullDownDrag = { dy ->
                pullDownOffsetPx = dy
            },
            onPullDownRelease = { dy ->
                scope.launch {
                    if (dy > 200f) {
                        // 拖拽距离足够 → 触发关闭，停止播放并保存进度
                        isExitingScreen = true
                        pauseCompat()
                        markIfWatched()
                        
                        // 渐渐向下滑出屏幕，采用 ease-in 算法让过渡丝滑
                        val start = pullDownOffsetPx
                        val target = 2000f
                        val steps = 18
                        for (i in 1..steps) {
                            val t = i.toFloat() / steps
                            pullDownOffsetPx = start + (target - start) * (t * t)
                            kotlinx.coroutines.delay(10)
                        }
                        onBackClick()
                    } else {
                        // 回弹
                        val start = pullDownOffsetPx
                        val steps = 12
                        for (i in steps downTo 0) {
                            pullDownOffsetPx = start * i / steps
                            kotlinx.coroutines.delay(12)
                        }
                        pullDownOffsetPx = 0f
                    }
                }
            }
        ) {
            // Player surface.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (isExitingScreen && pullDownOffsetPx == 0f) exitAlpha else 1f)
                    .graphicsLayer { translationX = swipeOffsetAnim.value }
            ) {
                if (useVlcFallback) {
                    VlcPlayerSurface(
                        item = currentVideo,
                        startPosition = currentPosition,
                        playbackSpeed = playbackSpeed,
                        volume = virtualVolumePercent,
                        onReady = { playerReadyFlow.tryEmit(Unit) },
                        onBindControls = { toggle, seek, speed, pause, _, _, setAudio, _, _, setSub, snapshot ->
                            vlcTogglePlayPause = toggle
                            vlcSeekTo = seek
                            vlcSetSpeed = speed
                            vlcPause = pause
                            vlcSetAudioTrack = setAudio
                            vlcSetSubtitleTrack = setSub
                            vlcSnapshot = snapshot
                        },
                        onTracksChanged = { audio, activeAudio, subtitles, activeSubtitle ->
                            vlcAudioTracks = audio
                            vlcActiveAudioTrack = activeAudio
                            vlcSubtitleTracks = subtitles
                            vlcActiveSubtitleTrack = activeSubtitle
                        },
                        onState = { position, length, playing ->
                            if (!isWaitingForReady && !isScrubbing) {
                                currentPosition = position
                                if (length > 0L) duration = length
                            }
                            isPlaying = playing
                        },
                        onEnded = {
                            repository.markWatched(currentVideo)
                            when {
                                autoReplay -> {
                                    seekToPosition(0L, force = true)
                                    vlcTogglePlayPause()
                                }
                                autoPlayNext -> playNext.value()
                            }
                        },
                        onError = {
                            playbackError = "VLC fallback failed: $it"
                        }
                    )
                } else {
                    AndroidView(
                        factory = { ctx ->
                            val root = android.view.LayoutInflater.from(ctx).inflate(R.layout.custom_video_player_view, null)
                            val view = root.findViewById<PlayerView>(R.id.player_view)
                            // Remove view from parent FrameLayout so it can be added to Compose view hierarchy safely
                            (view.parent as? android.view.ViewGroup)?.removeView(view)
                            view.apply {
                                exoPlayerView = this
                                keepScreenOn = true
                                setShutterBackgroundColor(android.graphics.Color.BLACK)
                                setKeepContentOnPlayerReset(false)
                                this.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            }
                        },
                        update = {
                            exoPlayerView = it
                            it.player = player
                            it.resizeMode = resizeMode
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Keep SurfaceView alive and avoid expensive recreation.
            if (isExitingScreen) {
                val placeholderBitmap = remember(currentVideo) { VideoThumbnailCache.get(currentVideo.storageKey) }
                if (placeholderBitmap != null) {
                    Image(
                        bitmap = placeholderBitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(Color.Black))
                }
            }

            playbackError?.let { error -> ErrorPanel(error, Modifier.align(Alignment.Center)) }

            // Gesture overlay.
            val previewTargetIndex = swipeTargetIndex
            if (previewTargetIndex != null && previewTargetIndex in playlist.indices && swipeOffsetAnim.value != 0f) {
                val targetVideo = playlist[previewTargetIndex]
                val translationX = if (swipeOffsetAnim.value > 0) {
                    -screenWidthPx + swipeOffsetAnim.value
                } else {
                    screenWidthPx + swipeOffsetAnim.value
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { this.translationX = translationX }
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    val cached = remember(targetVideo.storageKey) { VideoThumbnailCache.get(targetVideo.storageKey) }
                    val thumbFile = remember(targetVideo.storageKey) { VideoThumbnailCache.diskFile(context, targetVideo.storageKey) }
                    
                    if (cached != null) {
                        Image(
                            bitmap = cached.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(thumbFile)
                                .crossfade(true)
                                .build(),
                            contentDescription = targetVideo.displayName,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.15f))
                    )
                    Text(
                        text = targetVideo.displayName,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(start = 24.dp, end = 24.dp, bottom = 72.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black.copy(alpha = 0.58f))
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }

            // Next video preview overlay.
            val coverVideo = readyCoverVideo
            if (coverVideo != null) {
                val readyAlpha by animateFloatAsState(
                    targetValue = if (isWaitingForReady) 1f else 0f,
                    animationSpec = if (isWaitingForReady) snap() else tween(durationMillis = 200),
                    label = "readyAlpha"
                )
                if (readyAlpha > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(readyAlpha)
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        val cached = remember(coverVideo.storageKey) { VideoThumbnailCache.get(coverVideo.storageKey) }
                        val thumbFile = remember(coverVideo.storageKey) { VideoThumbnailCache.diskFile(context, coverVideo.storageKey) }
                        
                        if (cached != null) {
                            Image(
                                bitmap = cached.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        } else {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(thumbFile)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = coverVideo.displayName,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = showControls && !isScreenLocked && !MainActivity.isInPipMode.value,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                TopControls(
                    title = currentVideo.displayName,
                    indexText = "${currentIndex + 1} / ${playlist.size}",
                    isFavorite = isFavorite,
                    useVlcFallback = useVlcFallback,
                    sleepTimerText = sleepTimerText,
                    onBackClick = {
                        isExitingScreen = true
                    },
                    onFavoriteClick = {
                        isFavorite = !isFavorite
                        repository.setFavorite(currentVideo, isFavorite)
                    }
                )
            }

            AnimatedVisibility(
                visible = showControls && !isScreenLocked && !MainActivity.isInPipMode.value,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                BottomControls(
                    currentPosition = currentPosition,
                    duration = duration,
                    isPlaying = isPlaying,
                    canPrev = currentIndex > 0 || autoPlayNext,
                    canNext = currentIndex < playlist.lastIndex || autoPlayNext,
                    playbackSpeed = playbackSpeed,
                    onSeek = { targetPos ->
                        handleScrubSeek(targetPos, finished = false)
                    },
                    onSeekFinished = { targetPos ->
                        handleScrubSeek(targetPos, finished = true)
                    },
                    onSpeedChange = {
                        playbackSpeed = it
                        setPlaybackSpeedCompat(it)
                    },
                    onFrameStep = { frames ->
                        pauseCompat()
                        val target = (currentPosition + frames * 33L).coerceIn(0L, duration.coerceAtLeast(0L))
                        currentPosition = target
                        seekToPosition(target, force = true)
                    },
                    onPrev = { playPrev.value() },
                    onPlayPause = { togglePlayPause() },
                    onNext = { playNext.value() },
                    onRotate = {
                        activity?.let { act ->
                            val currentOrientation = act.requestedOrientation
                            if (currentOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE ||
                                currentOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            ) {
                                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            } else {
                                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            }
                        }
                    },
                    onCast = {
                        pauseCompat()
                        showCastDialog = true
                    },
                    onPip = {
                        activity?.let { act ->
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M &&
                                android.provider.Settings.canDrawOverlays(act)
                            ) {
                                FloatingPlayerManager.playlist = playlist
                                FloatingPlayerManager.currentIndex = currentIndex
                                FloatingPlayerManager.currentPosition = player?.currentPosition ?: currentPosition
                                FloatingPlayerManager.useVlcFallback = useVlcFallback
                                FloatingPlayerManager.isFloating = true

                                val p = player
                                player = null
                                runCatching { p?.release() }

                                val serviceIntent = Intent(act, FloatingPlayerService::class.java)
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                    act.startForegroundService(serviceIntent)
                                } else {
                                    act.startService(serviceIntent)
                                }
                                onBackClick()
                                act.moveTaskToBack(true)
                            } else {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                    val intent = Intent(
                                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        android.net.Uri.parse("package:${act.packageName}")
                                    )
                                    act.startActivity(intent)
                                } else {
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                        val params = (act as? MainActivity)?.pipParamsBuilder?.build()
                                            ?: android.app.PictureInPictureParams.Builder().build()
                                        act.enterPictureInPictureMode(params)
                                    }
                                }
                            }
                        }
                    },
                    onScreenshot = { captureScreenshot() },
                    onGif = {
                        pauseCompat()
                        showGifPanel = true
                    },
                    onSetCover = { selectCustomCover() },
                    onInfo = { showInfo = true },
                    onSettings = { showSettings = true }
                )
            }

            // Floating screen lock button.
            AnimatedVisibility(
                visible = showControls && !MainActivity.isInPipMode.value,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 24.dp)
            ) {
                IconButton(
                    onClick = {
                        isScreenLocked = !isScreenLocked
                        if (isScreenLocked) {
                            showControls = false
                        }
                    },
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.65f))
                        .border(
                            width = 1.dp,
                            brush = Brush.linearGradient(
                                colors = if (isScreenLocked) {
                                    listOf(AccentPink, AccentPink.copy(alpha = 0.3f))
                                } else {
                                    listOf(SecondaryNeonCyan, Color.White.copy(alpha = 0.15f))
                                }
                            ),
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = if (isScreenLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = "锁定屏幕",
                        tint = if (isScreenLocked) AccentPink else Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            AnimatedVisibility(
                visible = operationMessage != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 28.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.Black.copy(alpha = 0.82f))
                        .border(1.dp, SecondaryNeonCyan.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = operationMessage.orEmpty(),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // 播放器手势教程覆盖层
        com.example.videoplayer.ui.components.PlayerTutorialOverlay(
            visible = showTutorial,
            onDismiss = {
                showTutorial = false
                repository.setPlayerTutorialShown(true)
            }
        )
    }


    if (showSettings) {
        val audioOptions = remember(player, tracks, useVlcFallback, vlcAudioTracks, vlcActiveAudioTrack) {
            if (useVlcFallback) {
                vlcAudioTracks.map { (id, name) ->
                    TrackOption(
                        id = "vlc_audio_$id",
                        label = name.ifBlank { "闂婂疇寤?$id" },
                        isSelected = vlcActiveAudioTrack == id,
                        onSelect = {
                            vlcSetAudioTrack(id)
                            vlcActiveAudioTrack = id
                        }
                    )
                }
            } else {
                val options = mutableListOf<TrackOption>()
                player?.let { p ->
                    val currentTracks = p.currentTracks
                    currentTracks.groups.forEach { group ->
                        if (group.type == C.TRACK_TYPE_AUDIO) {
                            for (i in 0 until group.length) {
                                val format = group.getTrackFormat(i)
                                val label = "${format.language?.uppercase() ?: "未知"} - ${format.sampleMimeType?.substringAfter("/")?.uppercase() ?: "AAC"} (${format.channelCount} 声道)"
                                val isSelected = group.isTrackSelected(i)
                                options.add(TrackOption(
                                    id = "exo_audio_${group.mediaTrackGroup.hashCode()}_$i",
                                    label = label,
                                    isSelected = isSelected,
                                    onSelect = {
                                        val newParams = p.trackSelectionParameters
                                            .buildUpon()
                                            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, i))
                                            .build()
                                        p.trackSelectionParameters = newParams
                                    }
                                ))
                            }
                        }
                    }
                }
                options
            }
        }

        val subtitleOptions = remember(player, tracks, useVlcFallback, vlcSubtitleTracks, vlcActiveSubtitleTrack) {
            if (useVlcFallback) {
                val options = mutableListOf<TrackOption>()
                options.add(TrackOption(
                    id = "vlc_sub_disable",
                    label = "关闭字幕",
                    isSelected = vlcActiveSubtitleTrack == -1,
                    onSelect = {
                        vlcSetSubtitleTrack(-1)
                        vlcActiveSubtitleTrack = -1
                    }
                ))
                vlcSubtitleTracks.map { (id, name) ->
                    TrackOption(
                        id = "vlc_sub_$id",
                        label = name.ifBlank { "字幕 $id" },
                        isSelected = vlcActiveSubtitleTrack == id,
                        onSelect = {
                            vlcSetSubtitleTrack(id)
                            vlcActiveSubtitleTrack = id
                        }
                    )
                }.let { options.addAll(it) }
                options
            } else {
                val options = mutableListOf<TrackOption>()
                player?.let { p ->
                    val currentTracks = p.currentTracks
                    val isDisabled = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
                    
                    options.add(TrackOption(
                        id = "exo_sub_disable",
                        label = "关闭字幕",
                        isSelected = isDisabled,
                        onSelect = {
                            val newParams = p.trackSelectionParameters
                                .buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                .build()
                            p.trackSelectionParameters = newParams
                        }
                    ))

                    currentTracks.groups.forEach { group ->
                        if (group.type == C.TRACK_TYPE_TEXT) {
                            for (i in 0 until group.length) {
                                val format = group.getTrackFormat(i)
                                val label = "${format.language?.uppercase() ?: "未知"} - ${format.sampleMimeType?.substringAfter("/")?.uppercase() ?: "SRT"}"
                                val isSelected = !isDisabled && group.isTrackSelected(i)
                                options.add(TrackOption(
                                    id = "exo_sub_${group.mediaTrackGroup.hashCode()}_$i",
                                    label = label,
                                    isSelected = isSelected,
                                    onSelect = {
                                        val newParams = p.trackSelectionParameters
                                            .buildUpon()
                                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, i))
                                            .build()
                                        p.trackSelectionParameters = newParams
                                    }
                                ))
                            }
                        }
                    }
                }
                options
            }
        }

        PlayerSettingsDialog(
            skipSeconds = skipSeconds,
            onSkipSecondsChange = {
                skipSeconds = it
                repository.setSkipSeconds(it)
            },
            autoReplay = autoReplay,
            onAutoReplayChange = {
                autoReplay = it
                repository.setAutoReplayEnabled(it)
            },
            autoPlayNext = autoPlayNext,
            onAutoPlayNextChange = {
                autoPlayNext = it
                repository.setAutoPlayNextEnabled(it)
            },
            playbackSpeed = playbackSpeed,
            onPlaybackSpeedChange = {
                playbackSpeed = it
                setPlaybackSpeedCompat(it)
            },
            resizeMode = resizeMode,
            onResizeModeChange = { resizeMode = it },
            onFrameStep = { frames ->
                pauseCompat()
                val target = (currentPosition + frames * 33L).coerceIn(0L, duration.coerceAtLeast(0L))
                currentPosition = target
                seekToPosition(target, force = true)
            },
            useVlcFallback = useVlcFallback,
            onToggleDecoder = {
                playbackError = null
                val nextUseVlc = !useVlcFallback
                repository.setPreferredVlcDecoder(currentVideo, nextUseVlc)
                useVlcFallback = nextUseVlc
            },
            audioOptions = audioOptions,
            subtitleOptions = subtitleOptions,
            currentPreset = currentEqPreset,
            onPresetChange = { preset ->
                currentEqPreset = preset
                if (!useVlcFallback) {
                    val p = player
                    if (p != null) {
                        val id = p.audioSessionId
                        if (id != androidx.media3.common.C.AUDIO_SESSION_ID_UNSET) {
                            AudioEffectManager.applyPreset(id, preset)
                        }
                    }
                }
            },
            isBackgroundPlayEnabled = isBackgroundPlayEnabled,
            onBackgroundPlayChange = {
                isBackgroundPlayEnabled = it
                repository.setBackgroundPlayEnabled(it)
            },
            isPipEnabled = isPipEnabled,
            onPipChange = {
                isPipEnabled = it
                repository.setPipEnabled(it)
            },
            sleepTimerMinutes = sleepTimerMinutes,
            onSleepTimerChange = { minutes ->
                sleepTimerMinutes = minutes
                sleepTimerRemainingSeconds = minutes * 60
            },
            onShowTutorial = {
                showSettings = false
                showTutorial = true
            },
            onDismiss = { showSettings = false }
        )
    }

    if (showGifPanel) {
        InteractiveGifEditor(
            defaultStartMs = currentPosition,
            durationMs = duration,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            isExporting = isExportingGif,
            progress = gifExportProgress,
            onDismiss = { if (!isExportingGif) showGifPanel = false },
            onExport = { startMs, seconds, cX, cY, cW, cH ->
                exportGif(startMs, seconds, cX, cY, cW, cH)
            }
        )
    }

    if (showCastDialog) {
        CastDeviceSelectionDialog(
            onDismiss = { showCastDialog = false },
            onDeviceSelected = { device ->
                DlnaCastManager.startCast(context, device, currentVideo, currentPosition)
                castDeviceName = device.friendlyName
                isCasting = true
                showCastDialog = false
                castDuration = duration
                castPosition = currentPosition
                castSpeed = 1f
                isCastPlaying = true
                pauseCompat()
                
                showMessage("已连接到 ${device.friendlyName}，正在局域网投屏...")
            }
        )
    }

    if (isCasting) {
        CastRemoteControlOverlay(
            videoTitle = currentVideo.displayName,
            deviceName = castDeviceName,
            isPlaying = isCastPlaying,
            volume = castVolume,
            speed = castSpeed,
            position = castPosition,
            duration = castDuration,
            isLandscape = isCastOrientationLandscape,
            canPrev = currentIndex > 0 || autoPlayNext,
            canNext = currentIndex < playlist.lastIndex || autoPlayNext,
            onPlayPauseToggle = {
                if (DlnaCastManager.isPlaying) DlnaCastManager.pause() else DlnaCastManager.play()
                showMessage(if (!isCastPlaying) "遥控：开始播放" else "遥控：已暂停")
            },
            onSeek = { pos ->
                DlnaCastManager.seek(pos)
            },
            onVolumeChange = { vol ->
                DlnaCastManager.setCastVolume(vol)
            },
            onSpeedChange = { spd ->
                castSpeed = spd
                showMessage("遥控：播放倍速 ${spd}x (DLNA设备固定为1.0x)")
            },
            onRotateToggle = {
                isCastOrientationLandscape = !isCastOrientationLandscape
                showMessage(if (isCastOrientationLandscape) "遥控：电视屏幕已切换为横屏" else "遥控：电视屏幕已切换为竖屏")
            },
            onPrev = {
                val prevIndex = if (currentIndex > 0) currentIndex - 1 else if (autoPlayNext) playlist.lastIndex else -1
                if (prevIndex != -1 && prevIndex in playlist.indices) {
                    currentIndex = prevIndex
                    val newVideo = playlist[prevIndex]
                    val device = DlnaCastManager.selectedDevice
                    DlnaCastManager.stopCast()
                    if (device != null) {
                        DlnaCastManager.startCast(context, device, newVideo, 0L)
                    }
                    castPosition = 0L
                    castDuration = newVideo.duration
                    showMessage("遥控：已切换到上一个视频并投屏")
                }
            },
            onNext = {
                val nextIndex = if (currentIndex < playlist.lastIndex) currentIndex + 1 else if (autoPlayNext) 0 else -1
                if (nextIndex != -1 && nextIndex in playlist.indices) {
                    currentIndex = nextIndex
                    val newVideo = playlist[nextIndex]
                    val device = DlnaCastManager.selectedDevice
                    DlnaCastManager.stopCast()
                    if (device != null) {
                        DlnaCastManager.startCast(context, device, newVideo, 0L)
                    }
                    castPosition = 0L
                    castDuration = newVideo.duration
                    showMessage("遥控：已切换到下一个视频并投屏")
                }
            },
            onDisconnect = {
                DlnaCastManager.stopCast()
                isCasting = false
                // Resume local player from the remote cast position!
                seekToPosition(castPosition, force = true)
                if (isCastPlaying) {
                    if (useVlcFallback) vlcTogglePlayPause() else player?.play()
                }
                showMessage("投屏已断开，本地继续播放")
            }
        )
    }

    if (showInfo) {
        VideoInfoDialog(
            item = currentVideo,
            duration = duration,
            tracks = tracks,
            onDismiss = { showInfo = false }
        )
    }
}

private fun galleryName(prefix: String, extension: String): String {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    return "BlackCat_${prefix}_$stamp.$extension"
}

private fun restorePlaybackPosition(savedPosition: Long, savedDuration: Long): Long {
    if (savedPosition <= 0L) return 0L
    if (savedDuration <= 0L) return savedPosition
    val remainingMs = savedDuration - savedPosition
    val resetThresholdMs = if (savedDuration <= 30_000L) 500L else 3_000L
    return if (remainingMs in 0..resetThresholdMs) 0L else savedPosition.coerceAtMost(savedDuration)
}

private fun shouldMarkWatched(positionMs: Long, durationMs: Long): Boolean {
    if (durationMs <= 0L || positionMs <= 0L) return false
    val remainingMs = durationMs - positionMs
    val thresholdMs = if (durationMs <= 30_000L) 500L else maxOf(3_000L, (durationMs * 0.1f).toLong())
    return remainingMs <= thresholdMs
}

private fun shouldPreferVlcEngine(item: PlayerMediaItem): Boolean {
    val name = item.displayName.lowercase(Locale.ROOT)
    val path = item.path.lowercase(Locale.ROOT)
    val source = if (path.isNotBlank()) path else name
    val ext = source.substringAfterLast('.', missingDelimiterValue = "")
    return ext in setOf(
        "avi", "wmv", "asf", "vob", "ts", "m2ts", "mts", "mpg", "mpeg", "flv", "iso"
    )
}

private fun shouldStopVlcBeforeSwitch(item: PlayerMediaItem): Boolean {
    val name = item.displayName.lowercase(Locale.ROOT)
    val path = item.path.lowercase(Locale.ROOT)
    val source = if (path.isNotBlank()) path else name
    val ext = source.substringAfterLast('.', missingDelimiterValue = "")
    return item.size > 8_000_000_000L || ext in setOf("iso", "vob", "m2ts", "mts")
}

private fun currentPipAspectRatio(videoWidth: Int, videoHeight: Int, item: PlayerMediaItem): android.util.Rational {
    var w = videoWidth
    var h = videoHeight
    if (w <= 0 || h <= 0) {
        val resolution = item.resolution
        if (resolution.contains("x")) {
            val parts = resolution.split("x")
            w = parts.getOrNull(0)?.toIntOrNull() ?: 0
            h = parts.getOrNull(1)?.toIntOrNull() ?: 0
        }
    }
    return if (w > 0 && h > 0) {
        android.util.Rational(w, h)
    } else {
        android.util.Rational(16, 9)
    }
}

private fun seekProfileFor(item: PlayerMediaItem, durationMs: Long): SeekProfile {
    val is4k = isLikely4k(item)
    val isLargeFile = item.size > 1_500_000_000L
    val isHugeFile = item.size > 8_000_000_000L
    val effectiveDuration = durationMs.coerceAtLeast(item.duration).coerceAtLeast(1L)

    return when {
        isHugeFile || (is4k && item.size > 4_000_000_000L) -> SeekProfile(
            dragDecodeIntervalMs = 280L,
            minPositionDeltaMs = maxOf(1_800L, effectiveDuration / 320L),
            allowExactFinalSeek = false
        )
        isLargeFile || is4k -> SeekProfile(
            dragDecodeIntervalMs = 170L,
            minPositionDeltaMs = maxOf(900L, effectiveDuration / 560L),
            allowExactFinalSeek = false
        )
        else -> SeekProfile(
            dragDecodeIntervalMs = 80L,
            minPositionDeltaMs = 0L,
            allowExactFinalSeek = true
        )
    }
}

private fun bufferProfileFor(item: PlayerMediaItem): BufferProfile {
    val is4k = isLikely4k(item)
    val isHugeFile = item.size > 8_000_000_000L
    val isLargeFile = item.size > 1_500_000_000L
    return when {
        isHugeFile || (is4k && item.size > 4_000_000_000L) -> BufferProfile(
            minBufferMs = 50_000,
            maxBufferMs = 120_000,
            bufferForPlaybackMs = 2_500,
            bufferForPlaybackAfterRebufferMs = 5_000
        )
        isLargeFile || is4k -> BufferProfile(
            minBufferMs = 30_000,
            maxBufferMs = 80_000,
            bufferForPlaybackMs = 1_500,
            bufferForPlaybackAfterRebufferMs = 3_000
        )
        else -> BufferProfile(
            minBufferMs = 15_000,
            maxBufferMs = 45_000,
            bufferForPlaybackMs = 1_000,
            bufferForPlaybackAfterRebufferMs = 2_000
        )
    }
}

private fun canPreloadAdjacentThumbnails(item: PlayerMediaItem): Boolean {
    return item.size <= 1_500_000_000L && !isLikely4k(item)
}

private fun isLikely4k(item: PlayerMediaItem): Boolean {
    return item.resolution
        .lowercase(Locale.ROOT)
        .split("x")
        .mapNotNull { it.trim().toIntOrNull() }
        .let { parts -> parts.size >= 2 && (parts[0] >= 3500 || parts[1] >= 2000) }
}

private fun captureTextureBitmap(playerView: PlayerView?, width: Int, height: Int): Bitmap? {
    val texture = playerView?.let { findTextureView(it) } ?: return null
    val targetWidth = width.takeIf { it > 0 } ?: texture.width
    val targetHeight = height.takeIf { it > 0 } ?: texture.height
    if (targetWidth <= 0 || targetHeight <= 0) return null
    return runCatching { texture.getBitmap(targetWidth, targetHeight) }.getOrNull()
}

@Composable
private fun ExactVideoSeekBar(
    positionMs: Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
    onSeek: (Long) -> Unit,
    onSeekFinished: (Long) -> Unit
) {
    var widthPx by remember { mutableIntStateOf(0) }
    var dragTargetMs by remember { mutableLongStateOf(positionMs) }
    val shownPosition = dragTargetMs.takeIf { it >= 0L } ?: positionMs

    LaunchedEffect(positionMs) {
        dragTargetMs = positionMs
    }

    fun targetFromX(x: Float): Long {
        if (durationMs <= 0L || widthPx <= 0) return 0L
        val fraction = (x / widthPx.toFloat()).coerceIn(0f, 1f)
        return (durationMs * fraction).toLong().coerceIn(0L, durationMs)
    }

    fun updateTarget(x: Float, finished: Boolean) {
        val target = targetFromX(x)
        dragTargetMs = target
        onSeek(target)
        if (finished) onSeekFinished(target)
    }

    Canvas(
        modifier = modifier
            .height(28.dp)
            .onGloballyPositioned { widthPx = it.size.width }
            .pointerInput(durationMs, widthPx) {
                detectTapGestures { offset ->
                    updateTarget(offset.x, finished = true)
                }
            }
            .pointerInput(durationMs, widthPx) {
                detectDragGestures(
                    onDragStart = { offset -> updateTarget(offset.x, finished = false) },
                    onDrag = { change, _ ->
                        change.consume()
                        updateTarget(change.position.x, finished = false)
                    },
                    onDragEnd = { onSeekFinished(dragTargetMs.coerceIn(0L, durationMs.coerceAtLeast(0L))) },
                    onDragCancel = { dragTargetMs = positionMs }
                )
            }
    ) {
        val trackHeight = 3.dp.toPx()
        val trackTop = (size.height - trackHeight) / 2f
        val progress = if (durationMs > 0L) shownPosition.toFloat() / durationMs.toFloat() else 0f
        val progressWidth = size.width * progress.coerceIn(0f, 1f)
        drawRoundRect(
            color = Color.White.copy(alpha = 0.2f),
            topLeft = Offset(0f, trackTop),
            size = Size(size.width, trackHeight),
            cornerRadius = CornerRadius(trackHeight, trackHeight)
        )
        drawRoundRect(
            color = SecondaryNeonCyan,
            topLeft = Offset(0f, trackTop),
            size = Size(progressWidth, trackHeight),
            cornerRadius = CornerRadius(trackHeight, trackHeight)
        )
        drawCircle(
            color = Color.White,
            radius = 7.dp.toPx(),
            center = Offset(progressWidth.coerceIn(0f, size.width), size.height / 2f)
        )
    }
}

private fun findTextureView(view: View): TextureView? {
    if (view is TextureView) return view
    if (view is ViewGroup) {
        for (i in 0 until view.childCount) {
            findTextureView(view.getChildAt(i))?.let { return it }
        }
    }
    return null
}

private fun extractVideoFrame(context: Context, item: PlayerMediaItem, positionMs: Long): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return runCatching {
        setRetrieverDataSource(context, retriever, item)
        retriever.getFrameAtTime(positionMs.coerceAtLeast(0L) * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
    }.also {
        runCatching { retriever.release() }
    }.getOrNull()
}

private fun saveBitmapToGallery(context: Context, bitmap: Bitmap, displayName: String): String {
    return openGalleryOutput(context, displayName, "image/jpeg").use { target ->
        target.output.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 96, out)
        }
        target.finish()
        target.pathForMessage
    }
}

private fun saveFileToGallery(context: Context, source: File, displayName: String, mimeType: String): String {
    return openGalleryOutput(context, displayName, mimeType).use { target ->
        target.output.use { out ->
            source.inputStream().use { input -> input.copyTo(out) }
        }
        target.finish()
        target.pathForMessage
    }
}

private suspend fun exportGifToGallery(
    context: Context,
    item: PlayerMediaItem,
    startMs: Long,
    durationMs: Long,
    widthPx: Int,
    fps: Int,
    cropX: Float,
    cropY: Float,
    cropW: Float,
    cropH: Float,
    onProgress: (Float) -> Unit
): String {
    val frameDelayMs = 1000 / fps.coerceAtLeast(1)
    val frameCount = (durationMs / frameDelayMs).toInt().coerceIn(1, 100)
    val retriever = MediaMetadataRetriever()
    var first: Bitmap? = null
    return try {
        setRetrieverDataSource(context, retriever, item)
        
        val rawFirst = retriever.getFrameAtTime(startMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
            ?: error("无法提取 GIF 首帧")

        val rawW = rawFirst.width
        val rawH = rawFirst.height
        
        val cX = (cropX * rawW).toInt().coerceIn(0, rawW - 1)
        val cY = (cropY * rawH).toInt().coerceIn(0, rawH - 1)
        val cW = (cropW * rawW).toInt().coerceIn(1, rawW - cX)
        val cH = (cropH * rawH).toInt().coerceIn(1, rawH - cY)

        val croppedFirst = Bitmap.createBitmap(rawFirst, cX, cY, cW, cH)
        if (croppedFirst !== rawFirst) {
            rawFirst.recycle()
        }

        first = scaleBitmapToWidth(croppedFirst, widthPx)
        if (first !== croppedFirst) {
            croppedFirst.recycle()
        }

        val targetWidth = first.width
        val targetHeight = first.height

        val target = openGalleryOutput(context, galleryName("clip", "gif"), "image/gif")
        target.use {
            it.output.use { outStream ->
                SimpleGifEncoder(
                    output = outStream,
                    width = targetWidth,
                    height = targetHeight,
                    delayMs = frameDelayMs,
                    repeat = 0
                ).use { encoder ->
                    encoder.addFrame(first)
                    onProgress(1f / frameCount)
                    for (i in 1 until frameCount) {
                        val timeMs = startMs + i * frameDelayMs
                        val timeUs = timeMs * 1000L
                        val rawFrame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                        if (rawFrame != null) {
                            try {
                                val cropped = Bitmap.createBitmap(rawFrame, cX, cY, cW, cH)
                                val scaled = scaleBitmapToWidth(cropped, widthPx)
                                if (scaled !== cropped) {
                                    cropped.recycle()
                                }
                                encoder.addFrame(scaled)
                                scaled.recycle()
                            } finally {
                                rawFrame.recycle()
                            }
                        }
                        onProgress((i + 1).toFloat() / frameCount)
                    }
                }
            }
            it.finish()
            it.pathForMessage
        }
    } finally {
        first?.recycle()
        runCatching { retriever.release() }
    }
}

private fun setRetrieverDataSource(context: Context, retriever: MediaMetadataRetriever, item: PlayerMediaItem) {
    try {
        retriever.setDataSource(context, item.uri)
        return
    } catch (e: Exception) {
        // Fallback to path or fd
    }

    if (item.path.isNotBlank() && File(item.path).exists()) {
        try {
            retriever.setDataSource(item.path)
            return
        } catch (e: Exception) {
            // fallback
        }
    }

    val descriptor = context.contentResolver.openFileDescriptor(item.uri, "r")
        ?: error("无法打开视频文件")
    descriptor.use {
        retriever.setDataSource(it.fileDescriptor)
    }
}

private fun scaleBitmapToWidth(source: Bitmap, width: Int): Bitmap {
    if (source.width <= width) return source
    val ratio = width.toFloat() / source.width.toFloat()
    val targetHeight = (source.height * ratio).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(source, width, targetHeight, true)
}

private fun openGalleryOutput(context: Context, displayName: String, mimeType: String): GalleryOutput {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/BlackCatPlayer")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建图库文件")
        val output = context.contentResolver.openOutputStream(uri) ?: error("无法打开图库输出流")
        GalleryOutput(
            output = output,
            pathForMessage = "Pictures/BlackCatPlayer/$displayName",
            finish = {
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                context.contentResolver.update(uri, done, null, null)
            }
        )
    } else {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "BlackCatPlayer")
            .apply { mkdirs() }
        val file = File(dir, displayName)
        GalleryOutput(
            output = file.outputStream(),
            pathForMessage = file.absolutePath,
            finish = {
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mimeType), null)
            }
        )
    }
}

private class GalleryOutput(
    val output: java.io.OutputStream,
    val pathForMessage: String,
    private val finish: () -> Unit
) : java.io.Closeable {
    private var finished = false

    fun finish() {
        if (!finished) {
            finish.invoke()
            finished = true
        }
    }

    override fun close() {
        output.close()
    }
}

private fun openCastSettings(context: android.content.Context) {
    val castIntent = Intent(Settings.ACTION_CAST_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val wirelessIntent = Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching {
        context.startActivity(castIntent)
    }.recoverCatching {
        context.startActivity(wirelessIntent)
    }
}

// VideoSwitchPreviewOverlay removed, handled by interactive swipe layers in player Box

// VLC player surface


@Composable
private fun VlcPlayerSurface(
    item: PlayerMediaItem,
    startPosition: Long,
    playbackSpeed: Float,
    volume: Int = 100,
    onReady: () -> Unit = {},
    onBindControls: (
        togglePlayPause: () -> Unit,
        seekTo: (Long) -> Unit,
        setSpeed: (Float) -> Unit,
        pause: () -> Unit,
        getAudioTracks: () -> List<Pair<Int, String>>,
        getActiveAudioTrack: () -> Int,
        setAudioTrack: (Int) -> Unit,
        getSubtitleTracks: () -> List<Pair<Int, String>>,
        getActiveSubtitleTrack: () -> Int,
        setSubtitleTrack: (Int) -> Unit,
        takeSnapshot: (String) -> Boolean
    ) -> Unit,
    onTracksChanged: (
        audioTracks: List<Pair<Int, String>>,
        activeAudio: Int,
        subtitleTracks: List<Pair<Int, String>>,
        activeSubtitle: Int
    ) -> Unit,
    onState: (position: Long, duration: Long, isPlaying: Boolean) -> Unit,
    onEnded: () -> Unit,
    onError: (String) -> Unit
) {
    val latestOnReady by rememberUpdatedState(onReady)
    val context = LocalContext.current
    val latestOnState by rememberUpdatedState(onState)
    val latestOnEnded by rememberUpdatedState(onEnded)
    val latestOnError by rememberUpdatedState(onError)
    val latestOnTracksChanged by rememberUpdatedState(onTracksChanged)
    val isExiting = remember { mutableStateOf(false) }
    var vlcVideoLayout by remember { mutableStateOf<VLCVideoLayout?>(null) }

    // Bind LibVLC and MediaPlayer lifetime to this composable.
    val libVlc = remember {
        LibVLC(
            context,
            arrayListOf(
                // Keep VLC conservative for 4K and high precision PCM audio.
                "--audio-time-stretch",
                "--network-caching=3000",
                "--file-caching=1000",
                "--live-caching=1500",
                "--codec=all",
                "--avcodec-hw=any"
            )
        )
    }
    val mediaPlayer = remember { VlcMediaPlayer(libVlc) }

    LaunchedEffect(volume) {
        runCatching {
            mediaPlayer.volume = volume.coerceIn(0, 200)
        }
    }

    // Mount the SurfaceView once.
    AndroidView(
        factory = { ctx ->
            VLCVideoLayout(ctx).also { layout ->
                layout.keepScreenOn = true
                vlcVideoLayout = layout
                mediaPlayer.attachViews(layout, null, true, false)
            }
        },
        modifier = Modifier.fillMaxSize()
    )

    // Replace media on item changes without rebuilding the VLC player.
    DisposableEffect(item) {
        var tracksEnumerated = false

        mediaPlayer.setEventListener { event ->
            when (event.type) {
                VlcMediaPlayer.Event.Playing -> {
                    latestOnState(
                        mediaPlayer.time.coerceAtLeast(0L),
                        mediaPlayer.length.coerceAtLeast(0L),
                        true
                    )
                    latestOnReady()
                    // Enumerate tracks only on the first Playing event to avoid JNI churn.
                    if (!tracksEnumerated) {
                        tracksEnumerated = true
                        val audio = mediaPlayer.audioTracks?.map { it.id to it.name } ?: emptyList()
                        val activeAudio = mediaPlayer.audioTrack
                        val subs = mediaPlayer.spuTracks?.map { it.id to it.name } ?: emptyList()
                        val activeSub = mediaPlayer.spuTrack
                        latestOnTracksChanged(audio, activeAudio, subs, activeSub)
                    }
                }
                VlcMediaPlayer.Event.Paused -> {
                    latestOnState(
                        mediaPlayer.time.coerceAtLeast(0L),
                        mediaPlayer.length.coerceAtLeast(0L),
                        false
                    )
                }
                VlcMediaPlayer.Event.Stopped -> {
                    latestOnState(0L, mediaPlayer.length.coerceAtLeast(0L), false)
                }
                VlcMediaPlayer.Event.TimeChanged,
                VlcMediaPlayer.Event.LengthChanged -> {
                    // Normalize native callback values before updating Compose state.
                    latestOnState(
                        mediaPlayer.time.coerceAtLeast(0L),
                        mediaPlayer.length.coerceAtLeast(0L),
                        mediaPlayer.isPlaying
                    )
                }
                VlcMediaPlayer.Event.EndReached -> latestOnEnded()
                VlcMediaPlayer.Event.EncounteredError -> latestOnError("EncounteredError")
            }
        }


        onBindControls(
            {
                if (mediaPlayer.isPlaying) mediaPlayer.pause() else mediaPlayer.play()
                latestOnState(mediaPlayer.time.coerceAtLeast(0L), mediaPlayer.length.coerceAtLeast(0L), mediaPlayer.isPlaying)
            },
            { target ->
                mediaPlayer.time = target.coerceAtLeast(0L)
                latestOnState(mediaPlayer.time.coerceAtLeast(0L), mediaPlayer.length.coerceAtLeast(0L), mediaPlayer.isPlaying)
            },
            { speed -> mediaPlayer.setRate(speed) },
            {
                mediaPlayer.pause()
                latestOnState(mediaPlayer.time.coerceAtLeast(0L), mediaPlayer.length.coerceAtLeast(0L), mediaPlayer.isPlaying)
            },
            { mediaPlayer.audioTracks?.map { it.id to it.name } ?: emptyList() },
            { mediaPlayer.audioTrack },
            { id -> mediaPlayer.audioTrack = id },
            { mediaPlayer.spuTracks?.map { it.id to it.name } ?: emptyList() },
            { mediaPlayer.spuTrack },
            { id -> mediaPlayer.setSpuTrack(id) },
            { path ->
                val layout = vlcVideoLayout
                val textureView = layout?.let { findTextureView(it) }
                if (textureView != null) {
                    val bitmap = runCatching { textureView.bitmap }.getOrNull()
                    if (bitmap != null) {
                        try {
                            File(path).outputStream().use { out ->
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                            true
                        } catch (e: Exception) {
                            e.printStackTrace()
                            false
                        } finally {
                            bitmap.recycle()
                        }
                    } else {
                        false
                    }
                } else {
                    false
                }
            }
        )

        val media = if (item.path.isNotBlank() && java.io.File(item.path).exists()) {
            VlcMedia(libVlc, android.net.Uri.fromFile(java.io.File(item.path)))
        } else {
            VlcMedia(libVlc, item.uri)
        }.apply {
            setHWDecoderEnabled(true, false)
            if (startPosition > 0L) {
                // Use VLC media options for initial position to avoid early JNI seek failures.
                addOption(":start-time=${startPosition / 1000f}")
            }
        }
        mediaPlayer.media = media
        media.release()
        mediaPlayer.play()
        mediaPlayer.setRate(playbackSpeed)

        onDispose {
            runCatching { mediaPlayer.setEventListener(null) }
            if (!isExiting.value && shouldStopVlcBeforeSwitch(item)) {
                runCatching { mediaPlayer.stop() }
            }
        }
    }

    // Release native resources when the composable leaves composition.
    DisposableEffect(Unit) {
        onDispose {
            isExiting.value = true
            runCatching { mediaPlayer.setEventListener(null) }
            runCatching { mediaPlayer.detachViews() }
            val mp = mediaPlayer
            val vlc = libVlc
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                runCatching { mp.stop() }
                runCatching { mp.release() }
                runCatching { vlc.release() }
            }
        }
    }
}

// UI controls



@Composable
private fun TopControls(
    title: String,
    indexText: String,
    isFavorite: Boolean,
    useVlcFallback: Boolean,
    sleepTimerText: String?,
    onBackClick: () -> Unit,
    onFavoriteClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.82f), Color.Transparent)))
            .padding(start = 12.dp, end = 12.dp, top = 24.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBackClick) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(indexText, color = SecondaryNeonCyan, fontSize = 11.sp)
                if (sleepTimerText != null) {
                    Spacer(Modifier.width(8.dp))
                    Text("睡眠 $sleepTimerText", color = AccentPink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                if (useVlcFallback) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(SecondaryNeonCyan.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("VLC 解码", color = SecondaryNeonCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        IconButton(onClick = onFavoriteClick) {
            Icon(
                if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = "收藏",
                tint = if (isFavorite) AccentPink else Color.White
            )
        }
    }
}

@Composable
private fun BottomControls(
    currentPosition: Long,
    duration: Long,
    isPlaying: Boolean,
    canPrev: Boolean,
    canNext: Boolean,
    playbackSpeed: Float,
    onSeek: (Long) -> Unit,
    onSeekFinished: (Long) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onFrameStep: (Int) -> Unit,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onRotate: () -> Unit,
    onCast: () -> Unit,
    onPip: () -> Unit,
    onScreenshot: () -> Unit,
    onGif: () -> Unit,
    onSetCover: () -> Unit,
    onInfo: () -> Unit,
    onSettings: () -> Unit
) {
    var showSpeedPanel by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
            .padding(start = 14.dp, end = 14.dp, top = 18.dp, bottom = 24.dp)
    ) {
        val scope = rememberCoroutineScope()
        var isDragging by remember { mutableStateOf(false) }
        var draggingJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
        var localProgress by remember { mutableStateOf(currentPosition.toFloat()) }
        LaunchedEffect(currentPosition) {
            if (!isDragging) {
                localProgress = currentPosition.toFloat()
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatDuration(localProgress.toLong()), color = Color.White, fontSize = 11.sp)
            ExactVideoSeekBar(
                positionMs = localProgress.toLong(),
                durationMs = duration,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                onSeek = {
                    isDragging = true
                    draggingJob?.cancel()
                    localProgress = it.toFloat()
                    onSeek(it)
                },
                onSeekFinished = {
                    localProgress = it.toFloat()
                    onSeekFinished(it)
                    draggingJob = scope.launch {
                        delay(300)
                        isDragging = false
                    }
                }
            )
            Text(formatDuration(duration), color = Color.White, fontSize = 11.sp)
        }
        AnimatedVisibility(visible = showSpeedPanel, enter = fadeIn(), exit = fadeOut()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                    FilterChip(
                        selected = playbackSpeed == speed,
                        onClick = { onSpeedChange(speed) },
                        label = { Text("${speed}x") }
                    )
                }
                OutlinedButton(onClick = { onFrameStep(-1) }) { Text("-1F") }
                OutlinedButton(onClick = { onFrameStep(1) }) { Text("+1F") }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onRotate) {
                        Icon(Icons.Default.ScreenRotation, contentDescription = "旋转", tint = Color.White)
                    }
                    IconButton(onClick = onCast) {
                        Icon(Icons.Default.Cast, contentDescription = "投屏", tint = Color.White)
                    }
                    IconButton(onClick = onPip) {
                        Icon(Icons.Default.PictureInPicture, contentDescription = "小窗播放", tint = Color.White)
                    }
                    IconButton(onClick = onScreenshot) {
                        Icon(Icons.Default.CameraAlt, contentDescription = "截图", tint = Color.White)
                    }
                    TextButton(onClick = onGif) {
                        Text("GIF", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = onSetCover) {
                        Text("封面", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = onInfo) {
                        Icon(Icons.Default.Info, contentDescription = "视频信息", tint = Color.White)
                    }
                    TextButton(onClick = { showSpeedPanel = !showSpeedPanel }) {
                        Text("${playbackSpeed}x", color = Color.White, fontSize = 12.sp)
                    }
                }
            }
            Box(
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPrev, enabled = canPrev) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "上一个", tint = if (canPrev) Color.White else Color.Gray)
                    }
                    IconButton(
                        onClick = onPlayPause,
                        modifier = Modifier
                            .size(58.dp)
                            .clip(RoundedCornerShape(29.dp))
                            .background(SecondaryNeonCyan)
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "播放暂停",
                            tint = Color.Black,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                    IconButton(onClick = onNext, enabled = canNext) {
                        Icon(Icons.Default.SkipNext, contentDescription = "下一个", tint = if (canNext) Color.White else Color.Gray)
                    }
                }
            }
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterEnd
            ) {
                IconButton(onClick = onSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "设置", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun PlayerSettingsDialog(
    skipSeconds: Int,
    onSkipSecondsChange: (Int) -> Unit,
    autoReplay: Boolean,
    onAutoReplayChange: (Boolean) -> Unit,
    autoPlayNext: Boolean,
    onAutoPlayNextChange: (Boolean) -> Unit,
    playbackSpeed: Float,
    onPlaybackSpeedChange: (Float) -> Unit,
    resizeMode: Int,
    onResizeModeChange: (Int) -> Unit,
    onFrameStep: (Int) -> Unit,
    useVlcFallback: Boolean,
    onToggleDecoder: () -> Unit,
    audioOptions: List<TrackOption>,
    subtitleOptions: List<TrackOption>,
    currentPreset: String,
    onPresetChange: (String) -> Unit,
    isBackgroundPlayEnabled: Boolean,
    onBackgroundPlayChange: (Boolean) -> Unit,
    isPipEnabled: Boolean,
    onPipChange: (Boolean) -> Unit,
    sleepTimerMinutes: Int,
    onSleepTimerChange: (Int) -> Unit,
    onShowTutorial: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("播放设置", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("双击快进/后退：$skipSeconds 秒", color = Color.White, fontSize = 14.sp)
                Slider(
                    value = skipSeconds.toFloat(),
                    onValueChange = { onSkipSecondsChange(it.toInt()) },
                    valueRange = 3f..60f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = SecondaryNeonCyan,
                        inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                    )
                )
                SettingSwitch("单视频循环", autoReplay, onAutoReplayChange)
                SettingSwitch("列表连播，到末尾后从头循环", autoPlayNext, onAutoPlayNextChange)
                SettingSwitch("开启后台播放音频", isBackgroundPlayEnabled, onBackgroundPlayChange)
                SettingSwitch("自动开启小窗播放", isPipEnabled, onPipChange)
                
                Text("睡眠定时", color = Color.White, fontWeight = FontWeight.Bold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "关闭", 10 to "10 分钟", 20 to "20 分钟", 30 to "30 分钟", 60 to "60 分钟").forEach { (minutes, label) ->
                        FilterChip(
                            selected = sleepTimerMinutes == minutes,
                            onClick = { onSleepTimerChange(minutes) },
                            label = { Text(label) }
                        )
                    }
                }
                OutlinedButton(
                    onClick = onToggleDecoder,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (useVlcFallback) "切换为 ExoPlayer 解码" else "切换为 VLC 解码（支持 PCM S24 LE）")
                }
                OutlinedButton(
                    onClick = onShowTutorial,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("查看手势使用教程与区域标注")
                }
                
                // Audio tracks
                if (audioOptions.isNotEmpty()) {
                    Text("音频轨道", color = Color.White, fontWeight = FontWeight.Bold)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        audioOptions.forEach { option ->
                            FilterChip(
                                selected = option.isSelected,
                                onClick = { option.onSelect() },
                                label = { Text(option.label) }
                            )
                        }
                    }
                }

                // Subtitle tracks
                if (subtitleOptions.isNotEmpty()) {
                    Text("字幕轨道", color = Color.White, fontWeight = FontWeight.Bold)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        subtitleOptions.forEach { option ->
                            FilterChip(
                                selected = option.isSelected,
                                onClick = { option.onSelect() },
                                label = { Text(option.label) }
                            )
                        }
                    }
                }

                // Equalizer presets for ExoPlayer mode
                if (!useVlcFallback) {
                    Text("声音均衡器预设", color = Color.White, fontWeight = FontWeight.Bold)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Normal", "Bass Boost", "Vocal Clear", "Rock", "Pop", "Classical").forEach { preset ->
                            FilterChip(
                                selected = currentPreset == preset,
                                onClick = { onPresetChange(preset) },
                                label = { Text(preset) }
                            )
                        }
                    }
                }

                Text("播放速度", color = Color.White, fontWeight = FontWeight.Bold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f).forEach { speed ->
                        FilterChip(selected = playbackSpeed == speed, onClick = { onPlaybackSpeedChange(speed) }, label = { Text("${speed}x") })
                    }
                }
                if (!useVlcFallback) {
                    Text("画面比例", color = Color.White, fontWeight = FontWeight.Bold)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            AspectRatioFrameLayout.RESIZE_MODE_FIT to "适应",
                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "裁剪",
                            AspectRatioFrameLayout.RESIZE_MODE_FILL to "拉伸"
                        ).forEach { (mode, label) ->
                            FilterChip(selected = resizeMode == mode, onClick = { onResizeModeChange(mode) }, label = { Text(label) })
                        }
                    }
                }
                Text("逐帧控制", color = Color.White, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onFrameStep(-1) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                        Text("上一帧")
                    }
                    OutlinedButton(onClick = { onFrameStep(1) }) {
                        Text("下一帧")
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
                    }
                    OutlinedButton(onClick = { onFrameStep(5) }) { Text("+5 帧") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("确定", color = SecondaryNeonCyan) } },
        containerColor = CarbonCard,
        shape = RoundedCornerShape(18.dp)
    )
}

private data class TrackOption(
    val id: String,
    val label: String,
    val isSelected: Boolean,
    val onSelect: () -> Unit
)

@Composable
private fun GifExportDialog(
    defaultStartMs: Long,
    durationMs: Long,
    isExporting: Boolean,
    progress: Float,
    onDismiss: () -> Unit,
    onExport: (Long, Int) -> Unit
) {
    val maxSeconds = (durationMs / 1000L).toInt().coerceIn(1, 10)
    var seconds by remember { mutableIntStateOf(minOf(3, maxSeconds)) }
    val maxStart = (durationMs - seconds * 1000L).coerceAtLeast(0L)
    var startMs by remember { mutableLongStateOf(defaultStartMs.coerceIn(0L, maxStart)) }

    LaunchedEffect(maxStart) {
        if (startMs > maxStart) {
            startMs = maxStart
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("截取 GIF", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("起始位置：${formatDuration(startMs)}", color = Color.White, fontSize = 14.sp)
                Slider(
                    value = startMs.toFloat().coerceIn(0f, maxStart.toFloat()),
                    onValueChange = { startMs = it.toLong() },
                    valueRange = 0f..maxStart.toFloat().coerceAtLeast(1f),
                    enabled = !isExporting,
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = SecondaryNeonCyan)
                )
                Text("截取时长：$seconds 秒", color = Color.White, fontSize = 14.sp)
                Slider(
                    value = seconds.toFloat(),
                    onValueChange = { seconds = it.toInt().coerceIn(1, maxSeconds) },
                    valueRange = 1f..maxSeconds.toFloat().coerceAtLeast(2f),
                    steps = if (maxSeconds > 1) maxSeconds - 1 else 0,
                    enabled = !isExporting,
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = SecondaryNeonCyan)
                )
                if (isExporting) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = SecondaryNeonCyan,
                        trackColor = Color.White.copy(alpha = 0.18f)
                    )
                    Text("${(progress * 100).toInt()}%", color = TextSecondary, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onExport(startMs, seconds) },
                enabled = !isExporting
            ) {
                Text("开始导出", color = SecondaryNeonCyan)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isExporting) {
                Text("取消", color = Color.White)
            }
        },
        containerColor = CarbonCard,
        shape = RoundedCornerShape(18.dp)
    )
}

@Composable
private fun VideoInfoDialog(
    item: PlayerMediaItem,
    duration: Long,
    tracks: Tracks,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("视频信息", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                InfoLine("文件名", item.displayName)
                InfoLine("时长", formatDuration(if (duration > 0) duration else item.duration))
                InfoLine("文件大小", formatFileSize(item.size))
                InfoLine("分辨率", item.resolution.ifBlank { "未知" })
                InfoLine("路径", item.path.ifBlank { item.uri.toString() })
                HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                Text("轨道信息", color = SecondaryNeonCyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                val descriptions = remember(tracks) { describeTracks(tracks) }
                if (descriptions.isEmpty()) {
                    Text("轨道信息会在视频准备完成后显示。", color = TextSecondary, fontSize = 12.sp)
                } else {
                    descriptions.forEach { Text(it, color = TextSecondary, fontSize = 12.sp) }
                }
                Text(
                    "解码策略：ExoPlayer 硬件优先；遇到特殊音轨或错误时自动切换 VLC 全格式解码。",
                    color = SecondaryNeonCyan,
                    fontSize = 12.sp
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭", color = SecondaryNeonCyan) } },
        containerColor = CarbonCard,
        shape = RoundedCornerShape(18.dp)
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Column {
        Text(label, color = TextSecondary, fontSize = 11.sp)
        Text(value, color = Color.White, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

private fun describeTracks(tracks: Tracks): List<String> {
    val result = mutableListOf<String>()
    tracks.groups.forEach { group ->
        for (i in 0 until group.length) {
            val format = group.getTrackFormat(i)
            val selected = if (group.isTrackSelected(i)) "已选" else "可用"
            val supported = if (group.isTrackSupported(i, true)) "支持" else "可能不支持"
            result += when (group.type) {
                C.TRACK_TYPE_VIDEO -> "视频 ${i + 1} [$selected/$supported] ${format.videoSummary()}"
                C.TRACK_TYPE_AUDIO -> "音频 ${i + 1} [$selected/$supported] ${format.audioSummary()}"
                C.TRACK_TYPE_TEXT -> "字幕 ${i + 1} [$selected/$supported] ${format.textSummary()}"
                else -> "轨道 ${i + 1} [$selected/$supported] ${format.sampleMimeType ?: "未知类型"}"
            }
        }
    }
    return result
}

private fun Format.videoSummary(): String {
    val size = if (width > 0 && height > 0) "${width}x${height}" else "未知分辨率"
    val fps = if (frameRate > 0) ", ${"%.2f".format(frameRate)}fps" else ""
    return "${sampleMimeType ?: "未知格式"} ${codecs ?: ""} $size$fps".trim()
}

private fun Format.audioSummary(): String {
    val channels = if (channelCount > 0) "${channelCount}ch" else "未知声道"
    val rate = if (sampleRate > 0) ", ${sampleRate}Hz" else ""
    val lang = language?.takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""
    return "${sampleMimeType ?: "未知格式"} ${codecs ?: ""} $channels$rate$lang".trim()
}

private fun Format.textSummary(): String {
    val lang = language?.takeIf { it.isNotBlank() } ?: "未知语言"
    return "${sampleMimeType ?: "未知字幕"} $lang".trim()
}

@Composable
private fun SettingSwitch(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Color.White, fontSize = 14.sp)
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = SecondaryNeonCyan,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = Color.White.copy(alpha = 0.12f),
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

@Composable
private fun ErrorPanel(error: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .padding(20.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.82f))
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Error, contentDescription = null, tint = StatusRed, modifier = Modifier.size(34.dp))
        Spacer(Modifier.height(8.dp))
        Text(error, color = Color.White, fontSize = 13.sp)
    }
}

private class SeekQueue(private val player: ExoPlayer) {
    var isSeeking = false
        private set
    var lastSeekTimeMs = 0L
    private var pendingPositionMs: Long? = null
    private var pendingExact: Boolean = false

    fun seekTo(positionMs: Long, exact: Boolean, force: Boolean = false) {
        if (force) {
            pendingPositionMs = null
            pendingExact = false
            dispatch(positionMs, exact)
            return
        }
        if (isSeeking) {
            pendingPositionMs = positionMs
            pendingExact = exact
            return
        }
        dispatch(positionMs, exact)
    }

    private fun dispatch(positionMs: Long, exact: Boolean) {
        isSeeking = true
        lastSeekTimeMs = System.currentTimeMillis()
        val targetParams = if (exact) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC
        if (player.seekParameters != targetParams) {
            player.setSeekParameters(targetParams)
        }
        player.seekTo(positionMs)
    }

    fun onSeekProcessed() {
        val pending = pendingPositionMs
        if (pending != null) {
            val exact = pendingExact
            pendingPositionMs = null
            pendingExact = false
            dispatch(pending, exact)
        } else {
            isSeeking = false
        }
    }

    fun reset() {
        isSeeking = false
        lastSeekTimeMs = 0L
        pendingPositionMs = null
        pendingExact = false
    }
}

// 内置教程覆盖已迁移到 TutorialOverlay.kt
// 下面空函数仅保留符号引用兼容，内容已坐落不再进入
@Composable
private fun PlayerTutorialOverlay(onDismiss: () -> Unit) {
    com.example.videoplayer.ui.components.PlayerTutorialOverlay(
        visible = true,
        onDismiss = onDismiss
    )
}

@Composable
private fun InteractiveGifEditor(
    defaultStartMs: Long,
    durationMs: Long,
    videoWidth: Int,
    videoHeight: Int,
    isExporting: Boolean,
    progress: Float,
    onDismiss: () -> Unit,
    onExport: (Long, Int, Float, Float, Float, Float) -> Unit
) {
    val maxSeconds = (durationMs / 1000L).toInt().coerceIn(1, 10)
    var seconds by remember { mutableIntStateOf(minOf(3, maxSeconds)) }
    val maxStart = (durationMs - seconds * 1000L).coerceAtLeast(0L)
    var startMs by remember { mutableLongStateOf(defaultStartMs.coerceIn(0L, maxStart)) }

    var containerWidth by remember { mutableFloatStateOf(1f) }
    var containerHeight by remember { mutableFloatStateOf(1f) }
    
    var pxLeft by remember { mutableFloatStateOf(0f) }
    var pxTop by remember { mutableFloatStateOf(0f) }
    var pxRight by remember { mutableFloatStateOf(0f) }
    var pxBottom by remember { mutableFloatStateOf(0f) }
    var initialized by remember { mutableStateOf(false) }

    LaunchedEffect(containerWidth, containerHeight) {
        if (containerWidth > 1f && containerHeight > 1f && !initialized) {
            pxLeft = containerWidth * 0.15f
            pxTop = containerHeight * 0.15f
            pxRight = containerWidth * 0.85f
            pxBottom = containerHeight * 0.85f
            initialized = true
        }
    }

    LaunchedEffect(maxStart) {
        if (startMs > maxStart) {
            startMs = maxStart
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                containerWidth = it.size.width.toFloat()
                containerHeight = it.size.height.toFloat()
            }
    ) {
        // Draggable handler overlay
        var dragMode by remember { mutableStateOf(DragMode.None) }
        val density = androidx.compose.ui.platform.LocalDensity.current
        val minSize = with(density) { 80.dp.toPx() }
        val touchTolerance = with(density) { 40.dp.toPx() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(initialized) {
                    if (!initialized) return@pointerInput
                    detectDragGestures(
                        onDragStart = { offset ->
                            val x = offset.x
                            val y = offset.y
                            dragMode = when {
                                abs(x - pxLeft) < touchTolerance && abs(y - pxTop) < touchTolerance -> DragMode.ResizeTopLeft
                                abs(x - pxRight) < touchTolerance && abs(y - pxTop) < touchTolerance -> DragMode.ResizeTopRight
                                abs(x - pxLeft) < touchTolerance && abs(y - pxBottom) < touchTolerance -> DragMode.ResizeBottomLeft
                                abs(x - pxRight) < touchTolerance && abs(y - pxBottom) < touchTolerance -> DragMode.ResizeBottomRight
                                x in pxLeft..pxRight && y in pxTop..pxBottom -> DragMode.Move
                                else -> DragMode.None
                            }
                        },
                        onDrag = { change, dragAmount ->
                            if (isExporting) return@detectDragGestures
                            change.consume()
                            when (dragMode) {
                                DragMode.Move -> {
                                    val w = pxRight - pxLeft
                                    val h = pxBottom - pxTop
                                    val newLeft = (pxLeft + dragAmount.x).coerceIn(0f, containerWidth - w)
                                    val newTop = (pxTop + dragAmount.y).coerceIn(0f, containerHeight - h)
                                    pxLeft = newLeft
                                    pxRight = newLeft + w
                                    pxTop = newTop
                                    pxBottom = newTop + h
                                }
                                DragMode.ResizeTopLeft -> {
                                    pxLeft = (pxLeft + dragAmount.x).coerceIn(0f, pxRight - minSize)
                                    pxTop = (pxTop + dragAmount.y).coerceIn(0f, pxBottom - minSize)
                                }
                                DragMode.ResizeTopRight -> {
                                    pxRight = (pxRight + dragAmount.x).coerceIn(pxLeft + minSize, containerWidth)
                                    pxTop = (pxTop + dragAmount.y).coerceIn(0f, pxBottom - minSize)
                                }
                                DragMode.ResizeBottomLeft -> {
                                    pxLeft = (pxLeft + dragAmount.x).coerceIn(0f, pxRight - minSize)
                                    pxBottom = (pxBottom + dragAmount.y).coerceIn(pxTop + minSize, containerHeight)
                                }
                                DragMode.ResizeBottomRight -> {
                                    pxRight = (pxRight + dragAmount.x).coerceIn(pxLeft + minSize, containerWidth)
                                    pxBottom = (pxBottom + dragAmount.y).coerceIn(pxTop + minSize, containerHeight)
                                }
                                DragMode.None -> {}
                            }
                        },
                        onDragEnd = {
                            dragMode = DragMode.None
                        }
                    )
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (!initialized) return@Canvas
                val w = size.width
                val h = size.height

                // Draw 4-area dimmed masks
                drawRect(Color.Black.copy(alpha = 0.65f), Offset(0f, 0f), Size(w, pxTop))
                drawRect(Color.Black.copy(alpha = 0.65f), Offset(0f, pxBottom), Size(w, h - pxBottom))
                drawRect(Color.Black.copy(alpha = 0.65f), Offset(0f, pxTop), Size(pxLeft, pxBottom - pxTop))
                drawRect(Color.Black.copy(alpha = 0.65f), Offset(pxRight, pxTop), Size(w - pxRight, pxBottom - pxTop))

                // Draw crop box border
                drawRect(
                    color = SecondaryNeonCyan,
                    topLeft = Offset(pxLeft, pxTop),
                    size = Size(pxRight - pxLeft, pxBottom - pxTop),
                    style = Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f), 0f)
                    )
                )

                // Draw L-shaped corner handles
                val handleLen = 18.dp.toPx()
                val handleThick = 4.dp.toPx()
                val color = SecondaryNeonCyan

                // Top-Left
                drawLine(color, Offset(pxLeft, pxTop), Offset(pxLeft + handleLen, pxTop), strokeWidth = handleThick)
                drawLine(color, Offset(pxLeft, pxTop), Offset(pxLeft, pxTop + handleLen), strokeWidth = handleThick)
                // Top-Right
                drawLine(color, Offset(pxRight, pxTop), Offset(pxRight - handleLen, pxTop), strokeWidth = handleThick)
                drawLine(color, Offset(pxRight, pxTop), Offset(pxRight, pxTop + handleLen), strokeWidth = handleThick)
                // Bottom-Left
                drawLine(color, Offset(pxLeft, pxBottom), Offset(pxLeft + handleLen, pxBottom), strokeWidth = handleThick)
                drawLine(color, Offset(pxLeft, pxBottom), Offset(pxLeft, pxBottom - handleLen), strokeWidth = handleThick)
                // Bottom-Right
                drawLine(color, Offset(pxRight, pxBottom), Offset(pxRight - handleLen, pxBottom), strokeWidth = handleThick)
                drawLine(color, Offset(pxRight, pxBottom), Offset(pxRight, pxBottom - handleLen), strokeWidth = handleThick)
            }
        }

        // Glassmorphic bottom control panel
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xE60D1117))
                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(22.dp))
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("截取 GIF 区域及时间范围", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    if (isExporting) {
                        Text("导出中 ${(progress * 100).toInt()}%", color = SecondaryNeonCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("开始时间", color = TextSecondary, fontSize = 12.sp)
                        Text(formatDuration(startMs), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = startMs.toFloat().coerceIn(0f, maxStart.toFloat()),
                        onValueChange = { startMs = it.toLong() },
                        valueRange = 0f..maxStart.toFloat().coerceAtLeast(1f),
                        enabled = !isExporting,
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = SecondaryNeonCyan, inactiveTrackColor = Color.White.copy(alpha = 0.2f))
                    )
                }

                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("截取时长", color = TextSecondary, fontSize = 12.sp)
                        Text("$seconds 秒", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = seconds.toFloat(),
                        onValueChange = { seconds = it.toInt().coerceIn(1, maxSeconds) },
                        valueRange = 1f..maxSeconds.toFloat().coerceAtLeast(2f),
                        steps = if (maxSeconds > 1) maxSeconds - 1 else 0,
                        enabled = !isExporting,
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = SecondaryNeonCyan, inactiveTrackColor = Color.White.copy(alpha = 0.2f))
                    )
                }

                if (isExporting) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                        color = SecondaryNeonCyan,
                        trackColor = Color.White.copy(alpha = 0.15f)
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.08f)),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.15f))
                        ) {
                            Text("取消", color = Color.White)
                        }
                        
                        Button(
                            onClick = {
                                // Calculate coordinates relative to video viewport boundaries
                                val videoLeft: Float
                                val videoTop: Float
                                val videoWidthOnScreen: Float
                                val videoHeightOnScreen: Float

                                if (videoWidth > 0 && videoHeight > 0 && containerWidth > 0 && containerHeight > 0) {
                                    val rScreen = containerWidth / containerHeight
                                    val rVideo = videoWidth.toFloat() / videoHeight.toFloat()
                                    if (rScreen > rVideo) {
                                        videoHeightOnScreen = containerHeight
                                        videoWidthOnScreen = containerHeight * rVideo
                                        videoLeft = (containerWidth - videoWidthOnScreen) / 2f
                                        videoTop = 0f
                                    } else {
                                        videoWidthOnScreen = containerWidth
                                        videoHeightOnScreen = containerWidth / rVideo
                                        videoLeft = 0f
                                        videoTop = (containerHeight - videoHeightOnScreen) / 2f
                                    }
                                } else {
                                    videoLeft = 0f
                                    videoTop = 0f
                                    videoWidthOnScreen = containerWidth
                                    videoHeightOnScreen = containerHeight
                                }

                                val relativeLeft = ((pxLeft - videoLeft) / videoWidthOnScreen).coerceIn(0f, 1f)
                                val relativeTop = ((pxTop - videoTop) / videoHeightOnScreen).coerceIn(0f, 1f)
                                val relativeRight = ((pxRight - videoLeft) / videoWidthOnScreen).coerceIn(0f, 1f)
                                val relativeBottom = ((pxBottom - videoTop) / videoHeightOnScreen).coerceIn(0f, 1f)

                                onExport(
                                    startMs,
                                    seconds,
                                    relativeLeft,
                                    relativeTop,
                                    relativeRight - relativeLeft,
                                    relativeBottom - relativeTop
                                )
                            },
                            modifier = Modifier.weight(1.5f),
                            colors = ButtonDefaults.buttonColors(containerColor = SecondaryNeonCyan),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("导出 GIF", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

private enum class DragMode {
    None, Move, ResizeTopLeft, ResizeTopRight, ResizeBottomLeft, ResizeBottomRight
}

@Composable
private fun CastDeviceSelectionDialog(
    onDismiss: () -> Unit,
    onDeviceSelected: (DlnaDevice) -> Unit
) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        DlnaCastManager.startDiscovery(context)
        onDispose {
            DlnaCastManager.stopDiscovery()
        }
    }
    val devices = DlnaCastManager.devices

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Cast, contentDescription = null, tint = SecondaryNeonCyan, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text("选择投屏设备", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("请选择同一局域网内的 DLNA/UPnP/Cast 设备。", color = TextSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                
                if (devices.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = SecondaryNeonCyan, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("正在搜索局域网投屏设备...", color = TextMuted, fontSize = 12.sp)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(devices) { device ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White.copy(alpha = 0.05f))
                                    .border(0.5.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                                    .clickable { onDeviceSelected(device) }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(device.friendlyName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                    val ip = device.location.substringAfter("http://").substringBefore("/")
                                    Text("局域网设备 - $ip", color = TextMuted, fontSize = 10.sp)
                                }
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = SecondaryNeonCyan, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = Color.White)
            }
        },
        containerColor = CarbonCard,
        shape = RoundedCornerShape(18.dp)
    )
}

@Composable
private fun CastRemoteControlOverlay(
    videoTitle: String,
    deviceName: String,
    isPlaying: Boolean,
    volume: Int,
    speed: Float,
    position: Long,
    duration: Long,
    isLandscape: Boolean,
    canPrev: Boolean,
    canNext: Boolean,
    onPlayPauseToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolumeChange: (Int) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onRotateToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onDisconnect: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF070709))
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Cast, contentDescription = null, tint = SecondaryNeonCyan, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("正在投屏到 $deviceName", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF10B981)) // Green Connected Dot
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("局域网 4K 设备已连接", color = Color(0xFF10B981), fontSize = 10.sp)
                        }
                    }
                }
                
                Button(
                    onClick = onDisconnect,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(0.5.dp, Color.Red.copy(alpha = 0.4f)),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text("断开连接", color = Color.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Central Remote Body
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = videoTitle,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "媒体地址: ${DlnaCastManager.castUrl}",
                    color = TextMuted,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
                
                Spacer(Modifier.height(40.dp))
                
                // Remote Controller Buttons Panel
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Volume Down
                    IconButton(
                        onClick = { onVolumeChange(volume - 5) },
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.05f))
                            .border(0.5.dp, Color.White.copy(alpha = 0.1f), CircleShape)
                    ) {
                        Text("- 音量", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    
                    // Prev
                    IconButton(
                        onClick = onPrev,
                        enabled = canPrev,
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = if (canPrev) 0.05f else 0.01f))
                    ) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = null, tint = if (canPrev) Color.White else Color.Gray)
                    }

                    // Play/Pause Large
                    IconButton(
                        onClick = onPlayPauseToggle,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(SecondaryNeonCyan)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(34.dp)
                        )
                    }

                    // Next
                    IconButton(
                        onClick = onNext,
                        enabled = canNext,
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = if (canNext) 0.05f else 0.01f))
                    ) {
                        Icon(Icons.Default.SkipNext, contentDescription = null, tint = if (canNext) Color.White else Color.Gray)
                    }

                    // Volume Up
                    IconButton(
                        onClick = { onVolumeChange(volume + 5) },
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.05f))
                            .border(0.5.dp, Color.White.copy(alpha = 0.1f), CircleShape)
                    ) {
                        Text("+ 音量", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                
                Spacer(Modifier.height(30.dp))
                
                // Secondary Controls: Speed & Rotate
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // TV Screen Rotate
                    Button(
                        onClick = onRotateToggle,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.06f)),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.12f)),
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) {
                        Icon(Icons.Default.ScreenRotation, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (isLandscape) "切换竖屏" else "切换横屏", color = Color.White, fontSize = 12.sp)
                    }
                    
                    // TV Playback Speed Toggle
                    var speedMenuExpanded by remember { mutableStateOf(false) }
                    Box {
                        Button(
                            onClick = { speedMenuExpanded = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.06f)),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.12f)),
                            modifier = Modifier.padding(horizontal = 8.dp)
                        ) {
                            Text("倍速 ${speed}x", color = Color.White, fontSize = 12.sp)
                        }
                        DropdownMenu(
                            expanded = speedMenuExpanded,
                            onDismissRequest = { speedMenuExpanded = false }
                        ) {
                            listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { spd ->
                                DropdownMenuItem(
                                    text = { Text("${spd}x") },
                                    onClick = {
                                        onSpeedChange(spd)
                                        speedMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // TV Progress Seek Bar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.03f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
                    .padding(16.dp)
            ) {
                var seekProgress by remember(position) { mutableFloatStateOf(position.toFloat()) }
                var isSeeking by remember { mutableStateOf(false) }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatDuration(seekProgress.toLong()), color = Color.White, fontSize = 11.sp)
                    Text(formatDuration(duration), color = Color.White, fontSize = 11.sp)
                }
                
                Slider(
                    value = if (duration > 0L) seekProgress.coerceIn(0f, duration.toFloat()) else 0f,
                    onValueChange = {
                        isSeeking = true
                        seekProgress = it
                    },
                    onValueChangeFinished = {
                        isSeeking = false
                        onSeek(seekProgress.toLong())
                    },
                    valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = SecondaryNeonCyan,
                        inactiveTrackColor = Color.White.copy(alpha = 0.15f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "滑动进度条调整电视播放进度",
                    color = TextMuted,
                    fontSize = 9.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }
}
