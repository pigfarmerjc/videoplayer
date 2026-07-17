package com.example.videoplayer.ui.photos

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.example.videoplayer.data.model.MediaItem
import com.example.videoplayer.media.thumbnail.ThumbnailSchedulerProvider
import com.example.videoplayer.media.thumbnail.ThumbnailScrollController
import com.example.videoplayer.ui.gallery.GalleryThumbnail
import com.example.videoplayer.ui.gallery.resourceVersionKey
import com.example.videoplayer.ui.theme.GalleryBackground
import com.example.videoplayer.ui.theme.GallerySurface
import com.example.videoplayer.ui.theme.GalleryTextMuted

@Composable
fun PhotoGalleryScreen(
    photos: List<MediaItem>,
    onNavigateToPhoto: (Long, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val state = rememberLazyGridState()
    val scrollController = remember {
        ThumbnailScrollController(ThumbnailSchedulerProvider::setFastScrolling)
    }

    DisposableEffect(scrollController) {
        onDispose { scrollController.onScrollInProgressChanged(false) }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collect {
            scrollController.onScrollInProgressChanged(it)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(GalleryBackground)
            .testTag("photo-gallery")
    ) {
        if (photos.isEmpty()) {
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
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(2.dp))
                            .background(GallerySurface)
                            .pointerInput(photo.resourceVersionKey) {
                                detectTapGestures {
                                    onNavigateToPhoto(photo.id, photo.folderName)
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
