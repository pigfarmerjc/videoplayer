package com.example.videoplayer.ui.gallery

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.videoplayer.data.model.MediaItem
import com.example.videoplayer.media.thumbnail.ThumbnailPriority
import com.example.videoplayer.media.thumbnail.FastScrollGate
import com.example.videoplayer.media.thumbnail.GridScrollVelocityTracker
import com.example.videoplayer.media.thumbnail.ThumbnailSchedulerProvider
import com.example.videoplayer.media.thumbnail.ThumbnailScrollController
import com.example.videoplayer.media.thumbnail.ThumbnailSize
import com.example.videoplayer.ui.components.formatDuration
import com.example.videoplayer.ui.theme.GalleryBackground
import com.example.videoplayer.ui.theme.GalleryIceBlue
import com.example.videoplayer.ui.theme.GallerySurface
import com.example.videoplayer.ui.theme.GalleryText
import com.example.videoplayer.ui.theme.GalleryTextMuted
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs

internal data class GalleryVideo(
    val media: MediaItem
) : VideoTimelineItem {
    override val id: String = media.storageKey
    override val dateAddedEpochSeconds: Long = media.dateAdded
}

private sealed interface TimelineEntry {
    val stableKey: String

    data class Header(val section: VideoSection<GalleryVideo>) : TimelineEntry {
        override val stableKey: String = "header:${section.stableKey}"
    }

    data class Video(val item: GalleryVideo) : TimelineEntry {
        override val stableKey: String = "video:${item.media.resourceVersionKey}"
    }
}

@Composable
internal fun VideoGalleryGrid(
    sections: List<VideoSection<GalleryVideo>>,
    columnCount: Int,
    selectedKeys: Set<String>,
    progress: Map<String, Float>,
    aspectMode: GalleryAspectMode,
    state: LazyGridState,
    onColumnsChange: (Int) -> Unit,
    onVideoClick: (MediaItem) -> Unit,
    onVideoLongClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val entries = remember(sections) {
        buildList {
            sections.forEach { section ->
                add(TimelineEntry.Header(section))
                section.items.forEach { add(TimelineEntry.Video(it)) }
            }
        }
    }
    val entrySectionIndices = remember(sections) {
        buildTimelineSectionIndexMap(sections.map { it.items.size })
    }
    val activeSection by remember(entries, state) {
        androidx.compose.runtime.derivedStateOf {
            val entryIndex = state.firstVisibleItemIndex.coerceIn(0, entrySectionIndices.lastIndex.coerceAtLeast(0))
            sections.getOrNull(entrySectionIndices.getOrElse(entryIndex) { 0 })
        }
    }
    var presentationScale by remember { mutableFloatStateOf(1f) }
    var previewColumns by remember(columnCount) { mutableFloatStateOf(columnCount.toFloat()) }
    BindThumbnailFastScroll(state, columnCount)

    Box(modifier = modifier.background(GalleryBackground)) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columnCount),
            state = state,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(top = 36.dp, bottom = 116.dp),
            modifier = Modifier
                .fillMaxSize()
                .galleryPinch(
                    columnCount = columnCount,
                    onPreview = { columns ->
                        previewColumns = columns
                        presentationScale = (columnCount / columns).coerceIn(0.5f, 2f)
                    },
                    onCommit = { columns ->
                        onColumnsChange(columns)
                        presentationScale = 1f
                        previewColumns = columns.toFloat()
                    }
                )
                .graphicsLayer {
                    scaleX = presentationScale
                    scaleY = presentationScale
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
                }
        ) {
            items(
                items = entries,
                key = { it.stableKey },
                contentType = { if (it is TimelineEntry.Header) "date-header" else "video-thumbnail" },
                span = { entry ->
                    if (entry is TimelineEntry.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1)
                }
            ) { entry ->
                when (entry) {
                    is TimelineEntry.Header -> DateSectionHeader(entry.section)
                    is TimelineEntry.Video -> VideoThumbnailItem(
                        item = entry.item.media,
                        selected = entry.item.id in selectedKeys,
                        progress = progress[entry.item.id] ?: 0f,
                        aspectMode = aspectMode,
                        onClick = { onVideoClick(entry.item.media) },
                        onLongClick = { onVideoLongClick(entry.item.media) }
                    )
                }
            }
        }

        activeSection?.let { section ->
            Text(
                text = sectionTitle(section),
                color = GalleryText,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .background(GalleryBackground.copy(alpha = 0.96f))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .testTag("sticky-date-header")
            )
        }

        if (presentationScale != 1f) {
            Text(
                text = "${previewColumns.toInt().coerceIn(2, 8)} 列",
                color = GalleryBackground,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(GalleryIceBlue)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
    }
}

@Composable
private fun DateSectionHeader(section: VideoSection<GalleryVideo>) {
    Text(
        text = sectionTitle(section),
        color = GalleryTextMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .fillMaxWidth()
            .background(GalleryBackground)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    )
}

@Composable
private fun VideoThumbnailItem(
    item: MediaItem,
    selected: Boolean,
    progress: Float,
    aspectMode: GalleryAspectMode,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(item.galleryAspectRatio(aspectMode))
            .clip(RoundedCornerShape(2.dp))
            .background(GallerySurface)
            .pointerInput(item.resourceVersionKey, selected) {
                detectTapGestures(onLongPress = { onLongClick() }, onTap = { onClick() })
            }
            .semantics {
                role = Role.Button
                this.selected = selected
                onClick("打开视频") { onClick(); true }
                onLongClick("选择视频") { onLongClick(); true }
            }
            .testTag("video-item:${item.storageKey}")
    ) {
        GalleryThumbnail(item = item, modifier = Modifier.fillMaxSize())
        Text(
            text = formatDuration(item.duration),
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 5.dp, bottom = 6.dp)
                .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(3.dp))
                .padding(horizontal = 4.dp, vertical = 2.dp)
        )
        if (progress > 0f) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(3.dp)
                    .background(GalleryIceBlue)
            )
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(GalleryIceBlue)
                    .padding(3.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = "已选择", tint = GalleryBackground)
            }
        }
    }
}

@Composable
internal fun GalleryThumbnail(item: MediaItem, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(item.resourceVersionKey) { mutableStateOf<Bitmap?>(null) }
    var thumbnailSize by remember { mutableStateOf<ThumbnailSize?>(null) }

    LaunchedEffect(item.resourceVersionKey, thumbnailSize) {
        val requestedSize = thumbnailSize ?: return@LaunchedEffect
        bitmap = null
        ThumbnailSchedulerProvider.request(
            context = context,
            item = item,
            size = requestedSize,
            priority = ThumbnailPriority.VISIBLE
        ).collectLatest { bitmap = it.value }
    }

    Box(
        modifier = modifier
            .background(GallerySurface)
            .onSizeChanged { size ->
                if (size.width > 0 && size.height > 0) {
                    thumbnailSize = ThumbnailSize(size.width, size.height)
                }
            }
    ) {
        bitmap?.let { image ->
            Image(
                painter = BitmapPainter(image.asImageBitmap()),
                contentDescription = item.title.ifBlank { item.displayName },
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
internal fun BindThumbnailFastScroll(state: LazyGridState, columns: Int) {
    val scrollController = remember {
        ThumbnailScrollController(ThumbnailSchedulerProvider::setFastScrolling)
    }
    val gate = remember { FastScrollGate() }
    val velocityTracker = remember { GridScrollVelocityTracker() }
    DisposableEffect(scrollController) {
        onDispose { scrollController.onScrollInProgressChanged(false) }
    }
    LaunchedEffect(state, columns) {
        velocityTracker.reset()
        gate.update(isScrollInProgress = false, velocity = 0f)
        scrollController.onScrollInProgressChanged(false)
        var previousTime = System.nanoTime()
        snapshotFlow {
            val layoutInfo = state.layoutInfo
            GridScrollSample(
                scrolling = state.isScrollInProgress,
                visibleItems = layoutInfo.visibleItemsInfo,
                mainAxisSpacing = layoutInfo.mainAxisItemSpacing
            )
        }.collect { sample ->
            val now = System.nanoTime()
            val elapsedSeconds = ((now - previousTime).coerceAtLeast(1L)) / 1_000_000_000f
            val velocity = if (sample.scrolling) {
                velocityTracker.beginSample()
                sample.visibleItems.forEach { item ->
                    velocityTracker.addVisibleLine(
                        line = item.row,
                        mainAxisOffset = item.offset.y,
                        mainAxisSize = item.size.height
                    )
                }
                velocityTracker.endSample(elapsedSeconds, sample.mainAxisSpacing)
            } else {
                velocityTracker.reset()
                0f
            }
            scrollController.onScrollInProgressChanged(gate.update(sample.scrolling, velocity))
            previousTime = now
        }
    }
}

private data class GridScrollSample(
    val scrolling: Boolean,
    val visibleItems: List<LazyGridItemInfo>,
    val mainAxisSpacing: Int
)

private fun Modifier.galleryPinch(
    columnCount: Int,
    onPreview: (Float) -> Unit,
    onCommit: (Int) -> Unit
): Modifier = pointerInput(columnCount) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var cumulativeZoom = 1f
        var previewColumns = columnCount.toFloat()
        var pinching = false
        do {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } >= 2) {
                pinching = true
                cumulativeZoom *= event.calculateZoom()
                previewColumns = previewGalleryColumnCount(columnCount, cumulativeZoom)
                onPreview(previewColumns)
                event.changes.forEach { change ->
                    if (change.positionChanged()) change.consume()
                }
            }
        } while (event.changes.any { it.pressed })

        if (pinching) onCommit(commitGalleryColumnCount(previewColumns))
    }
}

internal val MediaItem.resourceVersionKey: String
    get() = "$storageKey|$uri|$dateModified"

internal fun MediaItem.galleryAspectRatio(mode: GalleryAspectMode): Float {
    if (mode == GalleryAspectMode.SQUARE) return 1f
    val parts = resolution.lowercase().split('x', '×')
    val width = parts.getOrNull(0)?.trim()?.toFloatOrNull() ?: return 16f / 9f
    val height = parts.getOrNull(1)?.trim()?.toFloatOrNull() ?: return 16f / 9f
    return (width / height).coerceIn(0.5f, 2f)
}

internal fun sectionTitle(section: VideoSection<GalleryVideo>): String = when (val key = section.key) {
    VideoSectionKey.Today -> "今天"
    VideoSectionKey.Yesterday -> "昨天"
    VideoSectionKey.ThisWeek -> "本周"
    is VideoSectionKey.Month -> "${key.yearMonth.year} 年 ${key.yearMonth.monthValue} 月"
}
