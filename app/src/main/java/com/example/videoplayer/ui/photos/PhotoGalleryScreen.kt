package com.example.videoplayer.ui.photos

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.videoplayer.data.model.MediaItem
import com.example.videoplayer.ui.gallery.GalleryAspectMode
import com.example.videoplayer.ui.gallery.BindThumbnailFastScroll
import com.example.videoplayer.ui.gallery.GalleryThumbnail
import com.example.videoplayer.ui.gallery.resourceVersionKey
import com.example.videoplayer.ui.theme.GalleryBackground
import com.example.videoplayer.ui.theme.GallerySurface
import com.example.videoplayer.ui.theme.GalleryTextMuted

sealed interface PhotoAccessState {
    data object Available : PhotoAccessState
    data object Requesting : PhotoAccessState
    data class NeedsPermission(val canRequest: Boolean) : PhotoAccessState
    data class QueryFailed(val message: String) : PhotoAccessState
}

@Composable
fun PhotoGalleryScreen(
    photos: List<MediaItem>,
    onNavigateToPhoto: (Long, String) -> Unit,
    state: LazyGridState = rememberLazyGridState(),
    accessState: PhotoAccessState = PhotoAccessState.Available,
    aspectMode: GalleryAspectMode = GalleryAspectMode.SQUARE,
    onRequestPermission: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    BindThumbnailFastScroll(state)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(GalleryBackground)
            .testTag("photo-gallery")
    ) {
        when (accessState) {
            PhotoAccessState.Requesting -> PhotoStateMessage("正在请求图片访问权限…")
            is PhotoAccessState.NeedsPermission -> PhotoPermissionState(
                canRequest = accessState.canRequest,
                onRequestPermission = onRequestPermission,
                onOpenSettings = onOpenSettings
            )
            is PhotoAccessState.QueryFailed -> PhotoQueryError(accessState.message, onRetry)
            PhotoAccessState.Available -> if (photos.isEmpty()) {
            Text(
                text = "这里还没有照片",
                color = GalleryTextMuted,
                modifier = Modifier.align(Alignment.Center)
            )
            } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = state,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(bottom = 104.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(
                    items = photos,
                    key = { it.resourceVersionKey },
                    contentType = { "photo-thumbnail" }
                ) { photo ->
                    Box(
                        modifier = Modifier
                            .aspectRatio(photo.aspectRatio(aspectMode))
                            .clip(RoundedCornerShape(2.dp))
                            .background(GallerySurface)
                            .pointerInput(photo.resourceVersionKey) {
                                detectTapGestures {
                                    onNavigateToPhoto(photo.id, photo.folderName)
                                }
                            }
                            .semantics {
                                role = Role.Button
                                onClick("打开照片") {
                                    onNavigateToPhoto(photo.id, photo.folderName)
                                    true
                                }
                            }
                            .testTag("photo-item:${photo.storageKey}")
                    ) {
                        GalleryThumbnail(photo, Modifier.fillMaxSize())
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun BoxScope.PhotoStateMessage(message: String) {
    Text(message, color = GalleryTextMuted, modifier = Modifier.align(Alignment.Center))
}

@Composable
private fun BoxScope.PhotoPermissionState(
    canRequest: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .align(Alignment.Center)
            .padding(28.dp)
            .testTag("photo-permission-required")
    ) {
        Text(
            if (canRequest) "允许访问图片后，照片会显示在这里。"
            else "图片权限已被永久拒绝，请在系统设置中允许访问。",
            color = GalleryTextMuted
        )
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = if (canRequest) onRequestPermission else onOpenSettings,
            modifier = Modifier.testTag(
                if (canRequest) "request-photo-permission" else "open-photo-settings"
            )
        ) {
            Text(if (canRequest) "允许访问" else "打开设置")
        }
    }
}

@Composable
private fun BoxScope.PhotoQueryError(message: String, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.align(Alignment.Center).padding(28.dp).testTag("photo-query-error")
    ) {
        Text("图片读取失败：$message", color = GalleryTextMuted)
        Spacer(Modifier.height(14.dp))
        Button(onClick = onRetry, modifier = Modifier.testTag("retry-photo-query")) {
            Text("重试")
        }
    }
}

private fun MediaItem.aspectRatio(mode: GalleryAspectMode): Float {
    if (mode == GalleryAspectMode.SQUARE) return 1f
    val parts = resolution.lowercase().split('x', '×')
    val width = parts.getOrNull(0)?.trim()?.toFloatOrNull() ?: return 1f
    val height = parts.getOrNull(1)?.trim()?.toFloatOrNull() ?: return 1f
    return (width / height).coerceIn(0.5f, 2f)
}
