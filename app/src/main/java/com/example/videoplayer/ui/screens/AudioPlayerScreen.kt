package com.example.videoplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.example.videoplayer.data.model.MediaItem as PlayerMediaItem
import com.example.videoplayer.data.model.MediaType
import com.example.videoplayer.data.repository.MediaRepository
import com.example.videoplayer.ui.components.formatDuration
import com.example.videoplayer.ui.theme.*
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioPlayerScreen(
    itemId: Long,
    folderName: String,
    mediaItems: List<PlayerMediaItem>,
    repository: MediaRepository,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val playlist = remember(folderName, mediaItems) {
        repository.resolveFolderItems(folderName, mediaItems).filter { it.type == MediaType.AUDIO }
    }
    var currentIndex by remember { mutableIntStateOf(playlist.indexOfFirst { it.id == itemId }.coerceAtLeast(0)) }
    val currentAudio = playlist.getOrNull(currentIndex)

    if (currentAudio == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("未找到该音频", color = Color.White)
        }
        return
    }

    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }

    // Rotation angle for vinyl record
    var rotationAngle by remember { mutableStateOf(0f) }
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            val startTime = System.currentTimeMillis()
            val startAngle = rotationAngle
            while (isPlaying) {
                val elapsed = System.currentTimeMillis() - startTime
                rotationAngle = (startAngle + (elapsed * 0.024f)) % 360f
                delay(16L) // ~60fps
            }
        }
    }

    // Sleep Timer state
    var sleepTimerSecondsLeft by remember { mutableIntStateOf(0) }
    LaunchedEffect(sleepTimerSecondsLeft, isPlaying) {
        if (sleepTimerSecondsLeft > 0 && isPlaying) {
            while (sleepTimerSecondsLeft > 0 && isPlaying) {
                delay(1000L)
                if (sleepTimerSecondsLeft > 0) {
                    sleepTimerSecondsLeft--
                }
            }
            if (sleepTimerSecondsLeft == 0) {
                player?.pause()
            }
        }
    }

    var showMenu by remember { mutableStateOf(false) }
    var showTimerDialog by remember { mutableStateOf(false) }

    fun playNext() {
        repository.savePlaybackProgress(currentAudio, currentPosition, duration)
        if (currentIndex < playlist.lastIndex) {
            currentIndex++
        }
    }

    fun playPrev() {
        repository.savePlaybackProgress(currentAudio, currentPosition, duration)
        if (currentIndex > 0) {
            currentIndex--
        }
    }

    LaunchedEffect(currentAudio) {
        repository.markLastViewed(folderName, currentAudio)
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        val exoPlayer = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(currentAudio.uri))
                prepare()
                val savedPosition = repository.getPlaybackPosition(currentAudio)
                if (savedPosition > 0L && (currentAudio.duration <= 0L || savedPosition < currentAudio.duration * 0.98f)) {
                    seekTo(savedPosition)
                    currentPosition = savedPosition
                }
                playWhenReady = true
            }
        player = exoPlayer
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) duration = exoPlayer.duration.coerceAtLeast(0L)
                if (state == Player.STATE_ENDED) playNext()
            }
        }
        exoPlayer.addListener(listener)
        try {
            var lastProgressSaveAtMs = 0L
            while (true) {
                currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
                if (duration <= 0) duration = exoPlayer.duration.coerceAtLeast(0L)
                val now = System.currentTimeMillis()
                if (now - lastProgressSaveAtMs >= 2000L) {
                    repository.savePlaybackProgress(currentAudio, currentPosition, duration)
                    lastProgressSaveAtMs = now
                }
                delay(500)
            }
        } finally {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    DisposableEffect(Unit) {
        onDispose { player?.release() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
    ) {
        // Glowing Neon Background Circle 1
        Box(
            modifier = Modifier
                .size(320.dp)
                .align(Alignment.TopStart)
                .offset(x = (-80).dp, y = (-80).dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(PrimaryNeonPurple.copy(alpha = 0.18f), Color.Transparent)
                    )
                )
        )
        // Glowing Neon Background Circle 2
        Box(
            modifier = Modifier
                .size(360.dp)
                .align(Alignment.BottomEnd)
                .offset(x = 80.dp, y = 80.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(SecondaryNeonCyan.copy(alpha = 0.12f), Color.Transparent)
                    )
                )
        )

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("音频播放", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
                        }
                    },
                    actions = {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多", tint = Color.White)
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            modifier = Modifier.background(CardObsidian)
                        ) {
                            DropdownMenuItem(
                                text = { Text("睡眠定时", color = Color.White) },
                                onClick = {
                                    showMenu = false
                                    showTimerDialog = true
                                }
                            )
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
                )
            },
            containerColor = Color.Transparent
        ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(Modifier.height(8.dp))
            // Vinyl Record Disc
            Box(
                modifier = Modifier
                    .size(250.dp)
                    .graphicsLayer(rotationZ = rotationAngle)
                    .clip(CircleShape)
                    .background(Color(0xFF0F0F13)) // Dark vinyl base
                    .border(3.dp, Color(0xFF1E1E24), CircleShape)
                    .border(24.dp, Color(0xFF131317), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                // Vinyl grooves (subtle concentric border rings)
                Box(
                    modifier = Modifier
                        .fillMaxSize(0.9f)
                        .border(1.dp, Color.White.copy(alpha = 0.03f), CircleShape)
                        .border(12.dp, Color.White.copy(alpha = 0.01f), CircleShape)
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize(0.75f)
                        .border(1.5.dp, Color.White.copy(alpha = 0.04f), CircleShape)
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize(0.6f)
                        .clip(CircleShape)
                        .background(
                            Brush.sweepGradient(
                                listOf(PrimaryNeonPurple, SecondaryNeonCyan, PrimaryNeonPurple)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(42.dp)
                    )
                }
                // Spindle hole
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(ObsidianBg)
                        .border(1.5.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(currentAudio.displayName, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Text(currentAudio.artist.ifBlank { "Unknown Artist" }, color = SecondaryNeonCyan, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sleepTimerSecondsLeft > 0) {
                    Spacer(Modifier.height(8.dp))
                    val mins = sleepTimerSecondsLeft / 60
                    val secs = sleepTimerSecondsLeft % 60
                    val timerStr = "${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}"
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = "睡眠定时",
                            tint = AccentPink,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "睡眠定时: $timerStr",
                            color = AccentPink,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Column(Modifier.fillMaxWidth()) {
                var isDragging by remember { mutableStateOf(false) }
                var localProgress by remember { mutableFloatStateOf(0f) }
                LaunchedEffect(currentPosition) {
                    if (!isDragging) {
                        localProgress = currentPosition.toFloat()
                    }
                }
                Slider(
                    value = localProgress.coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
                    onValueChange = {
                        isDragging = true
                        localProgress = it
                    },
                    onValueChangeFinished = {
                        player?.seekTo(localProgress.toLong())
                        currentPosition = localProgress.toLong()
                        isDragging = false
                    },
                    valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                    colors = SliderDefaults.colors(thumbColor = SecondaryNeonCyan, activeTrackColor = SecondaryNeonCyan)
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatDuration(localProgress.toLong()), color = TextSecondary, fontSize = 11.sp)
                    Text(formatDuration(duration), color = TextSecondary, fontSize = 11.sp)
                }
            }

            Row(Modifier.fillMaxWidth().padding(bottom = 30.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { playPrev() }, enabled = currentIndex > 0) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "上一首", tint = if (currentIndex > 0) Color.White else Color.Gray)
                }
                IconButton(
                    onClick = { player?.let { if (it.isPlaying) it.pause() else it.play() } },
                    modifier = Modifier.size(68.dp).clip(RoundedCornerShape(34.dp)).background(PrimaryNeonPurple)
                ) {
                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "播放", tint = Color.White, modifier = Modifier.size(36.dp))
                }
                IconButton(onClick = { playNext() }, enabled = currentIndex < playlist.lastIndex) {
                    Icon(Icons.Default.SkipNext, contentDescription = "下一首", tint = if (currentIndex < playlist.lastIndex) Color.White else Color.Gray)
                }
            }
        }
    }
}

    if (showTimerDialog) {
        AlertDialog(
            onDismissRequest = { showTimerDialog = false },
            title = { Text("睡眠定时", color = Color.White, fontWeight = FontWeight.Bold) },
            containerColor = CardObsidian,
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val options = listOf(
                        "不开启定时" to 0,
                        "10 分钟" to 10,
                        "20 分钟" to 20,
                        "30 分钟" to 30,
                        "60 分钟" to 60
                    )
                    options.forEach { (label, mins) ->
                        TextButton(
                            onClick = {
                                sleepTimerSecondsLeft = mins * 60
                                showTimerDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (mins == 0) label else "$label (${mins}分钟)",
                                color = if (sleepTimerSecondsLeft == mins * 60) SecondaryNeonCyan else Color.White,
                                fontWeight = if (sleepTimerSecondsLeft == mins * 60) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTimerDialog = false }) {
                    Text("取消", color = TextSecondary)
                }
            },
            shape = RoundedCornerShape(18.dp)
        )
    }
}
