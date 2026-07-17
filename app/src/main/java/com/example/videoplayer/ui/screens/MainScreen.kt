package com.example.videoplayer.ui.screens

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.example.videoplayer.data.library.LibraryMedia
import com.example.videoplayer.data.library.LibraryError
import com.example.videoplayer.data.library.MediaLibraryStore
import com.example.videoplayer.data.model.MediaItem
import com.example.videoplayer.data.model.MediaType
import com.example.videoplayer.data.repository.MediaRepository
import com.example.videoplayer.ui.gallery.VideoGalleryScreen
import com.example.videoplayer.ui.gallery.GalleryColumnStore
import com.example.videoplayer.ui.gallery.GalleryAspectMode
import com.example.videoplayer.ui.gallery.GalleryAspectStore
import com.example.videoplayer.ui.gallery.readGalleryAspectMode
import com.example.videoplayer.ui.gallery.writeGalleryAspectMode
import com.example.videoplayer.ui.gallery.readClampedColumnCount
import com.example.videoplayer.ui.photos.PhotoGalleryScreen
import com.example.videoplayer.ui.photos.PhotoAccessState
import com.example.videoplayer.ui.theme.GalleryBackground
import com.example.videoplayer.ui.theme.GalleryIceBlue
import com.example.videoplayer.ui.theme.GalleryRaisedSurface
import com.example.videoplayer.ui.theme.GallerySurface
import com.example.videoplayer.ui.theme.GalleryText
import com.example.videoplayer.ui.theme.GalleryTextMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class GalleryDestination {
    VIDEOS,
    PHOTOS,
    LIBRARY
}

@Composable
fun MainScreen(
    repository: MediaRepository,
    selectedTab: Int,
    onSelectedTabChange: (Int) -> Unit,
    onNavigateToFolder: (String) -> Unit,
    onNavigateToVideo: (Long, String) -> Unit,
    onNavigateToPhoto: (Long, String) -> Unit,
    onNavigateToSettings: () -> Unit,
    playerReturnGeneration: Int = 0,
    onMediaItemsLoaded: (List<MediaItem>) -> Unit = {}
) {
    val context = LocalContext.current
    val activity = context as Activity
    val scope = rememberCoroutineScope()
    val lifecycleOwner = activity as LifecycleOwner
    val store = remember(repository, scope) { MediaLibraryStore(repository, scope) }
    val libraryState by store.state.collectAsState()
    var hasVideoPermission by remember { mutableStateOf(hasVideoPermission(context)) }
    var hasPhotoPermission by remember { mutableStateOf(hasPhotoPermission(context)) }
    var photoAccessState by remember {
        mutableStateOf<PhotoAccessState>(
            if (hasPhotoPermission) PhotoAccessState.Available
            else PhotoAccessState.NeedsPermission(canRequest = true)
        )
    }
    var pendingDeleteItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }

    fun refreshLibrary() {
        scope.launch { store.refresh(force = true) }
    }

    val deleteRequestLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            refreshLibrary()
        } else if (pendingDeleteItems.isNotEmpty()) {
            Toast.makeText(context, "未删除所选视频", Toast.LENGTH_SHORT).show()
        }
        pendingDeleteItems = emptyList()
    }

    val videoPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasVideoPermission = granted
        if (granted) scope.launch { store.refresh(force = false) }
    }
    val photoPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPhotoPermission = granted
        photoAccessState = if (granted) {
            PhotoAccessState.Available
        } else {
            PhotoAccessState.NeedsPermission(
                canRequest = activity.shouldShowRequestPermissionRationale(photoPermission())
            )
        }
        if (granted) refreshLibrary()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val videoGranted = hasVideoPermission(context)
                val photoGranted = hasPhotoPermission(context)
                if (videoGranted && !hasVideoPermission) {
                    hasVideoPermission = true
                    refreshLibrary()
                }
                if (photoGranted && !hasPhotoPermission) {
                    hasPhotoPermission = true
                    photoAccessState = PhotoAccessState.Available
                    refreshLibrary()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        if (hasVideoPermission) {
            store.refresh(force = false)
        } else {
            videoPermissionLauncher.launch(videoPermission())
        }
    }

    val videos = remember(libraryState.generation, libraryState.videos) {
        libraryState.videos.map { it.toMediaItem(MediaType.VIDEO) }
    }
    val photos = remember(libraryState.generation, libraryState.photos) {
        libraryState.photos.map { it.toMediaItem(MediaType.PHOTO) }
    }
    var playbackProgress by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    LaunchedEffect(videos, playerReturnGeneration) {
        playbackProgress = withContext(Dispatchers.IO) {
            repository.playbackProgressSnapshot(videos)
        }
    }
    LaunchedEffect(videos, photos) {
        onMediaItemsLoaded(videos + photos)
    }

    val columnStore = remember(repository) {
        object : GalleryColumnStore {
            override fun read(): Int = repository.getGalleryColumnCount()
            override fun write(columnCount: Int) = repository.setGalleryColumnCount(columnCount)
        }
    }
    var columnCount by remember { mutableIntStateOf(columnStore.readClampedColumnCount()) }
    val aspectStore = remember(repository) {
        object : GalleryAspectStore {
            override fun read(): String? = repository.getGalleryAspectMode()
            override fun write(value: String) = repository.setGalleryAspectMode(value)
        }
    }
    var aspectMode by remember { mutableStateOf(aspectStore.readGalleryAspectMode()) }

    LaunchedEffect(hasPhotoPermission, libraryState.error) {
        if (hasPhotoPermission) {
            val photoError = (libraryState.error as? LibraryError.PartialQueryFailure)?.photo
            photoAccessState = if (photoError == null) PhotoAccessState.Available
            else PhotoAccessState.QueryFailed(photoError.message ?: "未知错误")
        }
    }

    if (!hasVideoPermission) {
        PermissionScreen(
            title = "允许访问视频",
            message = "需要读取本机视频，才能建立时间画廊。",
            onGrant = { videoPermissionLauncher.launch(videoPermission()) }
        )
        return
    }

    MainGalleryContent(
        videos = videos,
        photos = photos,
        playbackProgress = playbackProgress,
        initialColumns = columnCount,
        onColumnsChange = { requested ->
            columnCount = requested.coerceIn(2, 8)
            columnStore.write(columnCount)
        },
        onNavigateToVideo = onNavigateToVideo,
        onNavigateToPhoto = onNavigateToPhoto,
        onNavigateToLibrary = {},
        onRefresh = ::refreshLibrary,
        initialDestination = GalleryDestination.entries.getOrElse(selectedTab) {
            GalleryDestination.VIDEOS
        },
        onDestinationChange = { destination ->
            onSelectedTabChange(destination.ordinal)
            if (destination == GalleryDestination.PHOTOS && !hasPhotoPermission) {
                photoAccessState = PhotoAccessState.Requesting
                photoPermissionLauncher.launch(photoPermission())
            }
        },
        onOpenLibrarySection = onNavigateToFolder,
        onOpenSettings = onNavigateToSettings,
        isRefreshing = libraryState.isRefreshing,
        aspectMode = aspectMode,
        onAspectModeChange = { mode ->
            aspectMode = mode
            aspectStore.writeGalleryAspectMode(mode)
        },
        photoAccessState = photoAccessState,
        onRequestPhotoPermission = {
            photoAccessState = PhotoAccessState.Requesting
            photoPermissionLauncher.launch(photoPermission())
        },
        onOpenPhotoSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
            )
        },
        onRetryPhotoQuery = ::refreshLibrary,
        onShare = { items -> shareVideos(context, items) },
        onAddToPlaylist = { items ->
            items.forEach { repository.setInPlaylist(it, true) }
            Toast.makeText(context, "已加入播放列表", Toast.LENGTH_SHORT).show()
        },
        onDelete = { items ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    pendingDeleteItems = items
                    val request = MediaStore.createDeleteRequest(
                        context.contentResolver,
                        items.map { it.uri }
                    )
                    deleteRequestLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                } catch (error: Exception) {
                    pendingDeleteItems = emptyList()
                    Toast.makeText(context, "无法请求删除：${error.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
                }
            } else {
                scope.launch(Dispatchers.IO) {
                    val deleted = items.count { item ->
                        runCatching { context.contentResolver.delete(item.uri, null, null) > 0 }
                            .getOrDefault(false)
                    }
                    withContext(Dispatchers.Main) {
                        if (deleted > 0) refreshLibrary()
                        else Toast.makeText(context, "未能删除所选视频", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    )
}

@Composable
fun MainGalleryContent(
    videos: List<MediaItem>,
    photos: List<MediaItem>,
    playbackProgress: Map<String, Float>,
    initialColumns: Int,
    onColumnsChange: (Int) -> Unit,
    onNavigateToVideo: (Long, String) -> Unit,
    onNavigateToPhoto: (Long, String) -> Unit,
    onNavigateToLibrary: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    initialDestination: GalleryDestination = GalleryDestination.VIDEOS,
    onDestinationChange: (GalleryDestination) -> Unit = {},
    onOpenLibrarySection: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    isRefreshing: Boolean = false,
    aspectMode: GalleryAspectMode = GalleryAspectMode.SQUARE,
    onAspectModeChange: (GalleryAspectMode) -> Unit = {},
    photoAccessState: PhotoAccessState = PhotoAccessState.Available,
    onRequestPhotoPermission: () -> Unit = {},
    onOpenPhotoSettings: () -> Unit = {},
    onRetryPhotoQuery: () -> Unit = {},
    onShare: (List<MediaItem>) -> Unit = {},
    onAddToPlaylist: (List<MediaItem>) -> Unit = {},
    onDelete: (List<MediaItem>) -> Unit = {}
) {
    var destination by rememberSaveable { mutableStateOf(initialDestination) }
    val videoGridState = rememberLazyGridState()
    val photoGridState = rememberLazyGridState()

    Scaffold(
        containerColor = GalleryBackground,
        topBar = {
            GalleryTopBar(
                destination = destination,
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                aspectMode = aspectMode,
                onAspectModeChange = onAspectModeChange
            )
        },
        bottomBar = {
            GalleryNavigationBar(
                destination = destination,
                onSelect = { selected ->
                    destination = selected
                    onDestinationChange(selected)
                    if (selected == GalleryDestination.LIBRARY) onNavigateToLibrary()
                }
            )
        },
        modifier = modifier.fillMaxSize()
    ) { padding ->
        when (destination) {
            GalleryDestination.VIDEOS -> VideoGalleryScreen(
                videos = videos,
                playbackProgress = playbackProgress,
                columnCount = initialColumns,
                aspectMode = aspectMode,
                onColumnsChange = onColumnsChange,
                onNavigateToVideo = onNavigateToVideo,
                onShare = onShare,
                onAddToPlaylist = onAddToPlaylist,
                onDelete = onDelete,
                gridState = videoGridState,
                modifier = Modifier.padding(padding)
            )
            GalleryDestination.PHOTOS -> PhotoGalleryScreen(
                photos = photos,
                onNavigateToPhoto = onNavigateToPhoto,
                state = photoGridState,
                accessState = photoAccessState,
                aspectMode = aspectMode,
                onRequestPermission = onRequestPhotoPermission,
                onOpenSettings = onOpenPhotoSettings,
                onRetry = onRetryPhotoQuery,
                modifier = Modifier.padding(padding)
            )
            GalleryDestination.LIBRARY -> LibraryDestinationScreen(
                onOpenSection = onOpenLibrarySection,
                onOpenSettings = onOpenSettings,
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
private fun GalleryTopBar(
    destination: GalleryDestination,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    aspectMode: GalleryAspectMode,
    onAspectModeChange: (GalleryAspectMode) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .background(GalleryBackground.copy(alpha = 0.96f))
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)
    ) {
        Column {
            Text(
                text = when (destination) {
                    GalleryDestination.VIDEOS -> "视频"
                    GalleryDestination.PHOTOS -> "图片"
                    GalleryDestination.LIBRARY -> "资料库"
                },
                color = GalleryText,
                style = MaterialTheme.typography.headlineMedium
            )
            if (destination == GalleryDestination.VIDEOS) {
                Text("按时间浏览本机视频", color = GalleryTextMuted, fontSize = 11.sp)
            }
        }
        Row {
            if (destination != GalleryDestination.LIBRARY) {
                IconButton(
                    onClick = {
                        onAspectModeChange(
                            if (aspectMode == GalleryAspectMode.SQUARE) GalleryAspectMode.ORIGINAL
                            else GalleryAspectMode.SQUARE
                        )
                    },
                    modifier = Modifier.testTag("layout-options")
                ) {
                    Icon(
                        imageVector = Icons.Default.AspectRatio,
                        contentDescription = if (aspectMode == GalleryAspectMode.SQUARE) "使用原始比例" else "使用方形缩略图",
                        tint = GalleryText
                    )
                }
            }
            IconButton(onClick = onRefresh, enabled = !isRefreshing) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = if (isRefreshing) "正在刷新" else "刷新",
                    tint = if (isRefreshing) GalleryTextMuted else GalleryText
                )
            }
        }
    }
}

@Composable
private fun GalleryNavigationBar(
    destination: GalleryDestination,
    onSelect: (GalleryDestination) -> Unit
) {
    NavigationBar(containerColor = GallerySurface.copy(alpha = 0.98f)) {
        DestinationItem(
            destination = GalleryDestination.VIDEOS,
            current = destination,
            icon = Icons.Default.PlayCircleOutline,
            label = "视频",
            tag = "destination-videos",
            onSelect = onSelect
        )
        DestinationItem(
            destination = GalleryDestination.PHOTOS,
            current = destination,
            icon = Icons.Default.Image,
            label = "图片",
            tag = "destination-photos",
            onSelect = onSelect
        )
        DestinationItem(
            destination = GalleryDestination.LIBRARY,
            current = destination,
            icon = Icons.Default.Collections,
            label = "资料库",
            tag = "destination-library",
            onSelect = onSelect
        )
    }
}

@Composable
private fun RowScope.DestinationItem(
    destination: GalleryDestination,
    current: GalleryDestination,
    icon: ImageVector,
    label: String,
    tag: String,
    onSelect: (GalleryDestination) -> Unit
) {
    NavigationBarItem(
        selected = destination == current,
        onClick = { onSelect(destination) },
        icon = { Icon(icon, contentDescription = label) },
        label = { Text(label) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = GalleryBackground,
            selectedTextColor = GalleryIceBlue,
            indicatorColor = GalleryIceBlue,
            unselectedIconColor = GalleryTextMuted,
            unselectedTextColor = GalleryTextMuted
        ),
        modifier = Modifier.testTag(tag)
    )
}

@Composable
private fun LibraryDestinationScreen(
    onOpenSection: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val entries = listOf(
        LibraryEntry("文件夹", Icons.Default.Folder, MediaRepository.ALL_VIDEOS),
        LibraryEntry("收藏", Icons.Default.FavoriteBorder, MediaRepository.FAVORITES),
        LibraryEntry("播放历史", Icons.Default.History, MediaRepository.RECENT_PLAYED),
        LibraryEntry("播放列表", Icons.Default.PlaylistPlay, MediaRepository.PLAYLIST)
    )
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxSize()
            .background(GalleryBackground)
            .padding(14.dp)
            .testTag("library-destination")
    ) {
        entries.forEach { entry ->
            LibraryRow(entry.icon, entry.label) { onOpenSection(entry.folderName) }
        }
        LibraryRow(Icons.Default.Settings, "设置", onOpenSettings)
    }
}

@Composable
private fun LibraryRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        color = GallerySurface,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 15.dp)
        ) {
            Icon(icon, contentDescription = null, tint = GalleryIceBlue)
            Spacer(Modifier.width(12.dp))
            Text(label, color = GalleryText, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun PermissionScreen(title: String, message: String, onGrant: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GalleryBackground),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                Icons.Default.PlayCircleOutline,
                contentDescription = null,
                tint = GalleryIceBlue,
                modifier = Modifier.size(42.dp)
            )
            Spacer(Modifier.height(14.dp))
            Text(title, color = GalleryText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(message, color = GalleryTextMuted, fontSize = 13.sp)
            Spacer(Modifier.height(18.dp))
            Button(onClick = onGrant) { Text("继续") }
        }
    }
}

private data class LibraryEntry(
    val label: String,
    val icon: ImageVector,
    val folderName: String
)

private fun LibraryMedia.toMediaItem(type: MediaType): MediaItem = MediaItem(
    id = mediaStoreId,
    uri = Uri.parse(uri),
    title = title,
    displayName = displayName,
    path = path,
    folderName = folderName,
    size = size,
    dateAdded = dateAdded,
    dateModified = dateModified,
    duration = duration,
    resolution = resolution,
    type = type
)

private fun videoPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

private fun photoPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

private fun hasVideoPermission(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, videoPermission()) == PackageManager.PERMISSION_GRANTED

private fun hasPhotoPermission(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, photoPermission()) == PackageManager.PERMISSION_GRANTED

private fun shareVideos(context: android.content.Context, items: List<MediaItem>) {
    if (items.isEmpty()) return
    val uris = ArrayList(items.map { it.uri })
    val clipData = ClipData.newUri(context.contentResolver, "视频", uris.first()).apply {
        uris.drop(1).forEach { addItem(ClipData.Item(it)) }
    }
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "video/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        this.clipData = clipData
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "分享视频"))
}
