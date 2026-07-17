package com.example.videoplayer.data.library

data class LibraryMedia(
    val id: String,
    val mediaStoreId: Long = 0L,
    val uri: String = "",
    val title: String = "",
    val displayName: String = "",
    val path: String = "",
    val folderName: String = "",
    val size: Long = 0L,
    val dateAdded: Long = 0L,
    val dateModified: Long = dateAdded,
    val duration: Long = 0L,
    val resolution: String = ""
)

data class MediaLibraryState(
    val generation: Long = 0L,
    val videos: List<LibraryMedia> = emptyList(),
    val photos: List<LibraryMedia> = emptyList(),
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
    data class Success(val items: List<LibraryMedia>) : MediaQueryResult
    data class Failure(val cause: Throwable) : MediaQueryResult
}

data class MediaLibraryScanResult(
    val videos: MediaQueryResult,
    val photos: MediaQueryResult
)

interface MediaLibraryScanner {
    suspend fun scan(force: Boolean): MediaLibraryScanResult
}
