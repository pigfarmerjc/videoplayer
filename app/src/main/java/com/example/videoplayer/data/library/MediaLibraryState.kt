package com.example.videoplayer.data.library

import com.example.videoplayer.data.model.MediaItem

data class MediaLibraryState(
    val generation: Long = 0L,
    val videos: List<MediaItem> = emptyList(),
    val photos: List<MediaItem> = emptyList(),
    val isRefreshing: Boolean = false,
    val error: LibraryError? = null
)

sealed interface LibraryError {
    data class PartialQueryFailure(
        val video: Throwable?,
        val photo: Throwable?
    ) : LibraryError {
        init {
            require(video != null || photo != null)
        }
    }

    data class ScanFailure(val cause: Throwable) : LibraryError
}

sealed interface MediaQueryResult {
    data class Success(val items: List<MediaItem>) : MediaQueryResult
    data class Failure(val cause: Throwable) : MediaQueryResult
}

data class MediaLibraryScanResult(
    val videos: MediaQueryResult,
    val photos: MediaQueryResult
)

interface MediaLibraryScanner {
    suspend fun scan(force: Boolean): MediaLibraryScanResult
}
