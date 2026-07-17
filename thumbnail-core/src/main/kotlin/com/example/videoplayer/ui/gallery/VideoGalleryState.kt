package com.example.videoplayer.ui.gallery

import kotlin.math.roundToInt

private const val MIN_GALLERY_COLUMNS = 2
private const val MAX_GALLERY_COLUMNS = 8
private const val DEFAULT_GALLERY_COLUMNS = 4

interface GalleryColumnStore {
    fun read(): Int
    fun write(columnCount: Int)
}

enum class GalleryAspectMode(val persistedValue: String) {
    SQUARE("square"),
    ORIGINAL("original")
}

interface GalleryAspectStore {
    fun read(): String?
    fun write(value: String)
}

enum class PermissionRecoveryAction {
    NONE,
    REQUEST,
    OPEN_SETTINGS
}

fun resolvePermissionRecoveryAction(
    granted: Boolean,
    requestedOnce: Boolean,
    shouldShowRationale: Boolean
): PermissionRecoveryAction = when {
    granted -> PermissionRecoveryAction.NONE
    shouldShowRationale || !requestedOnce -> PermissionRecoveryAction.REQUEST
    else -> PermissionRecoveryAction.OPEN_SETTINGS
}

data class GalleryDeleteResult(
    val successCount: Int,
    val failureCount: Int
) {
    init {
        require(successCount >= 0)
        require(failureCount >= 0)
    }

    val message: String
        get() = if (successCount == 0) {
            "删除失败，0 个成功，$failureCount 个失败"
        } else {
            "已删除 $successCount 个，$failureCount 个失败"
        }
}

data class ContinueWatchingVideo<T : VideoTimelineItem>(
    val video: T,
    val progress: Float
)

data class VideoGalleryState<T : VideoTimelineItem>(
    val sections: List<VideoSection<T>> = emptyList(),
    val continueWatching: List<ContinueWatchingVideo<T>> = emptyList(),
    val columnCount: Int = DEFAULT_GALLERY_COLUMNS
) {
    fun persistColumnCount(requestedColumnCount: Int, store: GalleryColumnStore): Int {
        val clampedColumnCount = requestedColumnCount.clampGalleryColumnCount()
        store.write(clampedColumnCount)
        return clampedColumnCount
    }

    companion object {
        fun <T : VideoTimelineItem> create(
            videos: List<T>,
            playbackProgress: Map<String, Float>,
            persistedColumns: Int,
            sections: List<VideoSection<T>> = emptyList()
        ): VideoGalleryState<T> = VideoGalleryState(
            sections = sections,
            continueWatching = deriveContinueWatching(videos, playbackProgress),
            columnCount = persistedColumns.clampGalleryColumnCount()
        )
    }
}

fun <T : VideoTimelineItem> deriveContinueWatching(
    videos: List<T>,
    playbackProgress: Map<String, Float>
): List<ContinueWatchingVideo<T>> = videos
    .sortedWith(compareByDescending<T> { it.dateAddedEpochSeconds }.thenBy { it.id })
    .mapNotNull { video ->
        val progress = playbackProgress[video.id] ?: return@mapNotNull null
        if (progress.isFinite() && progress > 0f && progress < 1f) {
            ContinueWatchingVideo(video, progress)
        } else {
            null
        }
    }
    .take(5)

fun GalleryColumnStore.readClampedColumnCount(): Int {
    val storedColumnCount = read()
    val clampedColumnCount = storedColumnCount.clampGalleryColumnCount()
    if (storedColumnCount != clampedColumnCount) write(clampedColumnCount)
    return clampedColumnCount
}

fun Int.clampGalleryColumnCount(): Int = coerceIn(MIN_GALLERY_COLUMNS, MAX_GALLERY_COLUMNS)

fun previewGalleryColumnCount(startColumns: Int, zoom: Float): Float {
    val safeZoom = zoom.takeIf { it.isFinite() && it > 0f } ?: 1f
    return (startColumns.clampGalleryColumnCount() / safeZoom)
        .coerceIn(MIN_GALLERY_COLUMNS.toFloat(), MAX_GALLERY_COLUMNS.toFloat())
}

fun commitGalleryColumnCount(previewColumns: Float): Int {
    val safePreview = previewColumns.takeIf { it.isFinite() } ?: DEFAULT_GALLERY_COLUMNS.toFloat()
    return safePreview.roundToInt().clampGalleryColumnCount()
}

fun GalleryAspectStore.readGalleryAspectMode(): GalleryAspectMode {
    val stored = read()
    val mode = GalleryAspectMode.entries.firstOrNull { it.persistedValue == stored }
        ?: GalleryAspectMode.SQUARE
    if (stored != mode.persistedValue) write(mode.persistedValue)
    return mode
}

fun GalleryAspectStore.writeGalleryAspectMode(mode: GalleryAspectMode) {
    write(mode.persistedValue)
}

fun buildTimelineSectionIndexMap(sectionItemCounts: List<Int>): IntArray {
    val totalEntries = sectionItemCounts.sumOf { it.coerceAtLeast(0) + 1 }
    return IntArray(totalEntries).also { result ->
        var entryIndex = 0
        sectionItemCounts.forEachIndexed { sectionIndex, itemCount ->
            repeat(itemCount.coerceAtLeast(0) + 1) {
                result[entryIndex++] = sectionIndex
            }
        }
    }
}
