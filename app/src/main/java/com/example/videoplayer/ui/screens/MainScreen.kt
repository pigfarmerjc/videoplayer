package com.example.videoplayer.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.videoplayer.data.model.MediaFolder
import com.example.videoplayer.data.model.MediaItem
import com.example.videoplayer.data.model.MediaType
import com.example.videoplayer.data.repository.MediaRepository
import com.example.videoplayer.data.model.LayoutMode
import com.example.videoplayer.ui.components.MediaGalleryTile
import androidx.compose.material.icons.filled.Collections
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import com.example.videoplayer.ui.components.FolderCard
import com.example.videoplayer.ui.components.FolderGridTile
import com.example.videoplayer.ui.components.GlassmorphicCard
import com.example.videoplayer.ui.components.AppBackground
import com.example.videoplayer.ui.components.MediaGridTile
import com.example.videoplayer.ui.components.MediaItemCard
import com.example.videoplayer.ui.components.mediaPreviewRequest
import com.example.videoplayer.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform

@Composable
fun MainScreen(
    repository: MediaRepository,
    selectedTab: Int,
    onSelectedTabChange: (Int) -> Unit,
    onNavigateToFolder: (String) -> Unit,
    onNavigateToVideo: (Long, String) -> Unit,
    onNavigateToAudio: (Long, String) -> Unit,
    onNavigateToPhoto: (Long, String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onMediaItemsLoaded: (List<MediaItem>) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasPermission by remember { mutableStateOf(false) }
    var isStartupLoading by remember { mutableStateOf(!repository.hasFreshMediaCache()) }
    var isLoading by remember { mutableStateOf(false) }
    var mediaItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var folders by remember { mutableStateOf<List<MediaFolder>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }

    // 防抖搜索：300ms 延迟后更新过滤
    var debouncedQuery by remember { mutableStateOf("") }
    LaunchedEffect(searchQuery) {
        delay(300)
        debouncedQuery = searchQuery
    }

    var sortMode by remember { mutableStateOf(SortMode.BY_TIME) }
    var watchedLast by remember { mutableStateOf(repository.isWatchedLastEnabled()) }

    // 网格/列表模式（文件夹、视频 & 音频 Tab 独立保存）
    var folderGridMode by remember { mutableStateOf(repository.isMainFolderGridModeEnabled()) }
    var videoLayoutMode by remember { mutableStateOf(repository.getVideoLayoutMode()) }
    var videoGalleryColumns by remember { mutableIntStateOf(repository.getGalleryColumnCount()) }
    var audioGridMode by remember { mutableStateOf(repository.isAudioGridModeEnabled()) }
    // 视频网格尺寸档位: 140=小, 220=中, 320=大
    var videoGridSizeDp by remember { mutableIntStateOf(repository.getVideoGridSize()) }
    // 首次启动显示主界面功能教程
    var showMainTutorial by remember { mutableStateOf(!repository.isMainTutorialShown()) }
    var didInitialLoad by remember { mutableStateOf(false) }

    // ── 增加生命周期监听与返回刷新 ──
    val lifecycleOwner = LocalContext.current as? LifecycleOwner
    var resumeTrigger by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        if (lifecycleOwner == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    resumeTrigger++
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    val requiredPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    suspend fun reload(forceRefresh: Boolean = false) {
        val showBlockingLoading = forceRefresh || (!didInitialLoad && !repository.hasFreshMediaCache())
        if (showBlockingLoading) isLoading = true
        try {
            val items = repository.scanMedia(forceRefresh)
            mediaItems = items
            onMediaItemsLoaded(items)
            didInitialLoad = true
        } finally {
            if (showBlockingLoading) isLoading = false
        }
    }

    // 动态刷新 Folders
    LaunchedEffect(mediaItems, resumeTrigger) {
        if (hasPermission && mediaItems.isNotEmpty()) {
            folders = repository.getFolders(mediaItems)
        }
    }

    fun hasAllPermissions() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        hasPermission = results.values.all { it }
        if (hasPermission) {
            scope.launch {
                val showStartupAnimation = !repository.hasFreshMediaCache()
                isStartupLoading = showStartupAnimation
                val delayJob = if (showStartupAnimation) launch { delay(1200) } else null
                reload()
                delayJob?.join()
                isStartupLoading = false
            }
        } else {
            isStartupLoading = false
        }
    }

    LaunchedEffect(Unit) {
        hasPermission = hasAllPermissions()
        if (hasPermission) {
            scope.launch {
                val showStartupAnimation = !repository.hasFreshMediaCache()
                isStartupLoading = showStartupAnimation
                val delayJob = if (showStartupAnimation) launch { delay(1200) } else null
                reload()
                delayJob?.join()
                isStartupLoading = false
            }
        } else {
            isStartupLoading = false
            permissionLauncher.launch(requiredPermissions)
        }
    }

    fun toggleFavorite(item: MediaItem) {
        repository.setFavorite(item, !repository.isFavorite(item))
        scope.launch { folders = repository.getFolders(mediaItems) }
    }

    fun togglePlaylist(item: MediaItem) {
        repository.setInPlaylist(item, !repository.isInPlaylist(item))
        scope.launch { folders = repository.getFolders(mediaItems) }
    }

    // ── Offload filtering and sorting to background thread ──
    var filteredFolders by remember { mutableStateOf<List<MediaFolder>>(emptyList()) }
    var filteredVideos by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var filteredAudios by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var filteredPhotos by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var filteredFavorites by remember { mutableStateOf<List<MediaItem>>(emptyList()) }

    LaunchedEffect(folders, debouncedQuery) {
        val result = withContext(Dispatchers.Default) {
            if (debouncedQuery.isBlank()) {
                folders
            } else {
                folders.filter {
                    it.name.contains(debouncedQuery, ignoreCase = true) ||
                        it.path.contains(debouncedQuery, ignoreCase = true) ||
                        it.items.any { item -> item.displayName.contains(debouncedQuery, ignoreCase = true) }
                }
            }
        }
        filteredFolders = result
    }

    LaunchedEffect(mediaItems, debouncedQuery, sortMode, watchedLast, resumeTrigger) {
        val result = withContext(Dispatchers.Default) {
            val watchedSet = if (watchedLast) repository.watchedKeySnapshot() else emptySet()
            FilteredMedia(
                videos = sortMedia(mediaItems.filter { it.type == MediaType.VIDEO && it.displayName.contains(debouncedQuery, true) }, sortMode, watchedLast, watchedSet),
                audios = sortMedia(mediaItems.filter { it.type == MediaType.AUDIO && it.displayName.contains(debouncedQuery, true) }, sortMode, watchedLast, watchedSet),
                photos = sortMedia(mediaItems.filter { it.type == MediaType.PHOTO && it.displayName.contains(debouncedQuery, true) }, sortMode, watchedLast, watchedSet),
                favorites = sortMedia(repository.favoriteItems(mediaItems).filter { it.displayName.contains(debouncedQuery, true) }, sortMode, watchedLast, watchedSet)
            )
        }
        filteredVideos = result.videos
        filteredAudios = result.audios
        filteredPhotos = result.photos
        filteredFavorites = result.favorites
    }

    val progressItems = remember(selectedTab, filteredVideos, filteredAudios, filteredFavorites) {
        when (selectedTab) {
            1 -> filteredVideos
            2 -> filteredAudios
            4 -> filteredFavorites
            else -> emptyList()
        }
    }
    var progressSnapshot by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    LaunchedEffect(progressItems, resumeTrigger) {
        // 防抖 500ms：快速切 Tab 时只在稳定后才触发 IO 读
        delay(500)
        withContext(Dispatchers.IO) {
            progressSnapshot = repository.playbackProgressSnapshot(progressItems)
        }
    }
    val favoriteKeys = remember(folders, mediaItems, resumeTrigger) { repository.favoriteKeySnapshot() }
    val playlistKeys = remember(folders, mediaItems, resumeTrigger) { repository.playlistKeySnapshot() }

    Scaffold(
        containerColor = CarbonBg
    ) { paddingValues ->
        AppBackground(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                Header(
                    onRefresh = { if (hasPermission) scope.launch { reload(forceRefresh = true) } },
                    onSettings = onNavigateToSettings
                )
                SearchBox(searchQuery, onQueryChange = { searchQuery = it })
                SortToolbar(
                    sortMode = sortMode,
                    onSortModeChange = { sortMode = it },
                    watchedLast = watchedLast,
                    onWatchedLastChange = {
                        watchedLast = it
                        repository.setWatchedLastEnabled(it)
                    },
                    // 仅文件夹/视频/音频 Tab 显示网格切换按钮
                    showLayoutToggle = selectedTab == 0 || selectedTab == 1 || selectedTab == 2,
                    currentLayoutMode = when (selectedTab) {
                        0 -> if (folderGridMode) LayoutMode.GRID else LayoutMode.LIST
                        1 -> videoLayoutMode
                        2 -> if (audioGridMode) LayoutMode.GRID else LayoutMode.LIST
                        else -> LayoutMode.LIST
                    },
                    showSizeToggle = when (selectedTab) {
                        1 -> videoLayoutMode == LayoutMode.GRID
                        2 -> audioGridMode
                        else -> false
                    },
                    currentGridSizeDp = videoGridSizeDp,
                    onGridSizeChange = { size ->
                        videoGridSizeDp = size
                        repository.setVideoGridSize(size)
                    },
                    onLayoutModeToggle = {
                        when (selectedTab) {
                            0 -> {
                                folderGridMode = !folderGridMode
                                repository.setMainFolderGridModeEnabled(folderGridMode)
                            }
                            1 -> {
                                videoLayoutMode = when (videoLayoutMode) {
                                    LayoutMode.LIST -> LayoutMode.GRID
                                    LayoutMode.GRID -> LayoutMode.GALLERY
                                    LayoutMode.GALLERY -> LayoutMode.LIST
                                }
                                repository.setVideoLayoutMode(videoLayoutMode)
                            }
                            2 -> {
                                audioGridMode = !audioGridMode
                                repository.setAudioGridModeEnabled(audioGridMode)
                            }
                        }
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))

                when {
                    !hasPermission -> PermissionView { permissionLauncher.launch(requiredPermissions) }
                    isStartupLoading || isLoading -> Box(
                        modifier = Modifier.fillMaxSize().background(CarbonBg),
                    ) {
                        BlackCatLoader(
                            labelText = if (isStartupLoading) "正在启动黑猫播放器..." else "正在刷新媒体库..."
                        )
                    }
                    else -> Box(Modifier.weight(1f)) {
                        when (selectedTab) {
                            0 -> if (folderGridMode) {
                                FolderGridView(filteredFolders, onNavigateToFolder)
                            } else {
                                FolderList(filteredFolders, onNavigateToFolder)
                            }
                            1 -> when (videoLayoutMode) {
                                LayoutMode.LIST -> {
                                    MediaList(filteredVideos, MediaRepository.ALL_VIDEOS, favoriteKeys, playlistKeys, progressSnapshot, repository, ::toggleFavorite, ::togglePlaylist, onNavigateToVideo, onNavigateToAudio, onNavigateToPhoto)
                                }
                                LayoutMode.GRID -> {
                                    MediaGridView(
                                        items = filteredVideos,
                                        folderName = MediaRepository.ALL_VIDEOS,
                                        progressSnapshot = progressSnapshot,
                                        repository = repository,
                                        gridSizeDp = videoGridSizeDp,
                                        onNavigateToVideo = onNavigateToVideo,
                                        onNavigateToAudio = onNavigateToAudio,
                                        onNavigateToPhoto = onNavigateToPhoto
                                    )
                                }
                                LayoutMode.GALLERY -> {
                                    MediaGalleryView(
                                        items = filteredVideos,
                                        folderName = MediaRepository.ALL_VIDEOS,
                                        progressSnapshot = progressSnapshot,
                                        columnsCount = videoGalleryColumns,
                                        onColumnsChange = { cols ->
                                            videoGalleryColumns = cols
                                            repository.setGalleryColumnCount(cols)
                                        },
                                        onNavigateToVideo = onNavigateToVideo,
                                        onNavigateToAudio = onNavigateToAudio,
                                        onNavigateToPhoto = onNavigateToPhoto
                                    )
                                }
                            }
                            2 -> if (audioGridMode) {
                                MediaGridView(
                                    items = filteredAudios,
                                    folderName = MediaRepository.ALL_AUDIOS,
                                    progressSnapshot = progressSnapshot,
                                    repository = repository,
                                    gridSizeDp = videoGridSizeDp,
                                    onNavigateToVideo = onNavigateToVideo,
                                    onNavigateToAudio = onNavigateToAudio,
                                    onNavigateToPhoto = onNavigateToPhoto
                                )
                            } else {
                                MediaList(filteredAudios, MediaRepository.ALL_AUDIOS, favoriteKeys, playlistKeys, progressSnapshot, repository, ::toggleFavorite, ::togglePlaylist, onNavigateToVideo, onNavigateToAudio, onNavigateToPhoto)
                            }
                            3 -> PhotoGrid(filteredPhotos, onNavigateToPhoto)
                            4 -> MediaList(filteredFavorites, MediaRepository.FAVORITES, favoriteKeys, playlistKeys, progressSnapshot, repository, ::toggleFavorite, ::togglePlaylist, onNavigateToVideo, onNavigateToAudio, onNavigateToPhoto)
                        }
                    }
                }
            }

            GlassmorphicBottomNavBar(
                selectedTab = selectedTab,
                onTabSelected = onSelectedTabChange,
                modifier = Modifier.align(Alignment.BottomCenter)
            )

            // 首次启动主界面功能教程
            com.example.videoplayer.ui.components.MainTutorialOverlay(
                visible = showMainTutorial,
                onDismiss = {
                    showMainTutorial = false
                    repository.setMainTutorialShown(true)
                }
            )
        }
    }
}

@Composable
private fun Header(onRefresh: () -> Unit, onSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 22.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            val titleGradient = Brush.linearGradient(
                colors = listOf(PrimaryNeonPurple, SecondaryNeonCyan)
            )
            Text(
                text = "黑猫播放器",
                style = TextStyle(
                    brush = titleGradient,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            Text("4K 本地多媒体播放器", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        Row {
            IconButton(
                onClick = onRefresh,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.15f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh",
                    tint = SecondaryNeonCyan,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = onSettings,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.15f), CircleShape)
            ) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun SearchBox(query: String, onQueryChange: (String) -> Unit) {
    GlassmorphicCard(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        cornerRadius = 14.dp,
        shadowElevation = 1.dp
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, contentDescription = null, tint = SecondaryNeonCyan, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("搜索视频、音频、图片...", color = TextMuted, fontSize = 14.sp) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )
            if (query.isNotEmpty()) {
                Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color.White, modifier = Modifier.size(18.dp).clickable { onQueryChange("") })
            }
        }
    }
}

@Composable
private fun SortToolbar(
    sortMode: SortMode,
    onSortModeChange: (SortMode) -> Unit,
    watchedLast: Boolean,
    onWatchedLastChange: (Boolean) -> Unit,
    showLayoutToggle: Boolean = false,
    currentLayoutMode: LayoutMode = LayoutMode.LIST,
    onLayoutModeToggle: () -> Unit = {},
    showSizeToggle: Boolean = false,
    currentGridSizeDp: Int = 220,
    onGridSizeChange: (Int) -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.AutoMirrored.Filled.Sort, 
                        contentDescription = null, 
                        tint = Color.White, 
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(sortMode.label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                SortMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(mode.label) },
                        onClick = {
                            onSortModeChange(mode)
                            expanded = false
                        }
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showLayoutToggle) {
                if (showSizeToggle) {
                    val sizes = listOf(140 to "S", 220 to "M", 320 to "L")
                    sizes.forEach { (size, label) ->
                        val isActive = currentGridSizeDp == size
                        Box(
                            modifier = Modifier
                                .padding(end = 3.dp)
                                .size(26.dp)
                                .background(
                                    if (isActive) SecondaryNeonCyan.copy(alpha = 0.22f) else Color.Transparent,
                                    RoundedCornerShape(5.dp)
                                )
                                .border(
                                    0.5.dp,
                                    if (isActive) SecondaryNeonCyan else Color.White.copy(alpha = 0.3f),
                                    RoundedCornerShape(5.dp)
                                )
                                .clickable { onGridSizeChange(size) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (isActive) SecondaryNeonCyan else TextSecondary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                }
                val isNotList = currentLayoutMode != LayoutMode.LIST
                IconButton(
                    onClick = onLayoutModeToggle,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isNotList) SecondaryNeonCyan.copy(alpha = 0.22f) else Color.Transparent)
                ) {
                    Icon(
                        imageVector = when (currentLayoutMode) {
                            LayoutMode.LIST -> Icons.Default.GridView
                            LayoutMode.GRID -> Icons.Default.Collections
                            LayoutMode.GALLERY -> Icons.AutoMirrored.Filled.ViewList
                        },
                        contentDescription = "切换布局模式",
                        tint = if (isNotList) SecondaryNeonCyan else TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(6.dp))
            }
            Text("Watched Last", color = TextSecondary, fontSize = 12.sp)
            Spacer(Modifier.width(6.dp))
            Switch(checked = watchedLast, onCheckedChange = onWatchedLastChange)
        }
    }
}

@Composable
private fun FolderList(folders: List<MediaFolder>, onNavigateToFolder: (String) -> Unit) {
    if (folders.isEmpty()) {
        EmptyStateView("No folders found")
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 96.dp)) {
            items(
                folders,
                key = { "${it.name}-${it.path}" },
                contentType = { "folder" }
            ) { folder -> FolderCard(folder = folder, onClick = { onNavigateToFolder(folder.name) }) }
        }
    }
}

@Composable
private fun FolderGridView(folders: List<MediaFolder>, onNavigateToFolder: (String) -> Unit) {
    if (folders.isEmpty()) {
        EmptyStateView("No folders found")
    } else {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(148.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 96.dp, top = 4.dp)
        ) {
            gridItems(
                folders,
                key = { "${it.name}-${it.path}" },
                contentType = { "folder" }
            ) { folder ->
                FolderGridTile(folder = folder, onClick = { onNavigateToFolder(folder.name) })
            }
        }
    }
}

@Composable
private fun MediaList(
    items: List<MediaItem>,
    folderName: String,
    favoriteKeys: Set<String>,
    playlistKeys: Set<String>,
    progressSnapshot: Map<String, Float>,
    repository: MediaRepository,
    onFavorite: (MediaItem) -> Unit,
    onPlaylist: (MediaItem) -> Unit,
    onNavigateToVideo: (Long, String) -> Unit,
    onNavigateToAudio: (Long, String) -> Unit,
    onNavigateToPhoto: (Long, String) -> Unit
) {
    if (items.isEmpty()) {
        EmptyStateView("No media items found")
    } else {
        val lastViewedKey = remember(folderName, items) { repository.lastViewedKey(folderName) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 96.dp)) {
            items(
                items,
                key = { it.storageKey },
                contentType = { it.type }
            ) { item ->
                MediaItemCard(
                    item = item,
                    isFavorite = item.storageKey in favoriteKeys,
                    isInPlaylist = item.storageKey in playlistKeys,
                    progressFraction = progressSnapshot[item.storageKey] ?: 0f,
                    isLastViewed = lastViewedKey == item.storageKey,
                    onFavoriteClick = { onFavorite(item) },
                    onPlaylistClick = { onPlaylist(item) },
                    onClick = {
                        when (item.type) {
                            MediaType.VIDEO -> onNavigateToVideo(item.id, folderName)
                            MediaType.AUDIO -> onNavigateToAudio(item.id, folderName)
                            MediaType.PHOTO -> onNavigateToPhoto(item.id, folderName)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun MediaGridView(
    items: List<MediaItem>,
    folderName: String,
    progressSnapshot: Map<String, Float>,
    repository: MediaRepository,
    gridSizeDp: Int = 220,
    onNavigateToVideo: (Long, String) -> Unit,
    onNavigateToAudio: (Long, String) -> Unit,
    onNavigateToPhoto: (Long, String) -> Unit
) {
    if (items.isEmpty()) {
        EmptyStateView("No media items found")
    } else {
        val lastViewedKey = remember(folderName, items) { repository.lastViewedKey(folderName) }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(gridSizeDp.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 96.dp, top = 4.dp)
        ) {
            gridItems(
                items,
                key = { it.storageKey },
                contentType = { it.type }
            ) { item ->
                MediaGridTile(
                    item = item,
                    progressFraction = progressSnapshot[item.storageKey] ?: 0f,
                    isLastViewed = lastViewedKey == item.storageKey,
                    onClick = {
                        when (item.type) {
                            MediaType.VIDEO -> onNavigateToVideo(item.id, folderName)
                            MediaType.AUDIO -> onNavigateToAudio(item.id, folderName)
                            MediaType.PHOTO -> onNavigateToPhoto(item.id, folderName)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun PhotoGrid(items: List<MediaItem>, onNavigateToPhoto: (Long, String) -> Unit) {
    if (items.isEmpty()) {
        EmptyStateView("No photos found")
    } else {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(112.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 96.dp, top = 4.dp)
        ) {
            gridItems(
                items,
                key = { it.storageKey },
                contentType = { it.type }
            ) { item ->
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF13151D))
                        .clickable { onNavigateToPhoto(item.id, MediaRepository.ALL_PHOTOS) }
                ) {
                    AsyncImage(
                        model = mediaPreviewRequest(item),
                        contentDescription = item.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionView(onGrantClick: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = PrimaryNeonPurple, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(14.dp))
            Text("Media Permission Required", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("The app needs media access to scan your local video, audio, and images.", color = TextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(22.dp))
            Button(onClick = onGrantClick, colors = ButtonDefaults.buttonColors(containerColor = PrimaryNeonPurple), shape = RoundedCornerShape(10.dp)) {
                Text("Grant Permission", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun EmptyStateView(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Info, contentDescription = null, tint = TextMuted, modifier = Modifier.size(44.dp))
            Spacer(Modifier.height(8.dp))
            Text(message, color = TextSecondary, fontSize = 14.sp)
        }
    }
}

@Composable
fun GlassmorphicBottomNavBar(
    selectedTab: Int, 
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .fillMaxWidth()
            .height(66.dp)
            .shadow(
                elevation = 12.dp,
                shape = RoundedCornerShape(22.dp),
                clip = false,
                ambientColor = Color.Black,
                spotColor = Color.Black
            )
            .clip(RoundedCornerShape(22.dp))
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xE60D1117),
                        Color(0xCC070709)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        CarbonBorder,
                        Color(0x1F06B6D4),
                        CarbonBorder.copy(alpha = 0.5f)
                    )
                ),
                shape = RoundedCornerShape(22.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavBarItem(Icons.Default.Folder, "文件夹", selectedTab == 0) { onTabSelected(0) }
            NavBarItem(Icons.Default.PlayCircle, "视频", selectedTab == 1) { onTabSelected(1) }
            NavBarItem(Icons.Default.MusicNote, "音频", selectedTab == 2) { onTabSelected(2) }
            NavBarItem(Icons.Default.Image, "图片", selectedTab == 3) { onTabSelected(3) }
            NavBarItem(Icons.Default.Star, "收藏", selectedTab == 4) { onTabSelected(4) }
        }
    }
}

@Composable
fun NavBarItem(icon: ImageVector, label: String, isSelected: Boolean, onClick: () -> Unit) {
    val selectionProgress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "navSelection"
    )
    val tintColor = androidx.compose.ui.graphics.lerp(TextMuted, SecondaryNeonCyan, selectionProgress)
    
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(SecondaryNeonCyan.copy(alpha = 0.16f * selectionProgress))
            .graphicsLayer {
                scaleX = 1f + 0.04f * selectionProgress
                scaleY = 1f + 0.04f * selectionProgress
            }
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Icon(
            icon, 
            contentDescription = label, 
            tint = tintColor, 
            modifier = Modifier.size(21.dp)
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label, 
            color = tintColor, 
            fontSize = 9.sp, 
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

private enum class SortMode(val label: String) {
    BY_TIME("Time"),
    BY_NAME("Name"),
    BY_SIZE("Size")
}

private data class FilteredMedia(
    val videos: List<MediaItem>,
    val audios: List<MediaItem>,
    val photos: List<MediaItem>,
    val favorites: List<MediaItem>
)

private fun sortMedia(
    items: List<MediaItem>,
    sortMode: SortMode,
    watchedLast: Boolean,
    watchedSet: Set<String>
): List<MediaItem> {
    val sorted = when (sortMode) {
        SortMode.BY_TIME -> items.sortedByDescending { it.dateAdded }
        SortMode.BY_NAME -> items.sortedBy { it.displayName.lowercase() }
        SortMode.BY_SIZE -> items.sortedByDescending { it.size }
    }
    // 使用预计算的 watchedSet 快照，避免每个 item 读 SharedPreferences
    return if (watchedLast) sorted.sortedBy { it.storageKey in watchedSet } else sorted
}

@Composable
fun BlackCatLoader(
    modifier: Modifier = Modifier,
    labelText: String = "正在刷新媒体库..."
) {
    val infiniteTransition = rememberInfiniteTransition(label = "Spin")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "RotationAngle"
    )

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(modifier = Modifier.size(64.dp)) {
            val width = size.width
            val height = size.height
            val strokeWidth = 5f * (width / 64f)
            
            // Draw a background track (faint purple)
            drawCircle(
                color = Color(0x1A8B5CF6),
                radius = (width - strokeWidth) / 2f,
                center = Offset(width / 2f, height / 2f),
                style = Stroke(width = strokeWidth)
            )

            // Draw a rotating gradient arc representing the spinning loader
            withTransform({
                rotate(degrees = angle, pivot = Offset(width / 2f, height / 2f))
            }) {
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            Color(0x0000E5FF),
                            Color(0xFF00E5FF),
                            Color(0xFF8B5CF6),
                            Color(0x008B5CF6)
                        )
                    ),
                    startAngle = 0f,
                    sweepAngle = 270f,
                    useCenter = false,
                    size = Size(width - strokeWidth, height - strokeWidth),
                    topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // Neon loading text
        val titleGradient = Brush.linearGradient(
            colors = listOf(PrimaryNeonPurple, SecondaryNeonCyan)
        )
        Text(
            text = labelText,
            style = TextStyle(
                brush = titleGradient,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        )
    }
}

@Composable
private fun AlienBlackCatIcon(
    eyeColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height

        val scaleX = width / 108f
        val scaleY = height / 108f

        // Draw outer ring glow (shadow)
        drawCircle(
            color = eyeColor.copy(alpha = 0.22f),
            radius = 36f * scaleX,
            center = Offset(54f * scaleX, 54f * scaleY),
            style = Stroke(width = 8f * scaleX)
        )

        // Draw outer ring main line
        drawCircle(
            color = eyeColor,
            radius = 36f * scaleX,
            center = Offset(54f * scaleX, 54f * scaleY),
            style = Stroke(width = 4f * scaleX)
        )

        // Draw central play triangle
        val playPath = Path().apply {
            moveTo(46f * scaleX, 36f * scaleY)
            lineTo(72f * scaleX, 54f * scaleY)
            lineTo(46f * scaleX, 72f * scaleY)
            close()
        }
        drawPath(path = playPath, color = Color.White)
    }
}

@Composable
private fun MediaGalleryView(
    items: List<MediaItem>,
    folderName: String,
    progressSnapshot: Map<String, Float>,
    columnsCount: Int,
    onColumnsChange: (Int) -> Unit,
    onNavigateToVideo: (Long, String) -> Unit,
    onNavigateToAudio: (Long, String) -> Unit,
    onNavigateToPhoto: (Long, String) -> Unit
) {
    if (items.isEmpty()) {
        EmptyStateView("暂无视频文件")
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columnsCount),
            verticalArrangement = Arrangement.spacedBy(1.5.dp),
            horizontalArrangement = Arrangement.spacedBy(1.5.dp),
            contentPadding = PaddingValues(bottom = 96.dp, top = 4.dp),
            modifier = Modifier.pinchToZoomColumns(
                columns = columnsCount,
                onColumnsChange = onColumnsChange
            )
        ) {
            gridItems(
                items,
                key = { it.storageKey },
                contentType = { it.type }
            ) { item ->
                MediaGalleryTile(
                    item = item,
                    progressFraction = progressSnapshot[item.storageKey] ?: 0f,
                    onClick = {
                        when (item.type) {
                            MediaType.VIDEO -> onNavigateToVideo(item.id, folderName)
                            MediaType.AUDIO -> onNavigateToAudio(item.id, folderName)
                            MediaType.PHOTO -> onNavigateToPhoto(item.id, folderName)
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun Modifier.pinchToZoomColumns(
    columns: Int,
    onColumnsChange: (Int) -> Unit
): Modifier {
    val currentColumns by rememberUpdatedState(columns)
    val currentOnChange by rememberUpdatedState(onColumnsChange)
    return this.pointerInput(Unit) {
        var scaleMultiplier = 1f
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var lastDistance = 0f
            var isPinching = false
            
            do {
                val event = awaitPointerEvent()
                val pointers = event.changes.filter { it.pressed }
                if (pointers.size >= 2) {
                    val p1 = pointers[0].position
                    val p2 = pointers[1].position
                    val dx = p1.x - p2.x
                    val dy = p1.y - p2.y
                    val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                    
                    if (!isPinching) {
                        lastDistance = dist
                        isPinching = true
                    } else {
                        if (lastDistance > 0f && dist > 0f) {
                            val ratio = dist / lastDistance
                            scaleMultiplier *= ratio
                            lastDistance = dist
                            
                            val cols = currentColumns
                            if (scaleMultiplier > 1.3f) {
                                if (cols > 2) {
                                    currentOnChange(cols - 1)
                                    scaleMultiplier = 1.0f
                                } else {
                                    scaleMultiplier = 1.3f
                                }
                                event.changes.forEach { it.consume() }
                            } else if (scaleMultiplier < 0.7f) {
                                if (cols < 12) {
                                    currentOnChange(cols + 1)
                                    scaleMultiplier = 1.0f
                                } else {
                                    scaleMultiplier = 0.7f
                                }
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                } else {
                    isPinching = false
                    scaleMultiplier = 1f
                }
            } while (event.changes.any { it.pressed })
        }
    }
}
