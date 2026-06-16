package com.example.videoplayer.ui.components

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.WindowManager
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.videoplayer.ui.theme.GlassBlack80
import com.example.videoplayer.ui.theme.SecondaryNeonCyan
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun PlayerGestureOverlay(
    modifier: Modifier = Modifier,
    seekSeconds: Int = 10,
    durationMs: Long = 0L,
    currentPositionMs: Long = 0L,
    isLocked: Boolean = false,
    isEnabled: Boolean = true,
    onSingleTap: () -> Unit,
    onCenterDoubleTap: () -> Unit,
    onDoubleTapSeek: (Long) -> Unit, // seek delta in ms (+10000 or -10000)
    onScrub: (Long) -> Unit = {},
    onScrubFinished: (Long) -> Unit = {},
    onLongPressSpeed: (Boolean) -> Unit, // true for 2x, false for normal
    onSwipeDrag: (Float) -> Unit = {},
    onSwipeRelease: (Float) -> Unit = {},
    onPullDownDrag: (Float) -> Unit = {},    // 下拉位移（px）
    onPullDownRelease: (Float) -> Unit = {}, // 释放时传入最终位移
    content: @Composable BoxScope.() -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Audio Manager for volume adjust
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }

    // Gesture States
    var showIndicator by remember { mutableStateOf(false) }
    var indicatorIcon by remember { mutableStateOf(Icons.AutoMirrored.Filled.VolumeUp) }
    var indicatorText by remember { mutableStateOf("") }

    var showSpeedIndicator by remember { mutableStateOf(false) }
    var isLongPressActive by remember { mutableStateOf(false) }

    // Pull-down indicator
    var showPullDownHint by remember { mutableStateOf(false) }

    // Hide gesture indicators after some time
    var indicatorJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun triggerIndicator(icon: ImageVector, text: String) {
        indicatorIcon = icon
        indicatorText = text
        showIndicator = true
        indicatorJob?.cancel()
        indicatorJob = scope.launch {
            delay(1000)
            showIndicator = false
        }
    }

    val currentIsLocked by rememberUpdatedState(isLocked)
    val currentSeekSeconds by rememberUpdatedState(seekSeconds)
    val currentDurationMs by rememberUpdatedState(durationMs)
    val stableCurrentPositionMs by rememberUpdatedState(currentPositionMs)
    val currentOnSingleTap by rememberUpdatedState(onSingleTap)
    val currentOnCenterDoubleTap by rememberUpdatedState(onCenterDoubleTap)
    val currentOnDoubleTapSeek by rememberUpdatedState(onDoubleTapSeek)
    val currentOnScrub by rememberUpdatedState(onScrub)
    val currentOnScrubFinished by rememberUpdatedState(onScrubFinished)
    val currentOnLongPressSpeed by rememberUpdatedState(onLongPressSpeed)
    val currentOnSwipeDrag by rememberUpdatedState(onSwipeDrag)
    val currentOnSwipeRelease by rememberUpdatedState(onSwipeRelease)
    val currentOnPullDownDrag by rememberUpdatedState(onPullDownDrag)
    val currentOnPullDownRelease by rememberUpdatedState(onPullDownRelease)

    Box(
        modifier = modifier
            .fillMaxSize()
            // ── 1. 单击 / 双击 / 长按（锁屏时仍响应单击解锁）──
            .pointerInput(isEnabled) {
                if (!isEnabled) return@pointerInput
                detectTapGestures(
                    onTap = { currentOnSingleTap() },
                    onDoubleTap = { offset ->
                        if (!currentIsLocked) {
                            val screenWidth = size.width
                            val tapX = offset.x
                            if (tapX < screenWidth * 0.25f) {
                                currentOnDoubleTapSeek(-currentSeekSeconds * 1000L)
                                triggerIndicator(Icons.Default.Replay10, "-${currentSeekSeconds}s")
                            } else if (tapX > screenWidth * 0.75f) {
                                currentOnDoubleTapSeek(currentSeekSeconds * 1000L)
                                triggerIndicator(Icons.Default.Forward10, "+${currentSeekSeconds}s")
                            } else {
                                currentOnCenterDoubleTap()
                            }
                        }
                    },
                    onLongPress = {
                        if (!currentIsLocked) {
                            isLongPressActive = true
                            showSpeedIndicator = true
                            currentOnLongPressSpeed(true)
                        }
                    }
                )
            }
            // ── 2. 长按抬手还原倍速 ──
            .pointerInput(isEnabled) {
                if (!isEnabled) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (isLongPressActive && event.changes.all { !it.pressed }) {
                            isLongPressActive = false
                            showSpeedIndicator = false
                            currentOnLongPressSpeed(false)
                        }
                    }
                }
            }
            // ── 3. 拖拽（进度条 / 亮度 / 音量 / 切集 / 下拉关闭）──
            .pointerInput(isEnabled) {
                if (!isEnabled) return@pointerInput
                val density = context.resources.displayMetrics.density
                // 优化：水平判定阈值从 24dp 降至 16dp，让切换更灵敏
                val horizThreshold = 16f * density
                // 竖直判定阈值保持 18dp
                val vertThreshold = 18f * density
                var totalDragX = 0f
                var totalDragY = 0f
                var startX = 0f
                var startY = 0f
                var scrubTarget = 0L
                var volumeAccumulator = 0f
                // dragType:
                //   0 = 未确定
                //   1 = 水平切换视频
                //   2 = 亮度（左侧竖滑）
                //   3 = 音量（右侧竖滑）
                //   4 = 进度条拖拽（下半部水平）
                //   5 = 下拉关闭（左上角区域下滑）
                //  -1 = 中间竖滑，忽略
                var dragType = 0
                var gestureBrightness = -1f

                detectDragGestures(
                    onDragStart = { offset ->
                        if (!currentIsLocked) {
                            totalDragX = 0f
                            totalDragY = 0f
                            startX = offset.x
                            startY = offset.y
                            scrubTarget = stableCurrentPositionMs
                            volumeAccumulator = 0f
                            dragType = 0
                            showPullDownHint = false

                            val activity = findActivity(context)
                            val lp = activity?.window?.attributes
                            val currentBrightness = lp?.screenBrightness ?: -1f
                            gestureBrightness = if (currentBrightness < 0f) {
                                runCatching {
                                    android.provider.Settings.System.getInt(
                                        context.contentResolver,
                                        android.provider.Settings.System.SCREEN_BRIGHTNESS
                                    ) / 255f
                                }.getOrDefault(0.5f)
                            } else {
                                currentBrightness
                            }
                        }
                    },
                    onDragEnd = {
                        if (!currentIsLocked) {
                            when (dragType) {
                                1 -> currentOnSwipeRelease(totalDragX)
                                4 -> currentOnScrubFinished(scrubTarget)
                                5 -> {
                                    showPullDownHint = false
                                    currentOnPullDownRelease(totalDragY)
                                }
                            }
                        }
                    },
                    onDragCancel = {
                        if (!currentIsLocked) {
                            when (dragType) {
                                1 -> currentOnSwipeRelease(0f)
                                5 -> {
                                    showPullDownHint = false
                                    currentOnPullDownRelease(0f)
                                }
                            }
                        }
                    },
                    onDrag = { change, dragAmount ->
                        if (!currentIsLocked) {
                            change.consume()
                            totalDragX += dragAmount.x
                            totalDragY += dragAmount.y

                            val screenWidth = size.width
                            val screenHeight = size.height

                            if (dragType == 0) {
                                val absDx = abs(totalDragX)
                                val absDy = abs(totalDragY)

                                // ── 优先判断下拉关闭：左上角25%宽度，向下滑，且纵向位移 > 横向位移 ──
                                if (startX < screenWidth * 0.25f
                                    && totalDragY > 0f
                                    && absDy > absDx
                                    && absDy > vertThreshold
                                ) {
                                    dragType = 5
                                    showPullDownHint = true
                                }
                                // ── 水平切换视频（上半屏）或进度条拖拽（下半屏）──
                                else if (absDx > absDy && absDx > horizThreshold) {
                                    // 下半部(>35%)水平滑 = 进度条；否则 = 切换视频
                                    dragType = if (startY > screenHeight * 0.35f && currentDurationMs > 0L) 4 else 1
                                    if (dragType == 4) {
                                        scrubTarget = ((change.position.x / screenWidth.toFloat()).coerceIn(0f, 1f) * currentDurationMs).toLong()
                                        currentOnScrub(scrubTarget)
                                        triggerIndicator(Icons.Default.SlowMotionVideo, formatGestureDuration(scrubTarget))
                                    }
                                }
                                // ── 竖直滑动：左侧=亮度，右侧=音量，中间=忽略 ──
                                else if (absDy > absDx && absDy > vertThreshold) {
                                    dragType = when {
                                        startX < screenWidth * 0.25f -> 2
                                        startX > screenWidth * 0.75f -> 3
                                        else -> -1
                                    }
                                }
                            }

                            when (dragType) {
                                1 -> currentOnSwipeDrag(totalDragX)

                                2 -> {
                                    // 亮度调节：直接累积，避免每帧读 window.attributes
                                    val delta = -dragAmount.y / screenHeight.toFloat()
                                    gestureBrightness = (gestureBrightness + delta).coerceIn(0.01f, 1f)
                                    val activity = findActivity(context)
                                    activity?.let {
                                        val layoutParams = it.window.attributes
                                        layoutParams.screenBrightness = gestureBrightness
                                        it.window.attributes = layoutParams
                                        val pct = (gestureBrightness * 100).toInt()
                                        triggerIndicator(
                                            icon = when {
                                                pct < 30 -> Icons.Default.BrightnessLow
                                                pct < 70 -> Icons.Default.BrightnessMedium
                                                else -> Icons.Default.BrightnessHigh
                                            },
                                            text = "$pct%"
                                        )
                                    }
                                }

                                3 -> {
                                    // 音量调节：累积积分确保整数步长，避免跳帧
                                    val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                    volumeAccumulator += (-dragAmount.y / screenHeight.toFloat()) * maxVolume.toFloat()
                                    val actualDelta = volumeAccumulator.toInt()
                                    if (actualDelta != 0) {
                                        volumeAccumulator -= actualDelta.toFloat()
                                        val newVol = (currentVol + actualDelta).coerceIn(0, maxVolume)
                                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                                        val pct = (newVol.toFloat() / maxVolume * 100).toInt()
                                        triggerIndicator(
                                            icon = if (newVol == 0) Icons.AutoMirrored.Filled.VolumeMute
                                                   else if (pct < 50) Icons.AutoMirrored.Filled.VolumeDown
                                                   else Icons.AutoMirrored.Filled.VolumeUp,
                                            text = "$pct%"
                                        )
                                    }
                                }

                                4 -> {
                                    scrubTarget = ((change.position.x / screenWidth.toFloat()).coerceIn(0f, 1f) * currentDurationMs).toLong()
                                    currentOnScrub(scrubTarget)
                                    triggerIndicator(Icons.Default.SlowMotionVideo, formatGestureDuration(scrubTarget))
                                }

                                5 -> {
                                    // 下拉关闭：只传递正向（向下）位移
                                    val clamped = totalDragY.coerceAtLeast(0f)
                                    currentOnPullDownDrag(clamped)
                                }
                            }
                        }
                    }
                )
            }
    ) {

        // App Content beneath gesture layers
        content()

        // 下拉关闭提示箭头
        AnimatedVisibility(
            visible = showPullDownHint,
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it / 2 }),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("下滑关闭", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }

        // Volume / Brightness/Seek indicator Badge
        AnimatedVisibility(
            visible = showIndicator,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(GlassBlack80)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = indicatorIcon,
                    contentDescription = null,
                    tint = SecondaryNeonCyan,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = indicatorText,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                // Parse percentage for volume or brightness
                val pct = indicatorText.removeSuffix("%").toIntOrNull()
                if (pct != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { pct / 100f },
                        modifier = Modifier
                            .width(80.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = SecondaryNeonCyan,
                        trackColor = Color.White.copy(alpha = 0.2f)
                    )
                }
            }
        }

        // Long Press Speed indicator Badge
        AnimatedVisibility(
            visible = showSpeedIndicator,
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 40.dp)
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF8B5CF6).copy(alpha = 0.9f))
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.FastForward,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "长按 2.0x 倍速播放中",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

private fun findActivity(context: Context): Activity? {
    var ctx = context
    while (ctx is android.content.ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

private fun formatGestureDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
}
