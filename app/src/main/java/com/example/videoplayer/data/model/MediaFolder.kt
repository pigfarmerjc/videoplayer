package com.example.videoplayer.data.model

import androidx.compose.runtime.Immutable

@Immutable
data class MediaFolder(
    val name: String,
    val path: String,
    val items: List<MediaItem>
) {
    val totalCount: Int get() = items.size
    val videoCount: Int get() = items.count { it.type == MediaType.VIDEO }
    val photoCount: Int get() = items.count { it.type == MediaType.PHOTO }
    val audioCount: Int get() = items.count { it.type == MediaType.AUDIO }
}
