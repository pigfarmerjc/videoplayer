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
    private var scheduler: ThumbnailScheduler<Bitmap, MediaItem>? = null

    @Volatile
    private var isFastScrolling = false

    fun request(
        context: Context,
        item: MediaItem,
        size: ThumbnailSize,
        priority: ThumbnailPriority
    ): Flow<ThumbnailResult<Bitmap>> {
        val current = scheduler ?: synchronized(this) {
            scheduler ?: ThumbnailScheduler(
                cache = ThumbnailDiskCache.get(context),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            ).also {
                it.setFastScrolling(isFastScrolling)
                scheduler = it
            }
        }
        return current.request(item.thumbnailResourceIdentity(), item, size, priority)
    }

    fun setFastScrolling(isFastScrolling: Boolean) {
        this.isFastScrolling = isFastScrolling
        scheduler?.setFastScrolling(isFastScrolling)
    }

    private fun MediaItem.thumbnailResourceIdentity() = ThumbnailResourceIdentity(
        storageKey = storageKey,
        uri = uri.toString(),
        dateModified = dateModified
    )
}
