package com.example.videoplayer.data.model

import android.net.Uri
import androidx.compose.runtime.Immutable

@Immutable
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val title: String,
    val displayName: String,
    val path: String,
    val folderName: String,
    val size: Long,
    val dateAdded: Long,
    val duration: Long = 0L,  // 0 for images, ms for videos/audios
    val resolution: String = "",
    val type: MediaType,
    val artist: String = ""  // Mainly for audio
) {
    val storageKey: String = if (path.isNotBlank()) "file:$path" else "uri:$uri"
}
