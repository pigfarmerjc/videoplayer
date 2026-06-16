package com.example.videoplayer.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.example.videoplayer.data.model.MediaItem
import com.example.videoplayer.data.model.MediaType
import com.example.videoplayer.data.repository.MediaRepository
import com.example.videoplayer.ui.components.MediaGridTile
import com.example.videoplayer.ui.components.MediaItemCard
import com.example.videoplayer.ui.components.folderDisplayName
import com.example.videoplayer.ui.components.GlassmorphicCard
import com.example.videoplayer.ui.theme.CarbonBg
import com.example.videoplayer.ui.theme.CarbonBorder
import com.example.videoplayer.ui.theme.SecondaryNeonCyan
import com.example.videoplayer.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderDetailScreen(
    folderName: String,
    mediaItems: List<MediaItem>,
    repository: MediaRepository,
    onBackClick: () -> Unit,
    onNavigateToVideo: (Long, String) -> Unit,
    onNavigateToAudio: (Long, String) -> Unit,
    onNavigateToPhoto: (Long, String) -> Unit
) {
    val folderItems = remember(folderName, mediaItems) {
        repository.resolveFolderItems(folderName, mediaItems)
    }
    var hiddenMovedKeys by remember(folderName) { mutableStateOf<Set<String>>(emptySet()) }
    val visibleItems = remember(folderItems, hiddenMovedKeys) {
        folderItems.filter { it.storageKey !in hiddenMovedKeys }
    }
    var resumeTrigger by remember { mutableIntStateOf(0) }
    var progressSnapshot by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    LaunchedEffect(visibleItems, resumeTrigger) {
        withContext(Dispatchers.IO) {
            progressSnapshot = repository.playbackProgressSnapshot(visibleItems)
        }
    }
    var lastViewedKey by remember(folderName, visibleItems) { mutableStateOf(repository.lastViewedKey(folderName)) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = repository.lastViewedIndex(folderName, visibleItems)
    )
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = repository.lastViewedIndex(folderName, visibleItems)
    )
    val lifecycleOwner = LocalContext.current as? LifecycleOwner
    val scope = rememberCoroutineScope()
    var gridMode by remember { mutableStateOf(repository.isFolderGridModeEnabled()) }
    // 网格尺寸档位: 140=小, 220=中, 320=大
    var gridSizeDp by remember { mutableIntStateOf(repository.getFolderGridSize()) }
    var selectedKeys by remember(folderName) { mutableStateOf<Set<String>>(emptySet()) }
    var favoriteKeys by remember(folderName, visibleItems) { mutableStateOf(repository.favoriteKeySnapshot()) }
    var playlistKeys by remember(folderName, visibleItems) { mutableStateOf(repository.playlistKeySnapshot()) }
    var showMoveDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val selectedItems = remember(selectedKeys, visibleItems) {
        visibleItems.filter { it.storageKey in selectedKeys }
    }
    val isSelecting = selectedKeys.isNotEmpty()

    fun toggleSelection(item: MediaItem) {
        selectedKeys = if (item.storageKey in selectedKeys) {
            selectedKeys - item.storageKey
        } else {
            selectedKeys + item.storageKey
        }
    }

    fun openItem(item: MediaItem) {
        if (isSelecting) {
            toggleSelection(item)
        } else {
            when (item.type) {
                MediaType.VIDEO -> onNavigateToVideo(item.id, folderName)
                MediaType.AUDIO -> onNavigateToAudio(item.id, folderName)
                MediaType.PHOTO -> onNavigateToPhoto(item.id, folderName)
            }
        }
    }

    DisposableEffect(lifecycleOwner, folderName, visibleItems) {
        if (lifecycleOwner == null) {
            onDispose { }
        } else {
            val owner = lifecycleOwner
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME && visibleItems.isNotEmpty()) {
                    val targetIndex = repository.lastViewedIndex(folderName, visibleItems)
                    lastViewedKey = repository.lastViewedKey(folderName)
                    favoriteKeys = repository.favoriteKeySnapshot()
                    playlistKeys = repository.playlistKeySnapshot()
                    resumeTrigger++
                    scope.launch {
                        if (gridMode) gridState.scrollToItem(targetIndex) else listState.scrollToItem(targetIndex)
                    }
                }
            }
            owner.lifecycle.addObserver(observer)
            onDispose { owner.lifecycle.removeObserver(observer) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (isSelecting) "已选择 ${selectedKeys.size} 项" else folderDisplayName(folderName),
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (isSelecting) selectedKeys = emptySet() else onBackClick() }) {
                        Icon(
                            if (isSelecting) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    if (!isSelecting) {
                        // 网格尺寸切换（仅网格模式显示）
                        if (gridMode) {
                            val sizes = listOf(140 to "S", 220 to "M", 320 to "L")
                            sizes.forEach { (size, label) ->
                                val isActive = gridSizeDp == size
                                Box(
                                    modifier = androidx.compose.ui.Modifier
                                        .padding(end = 2.dp)
                                        .size(28.dp)
                                        .background(
                                            if (isActive) SecondaryNeonCyan.copy(alpha = 0.22f) else Color.Transparent,
                                            RoundedCornerShape(6.dp)
                                        )
                                        .border(
                                            0.5.dp,
                                            if (isActive) SecondaryNeonCyan else Color.White.copy(alpha = 0.3f),
                                            RoundedCornerShape(6.dp)
                                        )
                                        .clickable {
                                            gridSizeDp = size
                                            repository.setFolderGridSize(size)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        color = if (isActive) SecondaryNeonCyan else Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                        IconButton(onClick = {
                            gridMode = !gridMode
                            repository.setFolderGridModeEnabled(gridMode)
                        }) {
                            Icon(
                                imageVector = if (gridMode) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                                contentDescription = if (gridMode) "List" else "Grid",
                                tint = Color.White
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CarbonBg
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = CarbonBg
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            if (visibleItems.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无媒体文件", color = TextSecondary, fontSize = 14.sp)
                }
            } else {
                if (gridMode) {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(gridSizeDp.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(bottom = 96.dp, top = 8.dp)
                    ) {
                        gridItems(
                            visibleItems,
                            key = { it.storageKey },
                            contentType = { it.type }
                        ) { item ->
                            MediaGridTile(
                                item = item,
                                isSelected = item.storageKey in selectedKeys,
                                progressFraction = progressSnapshot[item.storageKey] ?: 0f,
                                isLastViewed = lastViewedKey == item.storageKey,
                                onLongClick = { toggleSelection(item) },
                                onClick = { openItem(item) }
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 96.dp, top = 8.dp)
                    ) {
                        items(
                            visibleItems,
                            key = { it.storageKey },
                            contentType = { it.type }
                        ) { item ->
                            MediaItemCard(
                                item = item,
                                isFavorite = item.storageKey in favoriteKeys,
                                isInPlaylist = item.storageKey in playlistKeys,
                                isSelected = item.storageKey in selectedKeys,
                                progressFraction = progressSnapshot[item.storageKey] ?: 0f,
                                isLastViewed = lastViewedKey == item.storageKey,
                                onLongClick = { toggleSelection(item) },
                                onFavoriteClick = {
                                    val nextFavorite = item.storageKey !in favoriteKeys
                                    repository.setFavorite(item, nextFavorite)
                                    favoriteKeys = if (nextFavorite) favoriteKeys + item.storageKey else favoriteKeys - item.storageKey
                                },
                                onPlaylistClick = {
                                    val nextInPlaylist = item.storageKey !in playlistKeys
                                    repository.setInPlaylist(item, nextInPlaylist)
                                    playlistKeys = if (nextInPlaylist) playlistKeys + item.storageKey else playlistKeys - item.storageKey
                                    scope.launch {
                                        snackbarHostState.showSnackbar(if (nextInPlaylist) "已加入播放列表" else "已移出播放列表")
                                    }
                                },
                                onClick = { openItem(item) }
                            )
                        }
                    }
                }

                val scrollFraction by remember(gridMode, visibleItems) {
                    derivedStateOf {
                        if (gridMode) {
                            val layoutInfo = gridState.layoutInfo
                            val totalItems = layoutInfo.totalItemsCount
                            val firstVisible = layoutInfo.visibleItemsInfo.firstOrNull()
                            if (totalItems > 0 && firstVisible != null) {
                                firstVisible.index.toFloat() / (totalItems - 1).coerceAtLeast(1)
                            } else 0f
                        } else {
                            val layoutInfo = listState.layoutInfo
                            val totalItems = layoutInfo.totalItemsCount
                            val firstVisible = layoutInfo.visibleItemsInfo.firstOrNull()
                            if (totalItems > 0 && firstVisible != null) {
                                firstVisible.index.toFloat() / (totalItems - 1).coerceAtLeast(1)
                            } else 0f
                        }
                    }
                }
                FastScrollHandle(
                    itemCount = visibleItems.size,
                    scrollFraction = scrollFraction,
                    modifier = Modifier.align(Alignment.CenterEnd),
                    onIndexChange = { index ->
                        scope.launch {
                            if (gridMode) gridState.scrollToItem(index) else listState.scrollToItem(index)
                        }
                    }
                )
            }

            // Floating bottom selection panel
            AnimatedVisibility(
                visible = isSelecting,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp)
            ) {
                GlassmorphicCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    cornerRadius = 20.dp,
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "已选 ${selectedKeys.size} 项",
                            color = SecondaryNeonCyan,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    selectedItems.forEach { repository.setInPlaylist(it, true) }
                                    selectedKeys = emptySet()
                                    scope.launch { snackbarHostState.showSnackbar("已加入播放列表") }
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.PlaylistAddCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("加入播放列表", fontSize = 12.sp)
                            }
                            TextButton(
                                onClick = { showMoveDialog = true },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
                            ) {
                                Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("移动到", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showMoveDialog) {
        val targetFolders = remember(mediaItems) {
            mediaItems
                .mapNotNull { it.path.takeIf { path -> path.isNotBlank() }?.let { path -> File(path).parent } }
                .distinct()
                .sorted()
        }
        MoveTargetDialog(
            folders = targetFolders,
            selectedCount = selectedItems.size,
            onDismiss = { showMoveDialog = false },
            onMove = { targetPath ->
                showMoveDialog = false
                val movingItems = selectedItems
                scope.launch {
                    val (moved, failed) = repository.moveItemsToFolder(movingItems, targetPath)
                    if (moved > 0 && failed == 0) {
                        hiddenMovedKeys = hiddenMovedKeys + movingItems.map { it.storageKey }
                    }
                    selectedKeys = emptySet()
                    snackbarHostState.showSnackbar("Moved $moved items, failed $failed items; tap refresh to update")
                }
            }
        )
    }
}

@Composable
private fun FastScrollHandle(
    itemCount: Int,
    scrollFraction: Float,
    modifier: Modifier = Modifier,
    onIndexChange: (Int) -> Unit
) {
    if (itemCount <= 24) return

    val maxTravel = 118.dp
    val yOffset = maxTravel * scrollFraction.coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .padding(end = 2.dp)
            .width(28.dp)
            .height(164.dp)
            .pointerInput(itemCount) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val fraction = (offset.y / size.height).coerceIn(0f, 1f)
                        onIndexChange((fraction * (itemCount - 1)).roundToInt())
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val fraction = (change.position.y / size.height).coerceIn(0f, 1f)
                        onIndexChange((fraction * (itemCount - 1)).roundToInt())
                    }
                )
            },
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .width(6.dp)
                .fillMaxHeight()
                .background(Color.White.copy(alpha = 0.18f), shape = MaterialTheme.shapes.small)
                .align(Alignment.Center)
        )
        Box(
            modifier = Modifier
                .padding(top = yOffset)
                .width(18.dp)
                .height(46.dp)
                .background(SecondaryNeonCyan.copy(alpha = 0.82f), shape = MaterialTheme.shapes.small)
        )
    }
}

@Composable
private fun MoveTargetDialog(
    folders: List<String>,
    selectedCount: Int,
    onDismiss: () -> Unit,
    onMove: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move $selectedCount files") },
        text = {
            if (folders.isEmpty()) {
                Text("No target folders available")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(folders, key = { it }) { path ->
                        TextButton(onClick = { onMove(path) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(File(path).name.ifBlank { path }, fontWeight = FontWeight.Bold)
                                Text(path, color = TextSecondary, fontSize = 11.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
