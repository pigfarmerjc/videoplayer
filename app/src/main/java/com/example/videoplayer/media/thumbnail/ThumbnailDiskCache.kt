package com.example.videoplayer.media.thumbnail

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.LruCache
import android.util.Size
import com.example.videoplayer.data.model.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class ThumbnailDiskCache private constructor(
    private val context: Context
) : ThumbnailCache<Bitmap, MediaItem>, ComponentCallbacks2 {
    private val maintenance = Channel<Unit>(Channel.CONFLATED)
    private val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = object : LruCache<ThumbnailKey, Bitmap>(memoryCacheSizeKb()) {
        override fun sizeOf(key: ThumbnailKey, value: Bitmap): Int = value.byteCount / 1024
    }

    init {
        context.registerComponentCallbacks(this)
        maintenanceScope.launch {
            for (ignored in maintenance) {
                trimDiskCache()
            }
        }
    }

    override suspend fun loadMemory(key: ThumbnailKey): Bitmap? = cache.get(key)

    override suspend fun loadDisk(key: ThumbnailKey): Bitmap? = withContext(Dispatchers.IO) {
        val file = diskFile(key)
        if (!file.exists() || file.length() == 0L) return@withContext null
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        )?.also { file.setLastModified(System.currentTimeMillis()) }
    }

    override suspend fun decode(key: ThumbnailKey, source: MediaItem): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(source.uri, Size(key.size.width, key.size.height), null)
            } else {
                @Suppress("DEPRECATION")
                android.provider.MediaStore.Video.Thumbnails.getThumbnail(
                    context.contentResolver,
                    source.id,
                    android.provider.MediaStore.Video.Thumbnails.MINI_KIND,
                    null
                )
            }
        }.getOrNull()?.toRgb565()
    }

    override suspend fun putMemory(key: ThumbnailKey, value: Bitmap) {
        cache.put(key, value)
    }

    override suspend fun writeDisk(key: ThumbnailKey, value: Bitmap) {
        withContext(Dispatchers.IO) {
        runCatching {
            diskFile(key).outputStream().use { output ->
                check(value.compress(Bitmap.CompressFormat.JPEG, 82, output)) { "缩略图写入失败" }
            }
            maintenance.trySend(Unit)
        }
        }
    }

    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) cache.evictAll()
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    override fun onLowMemory() {
        cache.evictAll()
    }

    private fun diskFile(key: ThumbnailKey): File {
        val directory = File(context.cacheDir, "grid_thumbnails").apply { mkdirs() }
        return File(directory, "${key.resource.storageKey}|${key.resource.uri}|${key.resource.dateModified}|${key.size.width}x${key.size.height}".sha256() + ".jpg")
    }

    private fun trimDiskCache() {
        val files = File(context.cacheDir, "grid_thumbnails")
            .listFiles { file -> file.extension.equals("jpg", ignoreCase = true) }
            ?.sortedByDescending(File::lastModified)
            ?: return
        var totalBytes = 0L
        files.forEachIndexed { index, file ->
            totalBytes += file.length()
            if (index >= MAX_DISK_ENTRIES || totalBytes > MAX_DISK_BYTES) {
                file.delete()
            }
        }
    }

    private fun Bitmap.toRgb565(): Bitmap {
        if (config == Bitmap.Config.RGB_565) return this
        return Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565).also { destination ->
            android.graphics.Canvas(destination).drawBitmap(this, 0f, 0f, null)
            recycle()
        }
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun memoryCacheSizeKb(): Int =
        (Runtime.getRuntime().maxMemory() / 1024L / 12L).toInt().coerceIn(8 * 1024, 48 * 1024)

    companion object {
        private const val MAX_DISK_ENTRIES = 2_500
        private const val MAX_DISK_BYTES = 128L * 1024L * 1024L

        @Volatile
        private var instance: ThumbnailDiskCache? = null

        fun get(context: Context): ThumbnailDiskCache = instance ?: synchronized(this) {
            instance ?: ThumbnailDiskCache(context.applicationContext).also { instance = it }
        }
    }
}
