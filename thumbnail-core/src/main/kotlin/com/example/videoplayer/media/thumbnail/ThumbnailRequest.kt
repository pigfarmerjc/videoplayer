package com.example.videoplayer.media.thumbnail

data class ThumbnailSize(
    val width: Int,
    val height: Int
)

data class ThumbnailKey(
    val mediaId: Long,
    val size: ThumbnailSize
)

enum class ThumbnailPriority {
    VISIBLE,
    PREFETCH,
    BACKGROUND
}

data class ThumbnailResult<T : Any>(
    val key: ThumbnailKey,
    val value: T
)

interface ThumbnailCache<T : Any> {
    suspend fun loadMemory(key: ThumbnailKey): T?
    suspend fun loadDisk(key: ThumbnailKey): T?
    suspend fun decode(key: ThumbnailKey): T?
    suspend fun putMemory(key: ThumbnailKey, value: T)
    suspend fun writeDisk(key: ThumbnailKey, value: T)
}
