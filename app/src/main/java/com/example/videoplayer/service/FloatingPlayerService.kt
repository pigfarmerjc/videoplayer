package com.example.videoplayer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.videoplayer.FloatingPlayerManager
import com.example.videoplayer.MainActivity
import com.example.videoplayer.data.model.MediaItem
import com.example.videoplayer.data.repository.MediaRepository
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media as VlcMedia
import org.videolan.libvlc.MediaPlayer as VlcMediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import java.util.Locale

class FloatingPlayerService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: FrameLayout? = null
    private var playerContainer: FrameLayout? = null
    private var controlOverlay: FrameLayout? = null
    private var progressBar: ProgressBar? = null
    private var playPauseButton: ImageButton? = null
    private var titleText: TextView? = null

    // Players
    private var exoPlayer: ExoPlayer? = null
    private var exoPlayerView: PlayerView? = null
    private var libVlc: LibVLC? = null
    private var vlcMediaPlayer: VlcMediaPlayer? = null
    private var vlcVideoLayout: VLCVideoLayout? = null

    private lateinit var repository: MediaRepository
    private val mainHandler = Handler(Looper.getMainLooper())
    private var progressTicker: Runnable? = null
    private var controlsTimeoutRunnable: Runnable? = null

    private var aspectRatio = 16f / 9f
    private val minWidth = 300
    private var maxWidth = 1920

    // WindowManager LayoutParams — always read fresh from floatingView.layoutParams
    private lateinit var windowParams: WindowManager.LayoutParams

    // Touch state
    private var touchDownWindowX = 0
    private var touchDownWindowY = 0
    private var touchDownRawX = 0f
    private var touchDownRawY = 0f
    private var touchDownWidth = 0
    private var touchDownHeight = 0
    private var isDragging = false
    private var isResizing = false
    private var isTouchConsumedByScale = false
    // Double-tap seek detection
    private var lastTapTimeMs = 0L
    private var lastTapRawX = 0f
    private val DOUBLE_TAP_MAX_MS = 350L
    private val DOUBLE_TAP_SLOP_PX = 80f
    private val SEEK_STEP_MS = 10_000L

    // Scale gesture
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        android.util.Log.d("FloatingPlayer", "FloatingPlayerService onCreate")
        repository = MediaRepository(applicationContext)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = resources.displayMetrics
        maxWidth = dm.widthPixels

        val currentItem = FloatingPlayerManager.playlist.getOrNull(FloatingPlayerManager.currentIndex)
        if (currentItem != null) {
            aspectRatio = getAspectRatio(currentItem.resolution)
        }

        startForegroundServiceNotification()
        createFloatingWindow()
        initializePlayer()
        startProgressTicker()
    }

    private fun startForegroundServiceNotification() {
        val channelId = "floating_player_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "视频悬浮窗播放",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("视频悬浮窗正在播放")
            .setContentText("正在以小窗模式播放视频")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1337,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(1337, notification)
        }
    }

    private fun createFloatingWindow() {
        android.util.Log.d("FloatingPlayer", "createFloatingWindow")

        // Build scale gesture detector first (used inside touch listener)
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isTouchConsumedByScale = true
                isDragging = false
                isResizing = false
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                val lp = floatingView?.layoutParams as? WindowManager.LayoutParams ?: return true
                val dm = resources.displayMetrics
                val maxW = (dm.widthPixels * 0.85f).toInt()
                val maxH = (dm.heightPixels * 0.85f).toInt()
                var newWidth = (lp.width * scaleFactor).toInt().coerceIn(minWidth, maxW)
                var newHeight = (newWidth / aspectRatio).toInt()
                if (newHeight > maxH) {
                    newHeight = maxH
                    newWidth = (newHeight * aspectRatio).toInt()
                }
                lp.width = newWidth
                lp.height = newHeight
                safeUpdateLayout(lp)
                FloatingPlayerManager.width = newWidth
                FloatingPlayerManager.height = newHeight
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                // optional cleanup
            }
        })

        floatingView = FrameLayout(this).apply {
            setBackgroundColor(AndroidColor.BLACK)
            clipToOutline = true
        }

        playerContainer = FrameLayout(this)
        floatingView!!.addView(playerContainer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        setupControlOverlay()

        // Clamp initial position to screen
        val dm = resources.displayMetrics
        val maxW = (dm.widthPixels * 0.85f).toInt()
        val maxH = (dm.heightPixels * 0.85f).toInt()
        var initW = FloatingPlayerManager.width.takeIf { it > 0 } ?: (dm.widthPixels / 2)
        var initH = FloatingPlayerManager.height.takeIf { it > 0 } ?: (initW / aspectRatio).toInt()
        if (initH > maxH) {
            initH = maxH
            initW = (initH * aspectRatio).toInt()
        }
        if (initW > maxW) {
            initW = maxW
            initH = (initW / aspectRatio).toInt()
        }
        if (initW < minWidth) {
            initW = minWidth
            initH = (initW / aspectRatio).toInt()
        }
        val initX = FloatingPlayerManager.x.coerceIn(0, (dm.widthPixels - initW).coerceAtLeast(0))
        val initY = FloatingPlayerManager.y.coerceIn(0, (dm.heightPixels - initH).coerceAtLeast(0))

        windowParams = WindowManager.LayoutParams(
            initW,
            initH,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initX
            y = initY
        }

        windowManager.addView(floatingView, windowParams)

        // Install touch listener AFTER view is added (so we always read windowParams fresh)
        floatingView!!.setOnTouchListener(floatingTouchListener)
    }

    /**
     * Main touch listener on the outer floatingView.
     * Handles:
     *  - ScaleGestureDetector (pinch to resize)
     *  - Drag (single finger move)
     *  - Tap (toggle controls)
     *
     * Button clicks on controlOverlay children are handled separately via their own onClick listeners.
     * They work because we let the touch system deliver ACTION_DOWN to the buttons when controls are visible,
     * unless the finger starts moving — in which case we cancel the child's touch and drag instead.
     */
    private val floatingTouchListener = View.OnTouchListener { _, event ->
        // Always feed to scale detector
        scaleGestureDetector.onTouchEvent(event)

        val action = event.actionMasked

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                val lp = floatingView?.layoutParams as? WindowManager.LayoutParams ?: return@OnTouchListener false
                touchDownWindowX = lp.x
                touchDownWindowY = lp.y
                touchDownRawX = event.rawX
                touchDownRawY = event.rawY
                touchDownWidth = lp.width
                touchDownHeight = lp.height
                isDragging = false
                isResizing = false
                isTouchConsumedByScale = false

                // Bottom-right corner = resize
                val cornerSize = dpToPx(48)
                isResizing = (event.x > lp.width - cornerSize && event.y > lp.height - cornerSize)
                android.util.Log.d("FloatingPlayer", "DOWN rawX=${event.rawX} rawY=${event.rawY} isResizing=$isResizing controls=${controlOverlay?.visibility}")
                true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                isTouchConsumedByScale = true
                isDragging = false
                isResizing = false
                true
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1 || scaleGestureDetector.isInProgress) {
                    isTouchConsumedByScale = true
                    isDragging = false
                    isResizing = false
                    return@OnTouchListener true
                }
                if (isTouchConsumedByScale) return@OnTouchListener true

                val dx = (event.rawX - touchDownRawX).toInt()
                val dy = (event.rawY - touchDownRawY).toInt()
                val dist = Math.sqrt((dx * dx + dy * dy).toDouble())

                if (!isDragging && dist > dpToPx(8)) {
                    isDragging = true
                    // Hide controls so the drag feels clean
                    // (user can tap again to bring them back)
                }

                val lp = floatingView?.layoutParams as? WindowManager.LayoutParams ?: return@OnTouchListener true

                if (isResizing) {
                    val dm = resources.displayMetrics
                    val maxW = (dm.widthPixels * 0.85f).toInt()
                    val maxH = (dm.heightPixels * 0.85f).toInt()
                    var newW = (touchDownWidth + dx).coerceIn(minWidth, maxW)
                    var newH = (newW / aspectRatio).toInt()
                    if (newH > maxH) {
                        newH = maxH
                        newW = (newH * aspectRatio).toInt()
                    }
                    lp.width = newW
                    lp.height = newH
                    safeUpdateLayout(lp)
                    FloatingPlayerManager.width = newW
                    FloatingPlayerManager.height = newH
                } else if (isDragging) {
                    val dm = resources.displayMetrics
                    val maxX = dm.widthPixels - lp.width
                    val maxY = dm.heightPixels - lp.height
                    lp.x = (touchDownWindowX + dx).coerceIn(0, maxX.coerceAtLeast(0))
                    lp.y = (touchDownWindowY + dy).coerceIn(0, maxY.coerceAtLeast(0))
                    safeUpdateLayout(lp)
                    FloatingPlayerManager.x = lp.x
                    FloatingPlayerManager.y = lp.y
                }
                true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dx = Math.abs(event.rawX - touchDownRawX)
                val dy = Math.abs(event.rawY - touchDownRawY)
                android.util.Log.d("FloatingPlayer", "UP dx=$dx dy=$dy isDragging=$isDragging isResizing=$isResizing scale=${scaleGestureDetector.isInProgress}")

                if (!isDragging && !isResizing && !isTouchConsumedByScale && dx < dpToPx(8) && dy < dpToPx(8)) {
                    val now = System.currentTimeMillis()
                    val timeSinceLast = now - lastTapTimeMs
                    val xDist = kotlin.math.abs(event.rawX - lastTapRawX)
                    if (timeSinceLast in 80..DOUBLE_TAP_MAX_MS && xDist < DOUBLE_TAP_SLOP_PX) {
                        // Double tap detected — seek forward/backward
                        val lp = floatingView?.layoutParams as? WindowManager.LayoutParams
                        val tapX = event.x
                        val halfW = (lp?.width ?: 1) / 2f
                        val seekDelta = if (tapX > halfW) SEEK_STEP_MS else -SEEK_STEP_MS
                        seekBy(seekDelta)
                        lastTapTimeMs = 0L  // reset so triple-tap doesn't re-trigger
                    } else {
                        // Single tap: toggle controls visibility
                        lastTapTimeMs = now
                        lastTapRawX = event.rawX
                        if (controlOverlay?.visibility != View.VISIBLE) {
                            showControls()
                        } else {
                            hideControls()
                        }
                    }
                }

                isDragging = false
                isResizing = false
                isTouchConsumedByScale = false
                true
            }

            else -> false
        }
    }

    private fun tapHitButton(rawX: Float, rawY: Float): Boolean {
        val overlay = controlOverlay ?: return false
        if (overlay.visibility != View.VISIBLE) return false
        // Walk all children of overlay and check bounds
        return viewGroupContainsTap(overlay, rawX.toInt(), rawY.toInt())
    }

    private fun viewGroupContainsTap(vg: ViewGroup, rawX: Int, rawY: Int): Boolean {
        val rect = android.graphics.Rect()
        for (i in 0 until vg.childCount) {
            val child = vg.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            child.getGlobalVisibleRect(rect)
            if (rect.contains(rawX, rawY)) {
                if (child is ViewGroup) {
                    if (viewGroupContainsTap(child, rawX, rawY)) return true
                }
                // If child has click listener, count it as a button hit
                if (child.hasOnClickListeners()) return true
            }
        }
        return false
    }

    private fun safeUpdateLayout(lp: WindowManager.LayoutParams) {
        try {
            floatingView?.let { windowManager.updateViewLayout(it, lp) }
        } catch (e: Exception) {
            android.util.Log.e("FloatingPlayer", "updateViewLayout failed: ${e.message}")
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun showControls() {
        controlOverlay?.visibility = View.VISIBLE
        resetControlsTimeout()
    }

    private fun hideControls() {
        controlOverlay?.visibility = View.GONE
        controlsTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
    }

    private fun setupControlOverlay() {
        controlOverlay = FrameLayout(this).apply {
            setBackgroundColor(AndroidColor.parseColor("#99000000"))
        }

        // Top bar (Expand icon + Title + Close button)
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
        }

        val expandBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_zoom)
            setBackgroundColor(AndroidColor.TRANSPARENT)
            setColorFilter(AndroidColor.WHITE)
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
            setOnClickListener { restoreToApp() }
        }
        topBar.addView(expandBtn, LinearLayout.LayoutParams(dpToPx(44), dpToPx(44)))

        titleText = TextView(this).apply {
            setTextColor(AndroidColor.WHITE)
            textSize = 12f
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxLines = 1
            gravity = Gravity.CENTER
        }
        topBar.addView(titleText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dpToPx(8)
            rightMargin = dpToPx(8)
        })

        val closeBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(AndroidColor.TRANSPARENT)
            setColorFilter(AndroidColor.WHITE)
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
            setOnClickListener { stopSelf() }
        }
        topBar.addView(closeBtn, LinearLayout.LayoutParams(dpToPx(44), dpToPx(44)))

        controlOverlay!!.addView(topBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP })

        // Middle controls (Prev, Play/Pause, Next)
        val midControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val prevBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_previous)
            setBackgroundColor(AndroidColor.TRANSPARENT)
            setColorFilter(AndroidColor.WHITE)
            setPadding(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(10))
            setOnClickListener { playPrevious() }
        }
        midControls.addView(prevBtn, LinearLayout.LayoutParams(dpToPx(52), dpToPx(52)))

        playPauseButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_pause)
            setBackgroundColor(AndroidColor.TRANSPARENT)
            setColorFilter(AndroidColor.WHITE)
            setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
            setOnClickListener { togglePlayPause() }
        }
        midControls.addView(playPauseButton, LinearLayout.LayoutParams(dpToPx(64), dpToPx(64)).apply {
            leftMargin = dpToPx(20)
            rightMargin = dpToPx(20)
        })

        val nextBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_next)
            setBackgroundColor(AndroidColor.TRANSPARENT)
            setColorFilter(AndroidColor.WHITE)
            setPadding(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(10))
            setOnClickListener { playNext() }
        }
        midControls.addView(nextBtn, LinearLayout.LayoutParams(dpToPx(52), dpToPx(52)))

        controlOverlay!!.addView(midControls, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER })

        // Bottom Progress Bar
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        controlOverlay!!.addView(progressBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dpToPx(4)
        ).apply { gravity = Gravity.BOTTOM })

        floatingView!!.addView(controlOverlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }

    private fun toggleControlsVisibility() {
        if (controlOverlay?.visibility == View.VISIBLE) hideControls() else showControls()
    }

    private fun resetControlsTimeout() {
        controlsTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        controlsTimeoutRunnable = Runnable {
            controlOverlay?.visibility = View.GONE
        }.also {
            mainHandler.postDelayed(it, 2000)
        }
    }

    private fun initializePlayer() {
        val currentItem = FloatingPlayerManager.playlist.getOrNull(FloatingPlayerManager.currentIndex) ?: return
        releasePlayer()
        titleText?.text = currentItem.displayName

        val oldAspectRatio = aspectRatio
        aspectRatio = getAspectRatio(currentItem.resolution)

        // Adjust layout dimensions to match aspect ratio safely, preserving the scale factor
        val lp = floatingView?.layoutParams as? WindowManager.LayoutParams
        if (lp != null) {
            val dm = resources.displayMetrics
            val maxW = (dm.widthPixels * 0.85f).toInt()
            val maxH = (dm.heightPixels * 0.85f).toInt()

            // 1. Calculate maxPossibleWidth for the old aspect ratio
            var oldMaxPossibleW = maxW
            var oldMaxPossibleH = (oldMaxPossibleW / oldAspectRatio).toInt()
            if (oldMaxPossibleH > maxH) {
                oldMaxPossibleH = maxH
                oldMaxPossibleW = (oldMaxPossibleH * oldAspectRatio).toInt()
            }

            // 2. Determine the current scale factor relative to the old max size
            val scale = (lp.width.toFloat() / oldMaxPossibleW).coerceIn(0f, 1f)

            // 3. Calculate maxPossibleWidth for the new aspect ratio
            var newMaxPossibleW = maxW
            var newMaxPossibleH = (newMaxPossibleW / aspectRatio).toInt()
            if (newMaxPossibleH > maxH) {
                newMaxPossibleH = maxH
                newMaxPossibleW = (newMaxPossibleH * aspectRatio).toInt()
            }

            // 4. Apply the scale to get new dimensions
            var targetWidth = (newMaxPossibleW * scale).toInt()
            var targetHeight = (targetWidth / aspectRatio).toInt()

            if (targetWidth < minWidth) {
                targetWidth = minWidth
                targetHeight = (targetWidth / aspectRatio).toInt()
            }
            lp.width = targetWidth
            lp.height = targetHeight
            
            // Adjust position if it goes offscreen
            val maxX = dm.widthPixels - lp.width
            val maxY = dm.heightPixels - lp.height
            if (lp.x > maxX) lp.x = maxX.coerceAtLeast(0)
            if (lp.y > maxY) lp.y = maxY.coerceAtLeast(0)
            
            safeUpdateLayout(lp)
            FloatingPlayerManager.width = lp.width
            FloatingPlayerManager.height = lp.height
            FloatingPlayerManager.x = lp.x
            FloatingPlayerManager.y = lp.y
        }

        playerContainer?.removeAllViews()

        if (FloatingPlayerManager.useVlcFallback) {
            initializeVlcPlayer(currentItem)
        } else {
            initializeExoPlayer(currentItem)
        }
        showControls()
    }

    private fun initializeExoPlayer(item: MediaItem) {
        val renderersFactory = DefaultRenderersFactory(applicationContext)
            .setEnableDecoderFallback(false)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        val is4k = item.resolution.lowercase(Locale.ROOT).contains("x") && item.resolution.split("x").mapNotNull { it.trim().toIntOrNull() }.let { parts -> parts.size >= 2 && (parts[0] >= 3500 || parts[1] >= 2000) }
        val isHugeFile = item.size > 8_000_000_000L
        val isLargeFile = item.size > 1_500_000_000L

        val (minBuffer, maxBuffer, playBuffer, rebufferBuffer) = when {
            isHugeFile || (is4k && item.size > 4_000_000_000L) -> quadruplet(50000, 120000, 2500, 5000)
            isLargeFile || is4k -> quadruplet(30000, 80000, 1500, 3000)
            else -> quadruplet(15000, 45000, 1000, 2000)
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBuffer, maxBuffer, playBuffer, rebufferBuffer)
            .build()

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val player = ExoPlayer.Builder(applicationContext)
            .setRenderersFactory(renderersFactory)
            .setAudioAttributes(audioAttrs, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true)
            .setLoadControl(loadControl)
            .setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
            .build()

        player.setMediaItem(ExoMediaItem.fromUri(item.uri))
        player.seekTo(FloatingPlayerManager.currentPosition)
        player.prepare()
        player.playWhenReady = true

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                mainHandler.post {
                    playPauseButton?.setImageResource(
                        if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
                    )
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    playNext()
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                mainHandler.post {
                    repository.setPreferredVlcDecoder(item, true)
                    FloatingPlayerManager.useVlcFallback = true
                    initializePlayer()
                }
            }
        })

        exoPlayerView = PlayerView(this).apply {
            useController = false
            this.player = player
        }

        playerContainer?.addView(exoPlayerView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        this.exoPlayer = player
    }

    private fun initializeVlcPlayer(item: MediaItem) {
        val libVlcInstance = LibVLC(
            applicationContext,
            arrayListOf(
                "--audio-time-stretch",
                "--network-caching=3000",
                "--file-caching=1000",
                "--live-caching=1500",
                "--codec=all",
                "--avcodec-hw=any"
            )
        )
        val player = VlcMediaPlayer(libVlcInstance)

        vlcVideoLayout = VLCVideoLayout(this)
        playerContainer?.addView(vlcVideoLayout, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        player.attachViews(vlcVideoLayout!!, null, true, false)

        val vlcMedia = if (item.path.isNotBlank()) {
            VlcMedia(libVlcInstance, item.path)
        } else {
            VlcMedia(libVlcInstance, item.uri)
        }
        vlcMedia.addOption(":start-time=${FloatingPlayerManager.currentPosition / 1000L}")
        player.media = vlcMedia
        vlcMedia.release()

        player.setEventListener { event ->
            when (event.type) {
                VlcMediaPlayer.Event.Playing -> {
                    mainHandler.post {
                        playPauseButton?.setImageResource(android.R.drawable.ic_media_pause)
                    }
                }
                VlcMediaPlayer.Event.Paused, VlcMediaPlayer.Event.Stopped -> {
                    mainHandler.post {
                        playPauseButton?.setImageResource(android.R.drawable.ic_media_play)
                    }
                }
                VlcMediaPlayer.Event.EndReached -> {
                    mainHandler.post { playNext() }
                }
                VlcMediaPlayer.Event.EncounteredError -> {
                    mainHandler.post {
                        android.widget.Toast.makeText(applicationContext, "播放出错，无法播放该视频", android.widget.Toast.LENGTH_SHORT).show()
                        stopSelf()
                    }
                }
            }
        }

        player.play()

        this.libVlc = libVlcInstance
        this.vlcMediaPlayer = player
    }

    private fun quadruplet(a: Int, b: Int, c: Int, d: Int): Quadruplet = Quadruplet(a, b, c, d)
    private data class Quadruplet(val minBuffer: Int, val maxBuffer: Int, val playBuffer: Int, val rebufferBuffer: Int)

    private fun seekBy(deltaMs: Long) {
        if (FloatingPlayerManager.useVlcFallback) {
            vlcMediaPlayer?.let { mp ->
                val newTime = (mp.time + deltaMs).coerceIn(0L, mp.length.coerceAtLeast(1L))
                mp.time = newTime
            }
        } else {
            exoPlayer?.let { p ->
                val newPos = (p.currentPosition + deltaMs).coerceIn(0L, p.duration.coerceAtLeast(1L))
                p.seekTo(newPos)
            }
        }
        // Brief visual feedback via progress bar flash handled by ticker
        resetControlsTimeout()
    }

    private fun togglePlayPause() {
        if (FloatingPlayerManager.useVlcFallback) {
            vlcMediaPlayer?.let {
                if (it.isPlaying) it.pause() else it.play()
            }
        } else {
            exoPlayer?.let {
                if (it.isPlaying) it.pause() else it.play()
            }
        }
        resetControlsTimeout()
    }

    private fun playNext() {
        saveProgress()
        if (FloatingPlayerManager.playlist.isNotEmpty()) {
            if (FloatingPlayerManager.playlist.size == 1) {
                FloatingPlayerManager.currentPosition = 0L
                if (FloatingPlayerManager.useVlcFallback) {
                    vlcMediaPlayer?.let {
                        it.setTime(0)
                        it.play()
                    }
                } else {
                    exoPlayer?.let {
                        it.seekTo(0)
                        it.play()
                    }
                }
            } else {
                FloatingPlayerManager.currentIndex = (FloatingPlayerManager.currentIndex + 1) % FloatingPlayerManager.playlist.size
                FloatingPlayerManager.currentPosition = 0L
                releasePlayer()
                initializePlayer()
            }
        }
    }

    private fun playPrevious() {
        saveProgress()
        if (FloatingPlayerManager.playlist.isNotEmpty()) {
            if (FloatingPlayerManager.playlist.size == 1) {
                FloatingPlayerManager.currentPosition = 0L
                if (FloatingPlayerManager.useVlcFallback) {
                    vlcMediaPlayer?.let {
                        it.setTime(0)
                        it.play()
                    }
                } else {
                    exoPlayer?.let {
                        it.seekTo(0)
                        it.play()
                    }
                }
            } else {
                FloatingPlayerManager.currentIndex = (FloatingPlayerManager.currentIndex - 1 + FloatingPlayerManager.playlist.size) % FloatingPlayerManager.playlist.size
                FloatingPlayerManager.currentPosition = 0L
                releasePlayer()
                initializePlayer()
            }
        }
    }

    private fun saveProgress() {
        val currentItem = FloatingPlayerManager.playlist.getOrNull(FloatingPlayerManager.currentIndex) ?: return
        val pos = getPlayerCurrentPosition()
        val dur = getPlayerDuration()
        if (pos >= 0L) {
            FloatingPlayerManager.currentPosition = pos
            repository.savePlaybackProgress(currentItem, pos, dur)
        }
    }

    private fun getPlayerCurrentPosition(): Long {
        return if (FloatingPlayerManager.useVlcFallback) {
            vlcMediaPlayer?.time ?: 0L
        } else {
            exoPlayer?.currentPosition ?: 0L
        }
    }

    private fun getPlayerDuration(): Long {
        return if (FloatingPlayerManager.useVlcFallback) {
            vlcMediaPlayer?.length ?: 0L
        } else {
            exoPlayer?.duration ?: 0L
        }
    }

    private fun restoreToApp() {
        saveProgress()
        FloatingPlayerManager.isFloating = true

        val restoreIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("EXTRA_RESTORE_FLOATING", true)
        }
        startActivity(restoreIntent)
        stopSelf()
    }

    private fun startProgressTicker() {
        progressTicker = object : Runnable {
            override fun run() {
                val pos = getPlayerCurrentPosition()
                val dur = getPlayerDuration()
                if (dur > 0) {
                    progressBar?.progress = ((pos * 100) / dur).toInt()
                }
                FloatingPlayerManager.currentPosition = pos
                mainHandler.postDelayed(this, 500)
            }
        }.also {
            mainHandler.postDelayed(it, 500)
        }
    }

    private fun stopProgressTicker() {
        progressTicker?.let { mainHandler.removeCallbacks(it) }
        progressTicker = null
    }

    private fun releasePlayer() {
        val exo = exoPlayer
        exoPlayer = null
        exoPlayerView = null

        val vlcMp = vlcMediaPlayer
        vlcMediaPlayer = null
        vlcVideoLayout = null
        val vlcInst = libVlc
        libVlc = null

        exo?.let {
            try {
                it.stop()
                it.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (vlcMp != null || vlcInst != null) {
            // detachViews must be called on the main thread
            try {
                vlcMp?.detachViews()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            Thread {
                try {
                    vlcMp?.let {
                        it.stop()
                        it.release()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                try {
                    vlcInst?.release()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }.start()
        }
    }

    private fun getAspectRatio(resolution: String): Float {
        // Prefer actual video dimensions stored in FloatingPlayerManager (set when launching)
        val vw = FloatingPlayerManager.videoWidth
        val vh = FloatingPlayerManager.videoHeight
        if (vw > 0 && vh > 0) return vw.toFloat() / vh.toFloat()
        // Fallback: parse resolution string e.g. "1920x1080"
        if (resolution.contains("x")) {
            val parts = resolution.split("x")
            val w = parts.getOrNull(0)?.toFloatOrNull() ?: 0f
            val h = parts.getOrNull(1)?.toFloatOrNull() ?: 0f
            if (w > 0 && h > 0) return w / h
        }
        return 16f / 9f
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        android.util.Log.d("FloatingPlayer", "FloatingPlayerService onDestroy")
        saveProgress()
        stopProgressTicker()
        releasePlayer()
        controlsTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        floatingView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) { e.printStackTrace() }
            floatingView = null
        }
        FloatingPlayerManager.isFloating = false
        MainActivity.isVideoPlaying = false
        super.onDestroy()
    }
}
