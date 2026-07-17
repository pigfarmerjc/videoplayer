package com.example.videoplayer.data.repository

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import com.example.videoplayer.data.library.LibraryMedia
import com.example.videoplayer.data.library.MediaLibraryScanResult
import com.example.videoplayer.data.library.MediaLibraryScanner
import com.example.videoplayer.data.library.MediaQueryResult
import com.example.videoplayer.data.model.MediaFolder
import com.example.videoplayer.data.model.MediaItem
import com.example.videoplayer.data.model.MediaType
import com.example.videoplayer.data.model.LayoutMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class MediaRepository(private val context: Context) : MediaLibraryScanner {
    @Volatile private var cachedItems: List<MediaItem>? = null
    @Volatile private var cachedAtMs: Long = 0L
    // Lazy initialize SharedPreferences to avoid repeated getSharedPreferences calls (#9)
    private val prefs by lazy { context.getSharedPreferences("aurora_player_prefs", Context.MODE_PRIVATE) }
    @Volatile private var cachedFavoriteKeys: Set<String>? = null
    @Volatile private var cachedPlaylistKeys: Set<String>? = null
    @Volatile private var cachedWatchedKeys: Set<String>? = null
    // key = uniqueKey, value = last_played_at timestamp ms
    @Volatile private var cachedLastPlayedMap: Map<String, Long>? = null
    // Folder 列表缓存：5 秒 TTL，避免 toggleFavorite 频繁触发重算
    @Volatile private var cachedFolders: List<MediaFolder>? = null
    @Volatile private var cachedFoldersAtMs: Long = 0L

    companion object {
        const val ALL_VIDEOS = "ALL_VIDEOS"
        const val ALL_PHOTOS = "ALL_PHOTOS"
        const val INTERNAL_VIDEOS = "INTERNAL_VIDEOS"
        const val TF_CARD_VIDEOS = "TF_CARD_VIDEOS"
        const val FAVORITES = "FAVORITES"
        const val RECENT_PLAYED = "RECENT_PLAYED"
        const val RECENT_ADDED = "RECENT_ADDED"
        const val PLAYLIST = "PLAYLIST"

        private val videoExtensions = setOf(
            "mp4", "m4v", "mkv", "webm", "avi", "3gp", "3g2", "ts", "m2ts", "mts",
            "mov", "flv", "mpg", "mpeg", "vob", "wmv", "asf", "iso"
        )
        private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")
        private const val MEDIA_CACHE_TTL_MS = 10 * 60 * 1000L
        private const val FOLDER_CACHE_TTL_MS = 5_000L
    }

    fun hasFreshMediaCache(): Boolean {
        val now = System.currentTimeMillis()
        return cachedItems != null && now - cachedAtMs < MEDIA_CACHE_TTL_MS
    }

    override suspend fun scan(force: Boolean): MediaLibraryScanResult =
        scanRepository(force).toLibraryScanResult()

    suspend fun scanMedia(forceRefresh: Boolean = false): List<MediaItem> {
        return scanRepository(forceRefresh).items()
    }

    private suspend fun scanRepository(force: Boolean): RepositoryScanResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cachedItems?.takeIf { !force && now - cachedAtMs < MEDIA_CACHE_TTL_MS }?.let {
            return@withContext RepositoryScanResult(
                videos = RepositoryQueryResult.Success(it.filter { item -> item.type == MediaType.VIDEO }),
                photos = RepositoryQueryResult.Success(it.filter { item -> item.type == MediaType.PHOTO })
            )
        }

        val interner = StringInterner()
        val items = linkedMapOf<String, MediaItem>()
        val videosDeferred = async { captureQuery { scanVideos(interner) + scanIsoFiles(interner) } }
        val imagesDeferred = async { captureQuery { scanImages(interner) } }
        val videoQuery = videosDeferred.await()
        val photoQuery = imagesDeferred.await()

        videoQuery.getOrNull()?.forEach { items[it.uniqueKey] = it }
        photoQuery.getOrNull()?.forEach { items[it.uniqueKey] = it }

        if (force) {
            scanPhysicalRoots(items, interner)
        }

        val allPrefs = prefs.all
        val normalized = items.values.map { item ->
            if (item.type == MediaType.VIDEO) {
                val savedDuration = (allPrefs["duration:${item.uniqueKey}"] as? Long) ?: 0L
                val savedResolution = allPrefs["resolution:${item.uniqueKey}"] as? String
                if (savedDuration > 0L || !savedResolution.isNullOrBlank()) {
                    item.copy(
                        duration = if (savedDuration > 0L) savedDuration else item.duration,
                        resolution = if (!savedResolution.isNullOrBlank()) savedResolution else item.resolution
                    )
                } else {
                    item
                }
            } else {
                item
            }
        }.sortedWith(
            compareByDescending<MediaItem> { it.dateAdded }
                .thenBy { it.displayName.lowercase(Locale.ROOT) }
        )

        if (videoQuery.isSuccess && photoQuery.isSuccess) {
            cachedItems = normalized
            cachedAtMs = now
        }

        RepositoryScanResult(
            videos = videoQuery.fold(
                onSuccess = { RepositoryQueryResult.Success(normalized.filter { item -> item.type == MediaType.VIDEO }) },
                onFailure = { RepositoryQueryResult.Failure(it) }
            ),
            photos = photoQuery.fold(
                onSuccess = { RepositoryQueryResult.Success(normalized.filter { item -> item.type == MediaType.PHOTO }) },
                onFailure = { RepositoryQueryResult.Failure(it) }
            )
        )
    }

    suspend fun getFolders(items: List<MediaItem>): List<MediaFolder> = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        cachedFolders?.takeIf { now - cachedFoldersAtMs < FOLDER_CACHE_TTL_MS }?.let { return@withContext it }
        buildFolders(items).also {
            cachedFolders = it
            cachedFoldersAtMs = now
        }
    }

    fun resolveFolderItems(folderName: String, items: List<MediaItem>): List<MediaItem> {
        val resolved = when (folderName) {
            ALL_VIDEOS -> items.filter { it.type == MediaType.VIDEO }
            ALL_PHOTOS -> items.filter { it.type == MediaType.PHOTO }
            INTERNAL_VIDEOS -> items.filter { it.type == MediaType.VIDEO && isInternalPath(it.path) }
            TF_CARD_VIDEOS -> items.filter { it.type == MediaType.VIDEO && !isInternalPath(it.path) }
            FAVORITES -> favoriteItems(items)
            RECENT_PLAYED -> recentPlayedItems(items)
            RECENT_ADDED -> recentAddedItems(items)
            PLAYLIST -> playlistItems(items)
            else -> items.filter { it.folderName == folderName }
        }
        return if (folderName == RECENT_PLAYED || folderName == PLAYLIST) resolved else resolved.sortedByDescending { it.dateAdded }
    }

    fun favoriteItems(items: List<MediaItem>): List<MediaItem> {
        val favoriteKeys = favoriteKeys()
        return items.filter { it.uniqueKey in favoriteKeys }.sortedByDescending { it.dateAdded }
    }

    fun recentPlayedItems(items: List<MediaItem>): List<MediaItem> {
        val playedMap = lastPlayedMap()
        return items
            .filter { it.uniqueKey in playedMap }
            .sortedByDescending { playedMap[it.uniqueKey] ?: 0L }
            .take(80)
    }

    fun recentAddedItems(items: List<MediaItem>): List<MediaItem> {
        return items.sortedByDescending { it.dateAdded }.take(120)
    }

    fun playlistItems(items: List<MediaItem>): List<MediaItem> {
        val keys = playlistKeys().toList()
        val byKey = items.associateBy { it.uniqueKey }
        return keys.mapNotNull { byKey[it] }
    }

    fun isFavorite(item: MediaItem): Boolean = item.uniqueKey in favoriteKeys()

    fun favoriteKeySnapshot(): Set<String> = favoriteKeys()

    fun setFavorite(item: MediaItem, favorite: Boolean) {
        synchronized(this) {
            val next = favoriteKeys().toMutableSet()
            if (favorite) next.add(item.uniqueKey) else next.remove(item.uniqueKey)
            cachedFavoriteKeys = next
            cachedFolders = null   // 收藏变化后立即失效 Folder 缓存
            prefs.edit().putStringSet("favorite_keys", next).apply()
        }
    }

    fun isInPlaylist(item: MediaItem): Boolean = item.uniqueKey in playlistKeys()

    fun playlistKeySnapshot(): Set<String> = playlistKeys()

    fun setInPlaylist(item: MediaItem, inPlaylist: Boolean) {
        synchronized(this) {
            val next = playlistKeys().toMutableList()
            if (inPlaylist) {
                if (item.uniqueKey !in next) next.add(item.uniqueKey)
            } else {
                next.remove(item.uniqueKey)
            }
            cachedPlaylistKeys = next.toSet()
            cachedFolders = null   // 收藏夹变化后立即失效 Folder 缓存
            prefs.edit().putString("playlist_keys", next.joinToString("\n")).apply()
        }
    }

    suspend fun moveItemsToFolder(items: List<MediaItem>, targetFolderPath: String): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val targetFolder = File(targetFolderPath)
        if (!targetFolder.exists() || !targetFolder.isDirectory) return@withContext 0 to items.size

        var moved = 0
        var failed = 0
        items.forEach { item ->
            val source = item.path.takeIf { it.isNotBlank() }?.let { File(it) }
            if (source == null || !source.exists() || !source.isFile) {
                failed++
                return@forEach
            }

            val destination = uniqueDestination(targetFolder, source.name)
            val sourcePath = source.absolutePath
            val destPath = destination.absolutePath
            val ok = runCatching { source.renameTo(destination) }.getOrDefault(false)
            if (ok) {
                moved++
                android.media.MediaScannerConnection.scanFile(
                    context,
                    arrayOf(sourcePath, destPath),
                    null,
                    null
                )
            } else {
                failed++
            }
        }
        cachedItems = null
        cachedAtMs = 0L
        cachedFolders = null
        cachedFoldersAtMs = 0L
        moved to failed
    }

    fun getSkipSeconds(): Int = prefs.getInt("skip_seconds", 10).coerceIn(3, 120)

    fun setSkipSeconds(seconds: Int) {
        prefs.edit().putInt("skip_seconds", seconds.coerceIn(3, 120)).apply()
    }

    fun isPlayerTutorialShown(): Boolean = prefs.getBoolean("player_tutorial_shown", false)

    fun setPlayerTutorialShown(shown: Boolean) {
        prefs.edit().putBoolean("player_tutorial_shown", shown).apply()
    }

    fun isAutoReplayEnabled(): Boolean = prefs.getBoolean("auto_replay", false)

    fun setAutoReplayEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("auto_replay", enabled).apply()
    }

    fun isAutoPlayNextEnabled(): Boolean = prefs.getBoolean("auto_play_next", true)

    fun setAutoPlayNextEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("auto_play_next", enabled).apply()
    }

    fun markWatched(item: MediaItem) {
        synchronized(this) {
            val current = watchedKeys()
            if (item.uniqueKey in current) return
            val next = current.toMutableSet().apply { add(item.uniqueKey) }
            cachedWatchedKeys = next
            prefs.edit().putStringSet("watched_keys", next).apply()
        }
    }

    fun isWatched(item: MediaItem): Boolean = item.uniqueKey in watchedKeys()

    fun watchedKeySnapshot(): Set<String> = watchedKeys()

    fun setWatchedLastEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("watched_last", enabled).apply()
    }

    fun isWatchedLastEnabled(): Boolean = prefs.getBoolean("watched_last", true)

    fun isBackgroundPlayEnabled(): Boolean = prefs.getBoolean("background_play", false)

    fun setBackgroundPlayEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("background_play", enabled).apply()
    }

    fun isPipEnabled(): Boolean = prefs.getBoolean("pip_enabled", true)

    fun setPipEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("pip_enabled", enabled).apply()
    }

    fun getPreferredVlcDecoder(item: MediaItem): Boolean? {
        return when (prefs.getString("decoder:${item.uniqueKey}", null)) {
            "vlc" -> true
            "exo" -> false
            else -> null
        }
    }

    fun setPreferredVlcDecoder(item: MediaItem, useVlc: Boolean) {
        prefs.edit().putString("decoder:${item.uniqueKey}", if (useVlc) "vlc" else "exo").apply()
    }

    fun isFolderGridModeEnabled(): Boolean = prefs.getBoolean("folder_grid_mode", true)

    fun setFolderGridModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("folder_grid_mode", enabled).apply()
    }

    fun isMainFolderGridModeEnabled(): Boolean = prefs.getBoolean("main_folder_grid_mode", false)

    fun setMainFolderGridModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("main_folder_grid_mode", enabled).apply()
    }

    fun isVideoGridModeEnabled(): Boolean = prefs.getBoolean("video_grid_mode", false)

    fun setVideoGridModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("video_grid_mode", enabled).apply()
    }

    fun getVideoLayoutMode(): LayoutMode {
        val modeOrdinal = prefs.getInt("video_layout_mode_v2", -1)
        if (modeOrdinal != -1) {
            return LayoutMode.entries.getOrNull(modeOrdinal) ?: LayoutMode.LIST
        }
        val oldGrid = prefs.getBoolean("video_grid_mode", false)
        return if (oldGrid) LayoutMode.GRID else LayoutMode.LIST
    }

    fun setVideoLayoutMode(mode: LayoutMode) {
        // Single atomic write: merge both keys into one prefs transaction.
        prefs.edit()
            .putInt("video_layout_mode_v2", mode.ordinal)
            .putBoolean("video_grid_mode", mode == LayoutMode.GRID)
            .apply()
    }

    fun getFolderLayoutMode(): LayoutMode {
        val modeOrdinal = prefs.getInt("folder_layout_mode_v2", -1)
        if (modeOrdinal != -1) {
            return LayoutMode.entries.getOrNull(modeOrdinal) ?: LayoutMode.GRID
        }
        val oldGrid = prefs.getBoolean("folder_grid_mode", true)
        return if (oldGrid) LayoutMode.GRID else LayoutMode.LIST
    }

    fun setFolderLayoutMode(mode: LayoutMode) {
        // Single atomic write: merge both keys into one prefs transaction.
        prefs.edit()
            .putInt("folder_layout_mode_v2", mode.ordinal)
            .putBoolean("folder_grid_mode", mode == LayoutMode.GRID)
            .apply()
    }

    fun getGalleryColumnCount(): Int {
        return prefs.getInt("gallery_column_count", 4).coerceIn(2, 12)
    }

    fun setGalleryColumnCount(count: Int) {
        prefs.edit().putInt("gallery_column_count", count.coerceIn(2, 12)).apply()
    }

    // 视频网格尺寸：140 = 小，220 = 中，320 = 大
    fun getVideoGridSize(): Int = prefs.getInt("video_grid_size", 220).let {
        if (it in setOf(140, 220, 320)) it else 220
    }

    fun setVideoGridSize(dp: Int) {
        prefs.edit().putInt("video_grid_size", dp).apply()
    }

    // 文件夹网格尺寸：140 = 小，220 = 中，320 = 大
    fun getFolderGridSize(): Int = prefs.getInt("folder_grid_size", 220).let {
        if (it in setOf(140, 220, 320)) it else 220
    }

    fun setFolderGridSize(dp: Int) {
        prefs.edit().putInt("folder_grid_size", dp).apply()
    }

    // 主界面教程
    fun isMainTutorialShown(): Boolean = prefs.getBoolean("main_tutorial_shown", false)

    fun setMainTutorialShown(shown: Boolean) {
        prefs.edit().putBoolean("main_tutorial_shown", shown).apply()
    }

    fun savePlaybackProgress(item: MediaItem, positionMs: Long, durationMs: Long) {
        val safeDuration = durationMs.coerceAtLeast(0L)
        val safePosition = positionMs.coerceIn(0L, safeDuration.takeIf { it > 0L } ?: Long.MAX_VALUE)
        val now = System.currentTimeMillis()
        synchronized(this) {
            prefs.edit()
                .putLong("progress:${item.uniqueKey}", safePosition)
                .putLong("duration:${item.uniqueKey}", safeDuration)
                .putLong("last_played_at:${item.uniqueKey}", now)
                .apply()
            // 同步更新内存缓存，避免下次 recentPlayedItems 重读 prefs.all
            cachedLastPlayedMap = (cachedLastPlayedMap ?: emptyMap()).toMutableMap().also { it[item.uniqueKey] = now }
        }
    }

    fun saveMetadata(item: MediaItem, durationMs: Long, resolution: String) {
        synchronized(this) {
            val editor = prefs.edit()
            if (durationMs > 0L) {
                editor.putLong("duration:${item.uniqueKey}", durationMs)
            }
            if (resolution.isNotBlank()) {
                editor.putString("resolution:${item.uniqueKey}", resolution)
            }
            editor.apply()
        }
    }

    fun getPlaybackPosition(item: MediaItem): Long = prefs.getLong("progress:${item.uniqueKey}", 0L)

    fun getPlaybackDuration(item: MediaItem): Long = prefs.getLong("duration:${item.uniqueKey}", item.duration)

    fun getProjectionMode(item: MediaItem): String? =
        prefs.getString("projection_mode:${item.uniqueKey}", null)

    fun setProjectionMode(item: MediaItem, mode: String) {
        prefs.edit().putString("projection_mode:${item.uniqueKey}", mode).apply()
    }

    fun isProjectionSensorEnabled(item: MediaItem): Boolean =
        prefs.getBoolean("projection_sensor:${item.uniqueKey}", true)

    fun setProjectionSensorEnabled(item: MediaItem, enabled: Boolean) {
        prefs.edit().putBoolean("projection_sensor:${item.uniqueKey}", enabled).apply()
    }

    fun getPlaybackProgressFraction(item: MediaItem): Float {
        val duration = prefs.getLong("duration:${item.uniqueKey}", item.duration).coerceAtLeast(0L)
        if (duration <= 0L) return 0f
        return (getPlaybackPosition(item).toFloat() / duration).coerceIn(0f, 1f)
    }

    fun playbackProgressSnapshot(items: List<MediaItem>): Map<String, Float> {
        val allPrefs = prefs.all
        return buildMap {
            items.forEach { item ->
                val durationObj = allPrefs["duration:${item.uniqueKey}"]
                val duration = (durationObj as? Long) ?: item.duration
                val safeDuration = duration.coerceAtLeast(0L)
                if (safeDuration > 0L) {
                    val positionObj = allPrefs["progress:${item.uniqueKey}"]
                    val position = (positionObj as? Long) ?: 0L
                    val safePosition = position.coerceAtLeast(0L)
                    val fraction = (safePosition.toFloat() / safeDuration).coerceIn(0f, 1f)
                    if (fraction > 0f) {
                        put(item.uniqueKey, fraction)
                    }
                }
            }
        }
    }

    fun markLastViewed(folderName: String, item: MediaItem) {
        prefs.edit()
            .putString("last_viewed:$folderName", item.uniqueKey)
            .putString("last_viewed_global", item.uniqueKey)
            .apply()
    }

    fun isLastViewed(folderName: String, item: MediaItem): Boolean {
        return prefs.getString("last_viewed:$folderName", "") == item.uniqueKey
    }

    fun lastViewedKey(folderName: String): String {
        return prefs.getString("last_viewed:$folderName", "") ?: ""
    }

    fun lastViewedIndex(folderName: String, items: List<MediaItem>): Int {
        val key = prefs.getString("last_viewed:$folderName", "") ?: ""
        return items.indexOfFirst { it.uniqueKey == key }.coerceAtLeast(0)
    }

    private fun buildFolders(items: List<MediaItem>): List<MediaFolder> {
        val videos = mutableListOf<MediaItem>()
        val photos = mutableListOf<MediaItem>()
        val physicalMap = mutableMapOf<String, MutableList<MediaItem>>()

        items.forEach { item ->
            when (item.type) {
                MediaType.VIDEO -> videos.add(item)
                MediaType.AUDIO -> Unit
                MediaType.PHOTO -> photos.add(item)
            }
            // Use folderName as key for grouping
            physicalMap.getOrPut(item.folderName) { mutableListOf() }.add(item)
        }

        val folders = mutableListOf<MediaFolder>()
        
        // 1. All Media Virtual Folders
        if (videos.isNotEmpty()) folders.add(MediaFolder(ALL_VIDEOS, "All Videos", videos))
        
        val internalVideos = videos.filter { isInternalPath(it.path) }
        if (internalVideos.isNotEmpty()) folders.add(MediaFolder(INTERNAL_VIDEOS, "Internal Videos", internalVideos))
        
        val tfVideos = videos.filter { !isInternalPath(it.path) }
        if (tfVideos.isNotEmpty()) folders.add(MediaFolder(TF_CARD_VIDEOS, "SD Card Videos", tfVideos))
        
        if (photos.isNotEmpty()) folders.add(MediaFolder(ALL_PHOTOS, "All Photos", photos))
        
        // 2. Specialized Virtual Folders
        val recentPlayed = recentPlayedItems(items)
        if (recentPlayed.isNotEmpty()) folders.add(MediaFolder(RECENT_PLAYED, "Recently Played", recentPlayed))
        
        val recentAdded = recentAddedItems(items)
        if (recentAdded.isNotEmpty()) folders.add(MediaFolder(RECENT_ADDED, "Recently Added", recentAdded))
        
        val playlist = playlistItems(items)
        if (playlist.isNotEmpty()) folders.add(MediaFolder(PLAYLIST, "Playlists", playlist))
        
        val favorites = favoriteItems(items)
        if (favorites.isNotEmpty()) folders.add(MediaFolder(FAVORITES, "Favorites", favorites))

        // 3. Physical Folders (grouped by folderName)
        val physicalFolders = physicalMap.map { (folderName, folderItems) ->
            val path = folderItems.firstOrNull()?.path?.let { File(it).parent } ?: "/"
            MediaFolder(folderName, path, folderItems)
        }.sortedBy { it.name.lowercase(Locale.ROOT) }

        return folders + physicalFolders
    }

    private fun scanPhysicalRoots(items: LinkedHashMap<String, MediaItem>, interner: StringInterner) {
        discoverStorageRoots().forEach { root ->
            scanDirectory(root, items, depth = 0, maxDepth = 8, interner)
        }
    }

    private fun discoverStorageRoots(): List<File> {
        val roots = linkedSetOf<File>()
        listOf(
            File("/storage/emulated/0/Movies"),
            File("/storage/emulated/0/Download"),
            File("/storage/emulated/0/DCIM"),
            File("/storage/emulated/0/Videos"),
            File("/storage/emulated/0/Music")
        ).filterTo(roots) { it.exists() && it.isDirectory }

        File("/storage").listFiles()
            ?.filter { it.isDirectory && it.name != "self" && it.name != "emulated" }
            ?.forEach { roots.add(it) }

        return roots.toList()
    }

    private val skipDirs = setOf("android", "lost.dir", "cache", "temp", "tmp", "logs", "system volume information", "backups")

    private fun scanDirectory(dir: File, items: LinkedHashMap<String, MediaItem>, depth: Int, maxDepth: Int, interner: StringInterner) {
        if (depth > maxDepth || dir.name.startsWith(".") || dir.name.lowercase(Locale.ROOT) in skipDirs) return
        val children = try {
            dir.listFiles()
        } catch (_: SecurityException) {
            null
        } ?: return

        children.forEach { file ->
            if (file.isDirectory) {
                if (!file.name.equals("Android", ignoreCase = true)) {
                    scanDirectory(file, items, depth + 1, maxDepth, interner)
                }
            } else if (file.isFile && file.length() > 0) {
                val type = typeFromExtension(file.extension.lowercase(Locale.ROOT)) ?: return@forEach
                val path = file.absolutePath
                val key = "file:$path"
                if (items.containsKey(key)) return@forEach

                items[key] = MediaItem(
                    id = stableFileId(path),
                    uri = Uri.fromFile(file),
                    title = file.nameWithoutExtension,
                    displayName = file.name,
                    path = path,
                    folderName = getParentFolderName(path, interner),
                    size = file.length(),
                    dateAdded = file.lastModified() / 1000,
                    duration = 0L,
                    resolution = "",
                    type = type
                )
            }
        }
    }

    private fun scanVideos(interner: StringInterner): List<MediaItem> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.RESOLUTION
        )
        return query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection) { cursor ->
            val id = cursor.getLong(MediaStore.Video.Media._ID)
            val path = cursor.getStringOrEmpty(MediaStore.Video.Media.DATA)
            MediaItem(
                id = id,
                uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                title = cursor.getStringOrEmpty(MediaStore.Video.Media.TITLE).ifEmpty { cursor.getStringOrEmpty(MediaStore.Video.Media.DISPLAY_NAME) },
                displayName = cursor.getStringOrEmpty(MediaStore.Video.Media.DISPLAY_NAME).ifEmpty { "Unknown video" },
                path = path,
                folderName = getParentFolderName(path, interner),
                size = cursor.getLong(MediaStore.Video.Media.SIZE),
                dateAdded = cursor.getLong(MediaStore.Video.Media.DATE_ADDED),
                duration = cursor.getLong(MediaStore.Video.Media.DURATION),
                resolution = cursor.getStringOrEmpty(MediaStore.Video.Media.RESOLUTION),
                type = MediaType.VIDEO
            )
        }
    }

    private fun scanIsoFiles(interner: StringInterner): List<MediaItem> {
        val filesUri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_ADDED
        )
        val selection = "(${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ? COLLATE NOCASE OR ${MediaStore.Files.FileColumns.DATA} LIKE ? COLLATE NOCASE)"
        val selectionArgs = arrayOf("%.iso", "%.iso")
        return query(filesUri, projection, selection, selectionArgs) { cursor ->
            val id = cursor.getLong(MediaStore.Files.FileColumns._ID)
            val path = cursor.getStringOrEmpty(MediaStore.Files.FileColumns.DATA)
            val displayName = cursor.getStringOrEmpty(MediaStore.Files.FileColumns.DISPLAY_NAME).ifEmpty { "Unknown ISO" }
            MediaItem(
                id = id,
                uri = ContentUris.withAppendedId(filesUri, id),
                title = displayName,
                displayName = displayName,
                path = interner.intern(path),
                folderName = getParentFolderName(path, interner),
                size = cursor.getLong(MediaStore.Files.FileColumns.SIZE),
                dateAdded = cursor.getLong(MediaStore.Files.FileColumns.DATE_ADDED),
                duration = 0L,
                resolution = "",
                type = MediaType.VIDEO
            )
        }
    }

    private fun scanImages(interner: StringInterner): List<MediaItem> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.TITLE,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_ADDED
        )
        return query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection) { cursor ->
            val id = cursor.getLong(MediaStore.Images.Media._ID)
            val path = cursor.getStringOrEmpty(MediaStore.Images.Media.DATA)
            MediaItem(
                id = id,
                uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                title = cursor.getStringOrEmpty(MediaStore.Images.Media.TITLE).ifEmpty { cursor.getStringOrEmpty(MediaStore.Images.Media.DISPLAY_NAME) },
                displayName = cursor.getStringOrEmpty(MediaStore.Images.Media.DISPLAY_NAME).ifEmpty { "Unknown image" },
                path = path,
                folderName = getParentFolderName(path, interner),
                size = cursor.getLong(MediaStore.Images.Media.SIZE),
                dateAdded = cursor.getLong(MediaStore.Images.Media.DATE_ADDED),
                type = MediaType.PHOTO
            )
        }
    }

    private fun query(
        uri: Uri,
        projection: Array<String>,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
        mapper: (Cursor) -> MediaItem
    ): List<MediaItem> {
        val list = mutableListOf<MediaItem>()
        context.contentResolver.query(uri, projection, selection, selectionArgs, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
            while (cursor.moveToNext()) {
                val item = mapper(cursor)
                if (item.size > 0) list.add(item)
            }
        }
        return list
    }

    private suspend fun <T> captureQuery(block: suspend () -> T): Result<T> {
        return try {
            Result.success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private fun RepositoryScanResult.toLibraryScanResult(): MediaLibraryScanResult {
        return MediaLibraryScanResult(
            videos = videos.toLibraryQueryResult(),
            photos = photos.toLibraryQueryResult()
        )
    }

    private fun RepositoryQueryResult.toLibraryQueryResult(): MediaQueryResult = when (this) {
        is RepositoryQueryResult.Success -> MediaQueryResult.Success(items.map { it.toLibraryMedia() })
        is RepositoryQueryResult.Failure -> MediaQueryResult.Failure(cause)
    }

    private fun RepositoryScanResult.items(): List<MediaItem> =
        videos.itemsOrEmpty() + photos.itemsOrEmpty()

    private fun RepositoryQueryResult.itemsOrEmpty(): List<MediaItem> = when (this) {
        is RepositoryQueryResult.Success -> items
        is RepositoryQueryResult.Failure -> emptyList()
    }

    private fun MediaItem.toLibraryMedia(): LibraryMedia = LibraryMedia(
        id = storageKey,
        title = title,
        displayName = displayName,
        path = path,
        folderName = folderName,
        size = size,
        dateAdded = dateAdded,
        duration = duration,
        resolution = resolution
    )

    private data class RepositoryScanResult(
        val videos: RepositoryQueryResult,
        val photos: RepositoryQueryResult
    )

    private sealed interface RepositoryQueryResult {
        data class Success(val items: List<MediaItem>) : RepositoryQueryResult
        data class Failure(val cause: Throwable) : RepositoryQueryResult
    }

    private fun isInternalPath(path: String): Boolean {
        return path.startsWith("/storage/emulated") || path.startsWith("/sdcard")
    }

    private fun typeFromExtension(ext: String): MediaType? = when (ext) {
        in videoExtensions -> MediaType.VIDEO
        in imageExtensions -> MediaType.PHOTO
        else -> null
    }

    private fun getParentFolderName(path: String, interner: StringInterner? = null): String {
        val name = runCatching { File(path).parentFile?.name }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "Storage"
        return interner?.intern(name) ?: name
    }

    private class StringInterner {
        private val map = ConcurrentHashMap<String, String>()
        fun intern(s: String): String = map.putIfAbsent(s, s) ?: s
    }

    private fun stableFileId(path: String): Long {
        var hash = -3808858705608670555L
        for (i in 0 until path.length) {
            hash = hash xor path[i].code.toLong()
            hash *= 1099511628211L
        }
        return hash or (1L shl 62)
    }

    private fun uniqueDestination(folder: File, fileName: String): File {
        var candidate = File(folder, fileName)
        if (!candidate.exists()) return candidate
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val extension = if (dot > 0) fileName.substring(dot) else ""
        var index = 1
        while (candidate.exists()) {
            candidate = File(folder, "$base ($index)$extension")
            index++
        }
        return candidate
    }

    private fun favoriteKeys(): Set<String> {
        return cachedFavoriteKeys ?: (prefs.getStringSet("favorite_keys", emptySet()) ?: emptySet()).also {
            cachedFavoriteKeys = it
        }
    }

    private fun playlistKeys(): Set<String> {
        return cachedPlaylistKeys ?: (prefs.getString("playlist_keys", "")
            ?.lines()
            ?.filter { it.isNotBlank() }
            ?.toCollection(linkedSetOf())
            ?: emptySet()).also {
                cachedPlaylistKeys = it
            }
    }

    private fun watchedKeys(): Set<String> {
        return cachedWatchedKeys ?: (prefs.getStringSet("watched_keys", emptySet()) ?: emptySet()).also {
            cachedWatchedKeys = it
        }
    }

    /**
     * 返回「last_played_at:<uniqueKey> → timestamp」的内存缓存 Map。
     * 首次调用时扫描一次 prefs.all，之后 savePlaybackProgress 增量更新，
     * 避免 recentPlayedItems 每次都做全量 prefs.all 拷贝。
     */
    private fun lastPlayedMap(): Map<String, Long> {
        return cachedLastPlayedMap ?: run {
            prefs.all.entries
                .filter { it.key.startsWith("last_played_at:") }
                .associate { it.key.removePrefix("last_played_at:") to ((it.value as? Long) ?: 0L) }
                .also { cachedLastPlayedMap = it }
        }
    }

    private val MediaItem.uniqueKey: String
        get() = storageKey

    private fun Cursor.getStringOrEmpty(columnName: String): String {
        val index = getColumnIndex(columnName)
        return if (index >= 0 && !isNull(index)) getString(index) ?: "" else ""
    }

    private fun Cursor.getLong(columnName: String): Long {
        val index = getColumnIndex(columnName)
        return if (index >= 0 && !isNull(index)) getLong(index) else 0L
    }
}

