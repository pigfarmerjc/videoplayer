package com.example.videoplayer.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.videoplayer.data.model.MediaItem as PlayerMediaItem
import com.example.videoplayer.data.repository.MediaRepository
import com.example.videoplayer.ui.components.mediaPreviewRequest
import com.example.videoplayer.ui.theme.ObsidianBg
import com.example.videoplayer.ui.theme.SecondaryNeonCyan
import com.example.videoplayer.ui.components.bounceClick
import androidx.compose.foundation.ExperimentalFoundationApi
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PhotoViewerScreen(
    itemId: Long,
    folderName: String,
    mediaItems: List<PlayerMediaItem>,
    repository: MediaRepository,
    onBackClick: () -> Unit
) {
    val playlist = remember(folderName, mediaItems) {
        val resolved = repository.resolveFolderItems(folderName, mediaItems)
        // Only filter if the resolved list might contain non-photo items
        if (folderName == MediaRepository.ALL_PHOTOS) {
            resolved
        } else {
            resolved.filter { it.type == com.example.videoplayer.data.model.MediaType.PHOTO }
        }
    }

    val startIndex = remember(playlist, itemId) {
        playlist.indexOfFirst { it.id == itemId }.coerceAtLeast(0)
    }

    val pagerState = rememberPagerState(
        initialPage = startIndex,
        pageCount = { playlist.size }
    )

    val currentIndex = pagerState.currentPage
    val currentPhoto = playlist.getOrNull(currentIndex)

    if (currentPhoto == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("未找到该图片", color = Color.White)
        }
        return
    }

    var showBars by remember { mutableStateOf(true) }
    var isZoomed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var currentSwipeOffset by remember { mutableStateOf(0f) }

    fun showPrevious() {
        if (pagerState.currentPage > 0) {
            scope.launch {
                pagerState.animateScrollToPage(pagerState.currentPage - 1)
            }
        }
    }

    fun showNext() {
        if (pagerState.currentPage < playlist.size - 1) {
            scope.launch {
                pagerState.animateScrollToPage(pagerState.currentPage + 1)
            }
        }
    }

    val bgAlpha = (1f - kotlin.math.abs(currentSwipeOffset) / 1000f).coerceIn(0f, 1f)

    Scaffold(
        topBar = {
            AnimatedVisibility(
                visible = showBars && kotlin.math.abs(currentSwipeOffset) < 50f,
                enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { -it })
            ) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = currentPhoto.displayName,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${currentIndex + 1} / ${playlist.size}",
                                color = SecondaryNeonCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    },
                    navigationIcon = {
                        Box(
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .size(40.dp)
                                .bounceClick { onBackClick() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White, modifier = Modifier.size(24.dp))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = ObsidianBg.copy(alpha = 0.9f))
                )
            }
        },
        containerColor = Color.Transparent
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.Black.copy(alpha = bgAlpha))
        ) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = !isZoomed && kotlin.math.abs(currentSwipeOffset) < 1f,
                modifier = Modifier.fillMaxSize(),
                pageSpacing = 16.dp
            ) { page ->
                val photo = playlist[page]
                ZoomablePhoto(
                    photo = photo,
                    onTap = { showBars = !showBars },
                    onZoomChanged = { zoomed ->
                        if (page == pagerState.currentPage) {
                            isZoomed = zoomed
                        }
                    },
                    onSwipeOffsetChanged = { offset ->
                        if (page == pagerState.currentPage) {
                            currentSwipeOffset = offset
                        }
                    },
                    onDismiss = onBackClick,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Next / Prev Quick Floating Swipe Buttons (visible only when not zoomed in)
            if (!isZoomed && currentSwipeOffset == 0f) {
                // Prev Arrow
                if (currentIndex > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 12.dp)
                            .size(44.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(Color.Black.copy(alpha = 0.5f))
                            .bounceClick { showPrevious() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBackIosNew,
                            contentDescription = "上一张",
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Next Arrow
                if (currentIndex < playlist.size - 1) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 12.dp)
                            .size(44.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(Color.Black.copy(alpha = 0.5f))
                            .bounceClick { showNext() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                            contentDescription = "下一张",
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomablePhoto(
    photo: PlayerMediaItem,
    onTap: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
    onSwipeOffsetChanged: (Float) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    val animateOffsetY = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = rememberCoroutineScope()
    val latestOnSwipeOffsetChanged by rememberUpdatedState(onSwipeOffsetChanged)

    // Reset zoom when photo changes
    LaunchedEffect(photo) {
        scale = 1f
        offset = Offset.Zero
        dragOffsetY = 0f
        animateOffsetY.snapTo(0f)
        onZoomChanged(false)
    }

    val currentOffsetY = if (dragOffsetY != 0f) dragOffsetY else animateOffsetY.value

    LaunchedEffect(Unit) {
        snapshotFlow {
            if (dragOffsetY != 0f) dragOffsetY else animateOffsetY.value
        }.collect { offsetY ->
            latestOnSwipeOffsetChanged(offsetY)
        }
    }

    val swipeScale = (1f - kotlin.math.abs(currentOffsetY) / 1500f).coerceIn(0.7f, 1f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(photo) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown()
                        var isDraggingY = false
                        var dragStarted = false
                        val startPosition = down.position
                        
                        scope.launch {
                            animateOffsetY.snapTo(0f)
                        }

                        do {
                            val event = awaitPointerEvent()
                            val canceled = event.changes.any { it.isConsumed }
                            if (!canceled) {
                                val pointerCount = event.changes.size
                                if (pointerCount >= 2) {
                                    val zoomChange = event.calculateZoom()
                                    val panChange = event.calculatePan()
                                    val newScale = (scale * zoomChange).coerceIn(1f, 5f)
                                    if (newScale != scale) {
                                        scale = newScale
                                        onZoomChanged(newScale > 1f)
                                    }
                                    if (scale > 1f) {
                                        val maxX = (scale - 1) * size.width / 2f
                                        val maxY = (scale - 1) * size.height / 2f
                                        val newOffset = offset + panChange
                                        offset = Offset(
                                            x = newOffset.x.coerceIn(-maxX, maxX),
                                            y = newOffset.y.coerceIn(-maxY, maxY)
                                        )
                                    }
                                    event.changes.forEach { if (it.previousPosition != it.position) it.consume() }
                                } else if (pointerCount == 1) {
                                    val change = event.changes.first()
                                    if (change.pressed) {
                                        val dragAmount = change.position - change.previousPosition
                                        if (!dragStarted) {
                                            val distance = change.position - startPosition
                                            if (distance.getDistance() > viewConfiguration.touchSlop) {
                                                dragStarted = true
                                                if (scale == 1f) {
                                                    if (distance.y > 0f && distance.y > kotlin.math.abs(distance.x) * 1.5f) {
                                                        isDraggingY = true
                                                    } else {
                                                        break
                                                    }
                                                }
                                            }
                                        }
                                        if (dragStarted) {
                                            if (scale > 1f) {
                                                val maxX = (scale - 1) * size.width / 2f
                                                val maxY = (scale - 1) * size.height / 2f
                                                val newOffset = offset + dragAmount
                                                offset = Offset(
                                                    x = newOffset.x.coerceIn(-maxX, maxX),
                                                    y = newOffset.y.coerceIn(-maxY, maxY)
                                                )
                                                change.consume()
                                            } else if (isDraggingY) {
                                                dragOffsetY += dragAmount.y
                                                change.consume()
                                            }
                                        }
                                    }
                                }
                            }
                        } while (event.changes.any { it.pressed })

                        if (scale == 1f && isDraggingY) {
                            scope.launch {
                                if (kotlin.math.abs(dragOffsetY) > 240f) {
                                    onDismiss()
                                } else {
                                    animateOffsetY.snapTo(dragOffsetY)
                                    dragOffsetY = 0f
                                    animateOffsetY.animateTo(
                                        targetValue = 0f,
                                        animationSpec = androidx.compose.animation.core.spring(
                                            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                                            stiffness = androidx.compose.animation.core.Spring.StiffnessLow
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
            .pointerInput(photo) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { _ ->
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                            onZoomChanged(false)
                        } else {
                            scale = 2.5f
                            onZoomChanged(true)
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        coil.compose.AsyncImage(
            model = mediaPreviewRequest(photo),
            contentDescription = photo.displayName,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale * swipeScale,
                    scaleY = scale * swipeScale,
                    translationX = offset.x,
                    translationY = offset.y + currentOffsetY
                ),
            contentScale = ContentScale.Fit
        )
    }
}
