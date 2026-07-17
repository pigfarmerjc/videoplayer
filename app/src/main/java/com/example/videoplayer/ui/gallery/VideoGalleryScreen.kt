package com.example.videoplayer.ui.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.example.videoplayer.data.model.MediaItem
import com.example.videoplayer.ui.components.formatDuration
import com.example.videoplayer.ui.theme.GalleryBackground
import com.example.videoplayer.ui.theme.GalleryIceBlue
import com.example.videoplayer.ui.theme.GalleryRaisedSurface
import com.example.videoplayer.ui.theme.GalleryText
import com.example.videoplayer.ui.theme.GalleryTextMuted
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun VideoGalleryScreen(
    videos: List<MediaItem>,
    playbackProgress: Map<String, Float>,
    columnCount: Int,
    onColumnsChange: (Int) -> Unit,
    onNavigateToVideo: (Long, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val galleryVideos = remember(videos) { videos.map(::GalleryVideo) }
    val sections = remember(galleryVideos) {
        groupVideos(galleryVideos, ZoneId.systemDefault(), Instant.now())
    }
    val continueWatching = remember(galleryVideos, playbackProgress) {
        deriveContinueWatching(galleryVideos, playbackProgress)
    }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var selectedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    val sectionStarts = remember(sections) {
        buildList {
            var itemIndex = 0
            sections.forEach { section ->
                add(itemIndex)
                itemIndex += section.items.size + 1
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(GalleryBackground)
            .semantics { testTagsAsResourceId = true }
            .testTag("video-gallery")
    ) {
        Column(Modifier.fillMaxSize()) {
            if (continueWatching.isNotEmpty()) {
                ContinueWatchingRow(
                    items = continueWatching,
                    onVideoClick = { item -> onNavigateToVideo(item.id, item.folderName) }
                )
            }
            Box(Modifier.weight(1f)) {
                VideoGalleryGrid(
                    sections = sections,
                    columnCount = columnCount,
                    selectedKeys = selectedKeys,
                    progress = playbackProgress,
                    state = gridState,
                    onColumnsChange = onColumnsChange,
                    onVideoClick = { item ->
                        if (selectedKeys.isEmpty()) {
                            onNavigateToVideo(item.id, item.folderName)
                        } else {
                            selectedKeys = selectedKeys.toggle(item.storageKey)
                        }
                    },
                    onVideoLongClick = { item ->
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        selectedKeys = selectedKeys.toggle(item.storageKey)
                    },
                    modifier = Modifier.fillMaxSize()
                )

                TimelineScrubber(
                    sections = sections,
                    activeSectionIndex = sectionStarts.indexOfLast {
                        it <= gridState.firstVisibleItemIndex
                    }.coerceAtLeast(0),
                    isScrolling = gridState.isScrollInProgress,
                    onSectionSelected = { sectionIndex ->
                        scope.launch {
                            gridState.scrollToItem(sectionStarts.getOrElse(sectionIndex) { 0 })
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 5.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = selectedKeys.isNotEmpty(),
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            SelectionActionSurface(
                selectedCount = selectedKeys.size,
                onClear = { selectedKeys = emptySet() }
            )
        }
    }
}

@Composable
private fun ContinueWatchingRow(
    items: List<ContinueWatchingVideo<GalleryVideo>>,
    onVideoClick: (MediaItem) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .testTag("continue-watching")
    ) {
        Text(
            text = "继续观看",
            color = GalleryText,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp)
        ) {
            items(items, key = { it.video.media.resourceVersionKey }) { entry ->
                Column(
                    modifier = Modifier
                        .width(156.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(GalleryRaisedSurface)
                        .semantics { role = Role.Button }
                        .then(
                            Modifier.pointerInput(entry.video.id) {
                                detectTapGestures {
                                    onVideoClick(entry.video.media)
                                }
                            }
                        )
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(88.dp)
                    ) {
                        GalleryThumbnail(entry.video.media, Modifier.fillMaxSize())
                        Text(
                            text = formatDuration(entry.video.media.duration),
                            color = Color.White,
                            fontSize = 10.sp,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(5.dp)
                                .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(3.dp))
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                        Box(
                            Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth(entry.progress.coerceIn(0f, 1f))
                                .height(3.dp)
                                .background(GalleryIceBlue)
                        )
                    }
                    Text(
                        text = entry.video.media.title.ifBlank { entry.video.media.displayName },
                        color = GalleryText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun SelectionActionSurface(selectedCount: Int, onClear: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(22.dp))
            .background(GalleryRaisedSurface.copy(alpha = 0.98f))
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .testTag("selection-actions")
    ) {
        IconButton(onClick = onClear, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Close, contentDescription = "退出选择", tint = GalleryText)
        }
        Text("已选择 $selectedCount 项", color = GalleryText, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = {}, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Share, contentDescription = "分享", tint = GalleryTextMuted)
        }
        IconButton(onClick = {}, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.PlaylistAdd, contentDescription = "加入播放列表", tint = GalleryTextMuted)
        }
        IconButton(onClick = {}, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.DeleteOutline, contentDescription = "删除", tint = GalleryTextMuted)
        }
    }
}

private fun Set<String>.toggle(key: String): Set<String> =
    if (key in this) this - key else this + key
