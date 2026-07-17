package com.example.videoplayer.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.example.videoplayer.data.library.LibraryMedia
import com.example.videoplayer.data.library.MediaLibraryStore
import com.example.videoplayer.data.model.MediaItem
import com.example.videoplayer.data.model.MediaType
import com.example.videoplayer.data.repository.MediaRepository
import com.example.videoplayer.ui.gallery.VideoGalleryScreen
import com.example.videoplayer.ui.gallery.GalleryColumnStore
import com.example.videoplayer.ui.gallery.readClampedColumnCount
import com.example.videoplayer.ui.photos.PhotoGalleryScreen
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
    onMediaItemsLoaded: (List<MediaItem>) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(repository, scope) { MediaLibraryStore(repository, scope) }
    val libraryState by store.state.collectAsState()
    var hasVideoPermission by remember { mutableStateOf(hasVideoPermission(context)) }
    var hasPhotoPermission by remember { mutableStateOf(hasPhotoPermission(context)) }

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
        if (granted) scope.launch { store.refresh(force = true) }
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
    LaunchedEffect(videos) {
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
        onRefresh = { scope.launch { store.refresh(force = true) } },
        initialDestination = GalleryDestination.entries.getOrElse(selectedTab) {
            GalleryDestination.VIDEOS
        },
        onDestinationChange = { destination ->
            onSelectedTabChange(destination.ordinal)
            if (destination == GalleryDestination.PHOTOS && !hasPhotoPermission) {
                photoPermissionLauncher.launch(photoPermission())
            }
        },
        onOpenLibrarySection = onNavigateToFolder,
        onOpenSettings = onNavigateToSettings,
        isRefreshing = libraryState.isRefreshing
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
    isRefreshing: Boolean = false
) {
    var destination by rememberSaveable { mutableStateOf(initialDestination) }

    Scaffold(
        containerColor = GalleryBackground,
        topBar = {
            GalleryTopBar(
                destination = destination,
                isRefreshing = isRefreshing,
                onRefresh = onRefresh
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
                onColumnsChange = onColumnsChange,
                onNavigateToVideo = onNavigateToVideo,
                modifier = Modifier.padding(padding)
            )
            GalleryDestination.PHOTOS -> PhotoGalleryScreen(
                photos = photos,
                onNavigateToPhoto = onNavigateToPhoto,
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
    onRefresh: () -> Unit
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
        IconButton(onClick = onRefresh, enabled = !isRefreshing) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = if (isRefreshing) "正在刷新" else "刷新",
                tint = if (isRefreshing) GalleryTextMuted else GalleryText
            )
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
