package com.example.videoplayer.media.thumbnail

import android.content.Context
import android.graphics.Bitmap
import com.example.videoplayer.data.model.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow

object ThumbnailSchedulerProvider {
    @Volatile
    private var scheduler: ThumbnailScheduler<Bitmap>? = null

    fun request(
        context: Context,
        item: MediaItem,
        size: ThumbnailSize,
        priority: ThumbnailPriority
    ): Flow<ThumbnailResult<Bitmap>> {
        val cache = ThumbnailDiskCache.get(context)
        cache.register(item)
        val current = scheduler ?: synchronized(this) {
            scheduler ?: ThumbnailScheduler(
                cache = cache,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            ).also { scheduler = it }
        }
        return current.request(item.id, size, priority)
    }
}
