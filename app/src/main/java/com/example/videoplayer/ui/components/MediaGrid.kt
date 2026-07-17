package com.example.videoplayer.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.example.videoplayer.data.model.MediaFolder
import com.example.videoplayer.data.model.MediaItem
import com.example.videoplayer.data.model.MediaType
import com.example.videoplayer.data.repository.MediaRepository
import com.example.videoplayer.ui.theme.AccentPink
import com.example.videoplayer.ui.theme.CardObsidian
import com.example.videoplayer.ui.theme.PrimaryNeonPurple
import com.example.videoplayer.ui.theme.SecondaryNeonCyan
import com.example.videoplayer.ui.theme.TextMuted
import com.example.videoplayer.ui.theme.TextPrimary
import com.example.videoplayer.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaItemCard(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isFavorite: Boolean = false,
    isInPlaylist: Boolean = false,
    isSelected: Boolean = false,
    progressFraction: Float = 0f,
    isLastViewed: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onFavoriteClick: (() -> Unit)? = null,
    onPlaylistClick: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.975f else 1f,
        animationSpec = spring(dampingRatio = 0.86f, stiffness = 720f),
        label = "scale"
    )

    GlassmorphicCard(
        modifier = modifier
            .fillMaxWidth()
            .height(88.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        cornerRadius = 16.dp,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = androidx.compose.foundation.LocalIndication.current,
                    onClick = onClick,
                    onLongClick = onLongClick
                )
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Rounded square thumbnail (Apple style)
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF13151D))
            ) {
                if (item.type == MediaType.AUDIO) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Brush.radialGradient(listOf(PrimaryNeonPurple.copy(alpha = 0.3f), Color.Transparent))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.MusicNote, contentDescription = null, tint = SecondaryNeonCyan, modifier = Modifier.size(32.dp))
                    }
                } else {
                    LightweightMediaPreview(item = item, contentDescription = item.title)
                }

                if (item.type == MediaType.VIDEO) {
                    // Small play overlay
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.4f))
                            .border(0.5.dp, Color.White.copy(alpha = 0.3f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                    // Small duration badge
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.Black.copy(alpha = 0.7f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(formatDuration(item.duration), color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Elegantly integrated progress bar at the bottom of thumbnail
                if (progressFraction > 0f && progressFraction < 0.98f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(Color.White.copy(alpha = 0.15f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progressFraction)
                                .background(
                                    brush = Brush.horizontalGradient(
                                        colors = listOf(PrimaryNeonPurple, SecondaryNeonCyan)
                                    )
                                )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Center details
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = item.displayName,
                    color = if (isLastViewed) SecondaryNeonCyan else TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = formatFileSize(item.size),
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                    if (item.resolution.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(item.resolution, color = TextMuted, fontSize = 9.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Action buttons
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                MediaTypeChip(item.type)
                if (onPlaylistClick != null) {
                    IconButton(
                        onClick = onPlaylistClick,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = if (isInPlaylist) Icons.AutoMirrored.Filled.PlaylistAddCheck else Icons.AutoMirrored.Filled.PlaylistAdd,
                            contentDescription = null,
                            tint = if (isInPlaylist) SecondaryNeonCyan else TextMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                if (onFavoriteClick != null) {
                    IconButton(
                        onClick = onFavoriteClick,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = null,
                            tint = if (isFavorite) AccentPink else TextMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(SecondaryNeonCyan.copy(alpha = 0.08f))
                    .border(1.5.dp, SecondaryNeonCyan, RoundedCornerShape(16.dp))
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaGridTile(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    progressFraction: Float = 0f,
    isLastViewed: Boolean = false,
    onLongClick: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.965f else 1f,
        animationSpec = spring(dampingRatio = 0.86f, stiffness = 720f),
        label = "scale"
    )

    Column(
        modifier = modifier
            .width(IntrinsicSize.Min)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = androidx.compose.foundation.LocalIndication.current,
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        // Rounded square thumbnail card
        GlassmorphicCard(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            cornerRadius = 16.dp,
            shadowElevation = 4.dp
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (item.type == MediaType.AUDIO) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.MusicNote, contentDescription = null, tint = SecondaryNeonCyan, modifier = Modifier.size(38.dp))
                    }
                } else {
                    LightweightMediaPreview(item = item, contentDescription = item.displayName)
                }

                if (item.type == MediaType.VIDEO) {
                    // Play icon overlay
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.40f))
                            .border(0.5.dp, Color.White.copy(alpha = 0.3f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Duration chip in corner
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.7f))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(formatDuration(item.duration), color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Progress bar integrated elegantly at the bottom edge of the thumbnail
                if (progressFraction > 0f && progressFraction < 0.98f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(Color.White.copy(alpha = 0.15f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progressFraction)
                                .background(
                                    brush = Brush.horizontalGradient(
                                        colors = listOf(PrimaryNeonPurple, SecondaryNeonCyan)
                                    )
                                )
                        )
                    }
                }

                if (isSelected) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(SecondaryNeonCyan.copy(alpha = 0.12f))
                            .border(2.dp, SecondaryNeonCyan, RoundedCornerShape(16.dp))
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Title and subtext below the card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp)
        ) {
            Text(
                text = item.displayName,
                color = if (isLastViewed) SecondaryNeonCyan else Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = when {
                    item.type == MediaType.AUDIO -> item.artist.ifBlank { "未知" }
                    item.resolution.isNotBlank() -> item.resolution
                    else -> formatFileSize(item.size)
                },
                color = TextSecondary,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaGalleryTile(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    progressFraction: Float = 0f,
    onLongClick: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.965f else 1f,
        animationSpec = spring(dampingRatio = 0.86f, stiffness = 720f),
        label = "scale"
    )

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = androidx.compose.foundation.LocalIndication.current,
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        if (item.type == MediaType.AUDIO) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF13151D)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = SecondaryNeonCyan,
                    modifier = Modifier.size(32.dp)
                )
            }
        } else {
            LightweightMediaPreview(item = item, contentDescription = null)
        }

        if (item.type == MediaType.VIDEO) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(8.dp)
                    )
                    Text(
                        formatDuration(item.duration),
                        color = Color.White,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (progressFraction > 0f && progressFraction < 0.98f) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(2.5.dp)
                    .background(Color.White.copy(alpha = 0.15f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progressFraction)
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(PrimaryNeonPurple, SecondaryNeonCyan)
                            )
                        )
                )
            }
        }

        if (isSelected) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(SecondaryNeonCyan.copy(alpha = 0.15f))
                    .border(2.dp, SecondaryNeonCyan)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(SecondaryNeonCyan),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

@Composable
private fun MediaTypeChip(type: MediaType) {
    val text = when (type) {
        MediaType.VIDEO -> "视频"
        MediaType.AUDIO -> "音频"
        MediaType.PHOTO -> "图片"
    }
    val color = when (type) {
        MediaType.VIDEO -> PrimaryNeonPurple
        MediaType.AUDIO -> SecondaryNeonCyan
        MediaType.PHOTO -> AccentPink
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.1f))
            .border(0.5.dp, color.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun FolderCard(
    folder: MediaFolder,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = when (folder.name) {
        MediaRepository.FAVORITES -> AccentPink
        MediaRepository.TF_CARD_VIDEOS -> SecondaryNeonCyan
        MediaRepository.INTERNAL_VIDEOS -> PrimaryNeonPurple
        else -> SecondaryNeonCyan
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.975f else 1f,
        animationSpec = spring(dampingRatio = 0.86f, stiffness = 720f),
        label = "scale"
    )

    GlassmorphicCard(
        modifier = modifier
            .fillMaxWidth()
            .height(88.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        cornerRadius = 16.dp,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = interactionSource,
                    indication = androidx.compose.foundation.LocalIndication.current,
                    onClick = onClick
                )
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF13151D)),
                contentAlignment = Alignment.Center
            ) {
                val previewItem = remember(folder.items) {
                    folder.items.firstOrNull { it.type == MediaType.VIDEO || it.type == MediaType.PHOTO }
                }
                if (previewItem == null) {
                    Icon(Icons.Default.Folder, contentDescription = null, tint = accent, modifier = Modifier.size(32.dp))
                } else {
                    LightweightMediaPreview(item = previewItem, contentDescription = null)
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(folderDisplayName(folder.name), color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.height(4.dp))
                Text(folder.path, color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(accent.copy(alpha = 0.1f))
                    .border(0.5.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("${folder.totalCount} 项", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun FolderGridTile(
    folder: MediaFolder,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = when (folder.name) {
        MediaRepository.FAVORITES -> AccentPink
        MediaRepository.TF_CARD_VIDEOS -> SecondaryNeonCyan
        MediaRepository.INTERNAL_VIDEOS -> PrimaryNeonPurple
        else -> SecondaryNeonCyan
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.965f else 1f,
        animationSpec = spring(dampingRatio = 0.86f, stiffness = 720f),
        label = "scale"
    )

    GlassmorphicCard(
        modifier = modifier
            .aspectRatio(1f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        cornerRadius = 16.dp,
        shadowElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = interactionSource,
                    indication = androidx.compose.foundation.LocalIndication.current,
                    onClick = onClick
                )
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF13151D)),
                contentAlignment = Alignment.Center
            ) {
                val previewItem = remember(folder.items) {
                    folder.items.firstOrNull { it.type == MediaType.VIDEO || it.type == MediaType.PHOTO }
                }
                if (previewItem == null) {
                    Icon(Icons.Default.Folder, contentDescription = null, tint = accent, modifier = Modifier.size(32.dp))
                } else {
                    LightweightMediaPreview(item = previewItem, contentDescription = null)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = folderDisplayName(folder.name),
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accent.copy(alpha = 0.1f))
                    .border(0.5.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "${folder.totalCount} 项",
                    color = accent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

fun folderDisplayName(name: String): String = when (name) {
    MediaRepository.ALL_VIDEOS -> "全部视频"
    MediaRepository.ALL_PHOTOS -> "全部照片"
    MediaRepository.INTERNAL_VIDEOS -> "本机视频"
    MediaRepository.TF_CARD_VIDEOS -> "TF 卡视频"
    MediaRepository.RECENT_PLAYED -> "最近播放"
    MediaRepository.RECENT_ADDED -> "最近添加"
    MediaRepository.FAVORITES -> "收藏夹"
    else -> name
}

@Composable
fun mediaPreviewRequest(item: MediaItem): ImageRequest {
    val context = LocalContext.current
    return remember(item.storageKey, context) {
        val builder = ImageRequest.Builder(context)
            .data(item.uri)
            .crossfade(false)
        if (item.type == MediaType.PHOTO && item.displayName.endsWith(".gif", ignoreCase = true)) {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                builder.decoderFactory(ImageDecoderDecoder.Factory())
            } else {
                builder.decoderFactory(GifDecoder.Factory())
            }
        }
        builder.build()
    }
}

@Composable
private fun LightweightMediaPreview(item: MediaItem, contentDescription: String?) {
    if (item.type == MediaType.VIDEO) {
        val context = LocalContext.current
        var bitmapState by remember(item.storageKey) {
            mutableStateOf(VideoThumbnailCache.get(item.storageKey))
        }

        LaunchedEffect(item.storageKey) {
            if (VideoThumbnailCache.get(item.storageKey) == null) {
                val bitmap = VideoThumbnailCache.load(context, item)
                if (bitmap != null) {
                    bitmapState = bitmap
                }
            }
        }

        val currentBitmap = bitmapState
        if (currentBitmap != null) {
            Image(
                bitmap = currentBitmap.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            VideoThumbnailPlaceholder(contentDescription)
        }
    } else {
        AsyncImage(
            model = mediaPreviewRequest(item),
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
private fun VideoThumbnailPlaceholder(contentDescription: String?) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(Color(0xFF151824), Color(0xFF242334)))),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.PlayArrow,
            contentDescription = contentDescription,
            tint = SecondaryNeonCyan.copy(alpha = 0.82f),
            modifier = Modifier.size(34.dp)
        )
    }
}

object VideoThumbnailCache : android.content.ComponentCallbacks2 {
    @Volatile
    private var dirCreated = false

    // Limit concurrent thumbnail decoding to keep scrolling smooth.
    private val thumbnailPermits = Semaphore(3)
    private var lastTrimAtMs = 0L
    // Keep memory cache conservative for large media folders.
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024L / 12L).toInt().coerceIn(8 * 1024, 48 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount / 1024
        }
    }
    // Cache sanitized disk file names derived from storage keys.
    private val fileNameCache = java.util.concurrent.ConcurrentHashMap<String, String>(256)

    fun init(context: android.content.Context) {
        context.registerComponentCallbacks(this)
    }

    override fun onTrimMemory(level: Int) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            cache.evictAll()
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
    override fun onLowMemory() {
        cache.evictAll()
    }

    fun get(key: String): Bitmap? = cache.get(key)

    fun setCustomCover(context: android.content.Context, key: String, bitmap: Bitmap): Boolean {
        val cacheBitmap = bitmap.copy(Bitmap.Config.RGB_565, false)
        val file = diskFile(context, key)
        return try {
            file.outputStream().use { out ->
                check(cacheBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)) { "封面图片写入失败" }
            }
            runCatching { file.setLastModified(System.currentTimeMillis()) }
            cache.put(key, cacheBitmap)
            trimDiskCache(context)
            true
        } catch (e: Exception) {
            cacheBitmap.recycle()
            false
        }
    }

    suspend fun load(context: android.content.Context, item: MediaItem): Bitmap? {
        // 1. Check memory cache
        cache.get(item.storageKey)?.let { return it }

        // 2. Check disk cache. If file exists, decode it immediately without semaphore!
        val bitmap = withContext(Dispatchers.IO) {
            val file = diskFile(context, item.storageKey)
            if (file.exists() && file.length() > 0L) {
                val cachedBitmap = runCatching {
                    val options = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    BitmapFactory.decodeFile(file.absolutePath, options)?.also {
                        runCatching { file.setLastModified(System.currentTimeMillis()) }
                        cache.put(item.storageKey, it)
                    }
                }.getOrNull()
                if (cachedBitmap != null) return@withContext cachedBitmap
            }

            // 3. Disk cache miss: Generate thumbnail. Throttled by semaphore.
            thumbnailPermits.withPermit {
                // Double check memory / disk cache inside semaphore
                cache.get(item.storageKey)?.let { return@withContext it }
                if (file.exists() && file.length() > 0L) {
                    val options = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    return@withContext BitmapFactory.decodeFile(file.absolutePath, options)?.also {
                        cache.put(item.storageKey, it)
                    }
                }

                val generated = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        context.contentResolver.loadThumbnail(item.uri, Size(360, 220), null)
                            .let { src ->
                                if (src.config == android.graphics.Bitmap.Config.RGB_565) src
                                else android.graphics.Bitmap.createBitmap(src.width, src.height, android.graphics.Bitmap.Config.RGB_565).also { dst ->
                                    android.graphics.Canvas(dst).drawBitmap(src, 0f, 0f, null)
                                    src.recycle()
                                }
                            }
                    } else {
                        @Suppress("DEPRECATION")
                        MediaStore.Video.Thumbnails.getThumbnail(
                            context.contentResolver,
                            item.id,
                            MediaStore.Video.Thumbnails.MINI_KIND,
                            null
                        )
                    }
                }.getOrNull()?.let { systemThumb ->
                    if (isBitmapMostlyBlack(systemThumb)) {
                        systemThumb.recycle()
                        null
                    } else {
                        systemThumb
                    }
                } ?: runCatching {
                    getSmartThumbnail(context, item.uri)
                }.getOrNull()

                generated?.also { bitmapResult ->
                    runCatching {
                        file.outputStream().use { out ->
                            bitmapResult.compress(Bitmap.CompressFormat.JPEG, 82, out)
                        }
                        trimDiskCache(context)
                    }
                    if (item.duration <= 0L || item.resolution.isBlank()) {
                        extractAndSaveMetadata(context, item)
                    }
                }
            }
        }
        if (bitmap != null) cache.put(item.storageKey, bitmap)
        return bitmap
    }

    private fun getSmartThumbnail(context: android.content.Context, uri: android.net.Uri): Bitmap? {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 0L
            
            // Base step for seeking: 10% of duration or 3 seconds, whichever is smaller.
            // If duration <= 0, default to 1 second.
            val stepMs = if (durationMs > 10_000L) {
                3000L
            } else if (durationMs > 0L) {
                durationMs / 10
            } else {
                1000L
            }

            var resultBitmap: Bitmap? = null
            
            // Try up to 3 times (1x step, 2x step, 3x step) to find a non-black frame
            for (retry in 1..3) {
                val targetMs = stepMs * retry
                if (durationMs > 0L && targetMs >= durationMs) {
                    break
                }
                
                val timeUs = targetMs * 1000L
                val frame = extractFrameAtTime(retriever, timeUs)
                if (frame != null) {
                    if (!isBitmapMostlyBlack(frame)) {
                        resultBitmap?.recycle()
                        resultBitmap = frame
                        break
                    } else {
                        // It is mostly black. Keep it as fallback if we don't have anything yet.
                        if (resultBitmap == null) {
                            resultBitmap = frame
                        } else {
                            frame.recycle()
                        }
                    }
                }
            }
            
            return resultBitmap
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun extractFrameAtTime(
        retriever: android.media.MediaMetadataRetriever,
        timeUs: Long
    ): Bitmap? {
        val rawBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                retriever.getScaledFrameAtTime(timeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 360, 220)
            } catch (e: Throwable) {
                retriever.getFrameAtTime(timeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        } else {
            retriever.getFrameAtTime(timeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }
        
        return rawBitmap?.let { src ->
            if (src.config == android.graphics.Bitmap.Config.RGB_565) src
            else android.graphics.Bitmap.createBitmap(src.width, src.height, android.graphics.Bitmap.Config.RGB_565).also { dst ->
                android.graphics.Canvas(dst).drawBitmap(src, 0f, 0f, null)
                src.recycle()
            }
        }
    }

    private fun isBitmapMostlyBlack(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return true
        
        var darkPixels = 0
        val samplePoints = 10
        var totalPoints = 0
        
        for (i in 1 until samplePoints) {
            for (j in 1 until samplePoints) {
                val x = (width * i) / samplePoints
                val y = (height * j) / samplePoints
                val pixel = bitmap.getPixel(x, y)
                
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff
                val luminance = 0.299f * r + 0.587f * g + 0.114f * b
                
                if (luminance < 15f) {
                    darkPixels++
                }
                totalPoints++
            }
        }
        return (darkPixels.toFloat() / totalPoints) > 0.85f
    }

    fun diskFile(context: android.content.Context, key: String): File {
        val dir = File(context.cacheDir, "video_thumbs")
        if (!dirCreated) {
            dir.mkdirs()
            dirCreated = true
        }
        // Decode thumbnails off the main thread and reuse hashed cache files.
        val fileName = fileNameCache.getOrPut(key) { "${key.sha256()}.jpg" }
        return File(dir, fileName)
    }

    private fun trimDiskCache(context: android.content.Context) {
        val now = System.currentTimeMillis()
        if (now - lastTrimAtMs < 60_000L) return
        lastTrimAtMs = now
        val files = File(context.cacheDir, "video_thumbs")
            .listFiles { file -> file.extension.equals("jpg", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        var totalBytes = 0L
        files.forEachIndexed { index, file ->
            totalBytes += file.length()
            if (index > 2500 || totalBytes > 128L * 1024L * 1024L) {
                runCatching { file.delete() }
            }
        }
    }

    private fun extractAndSaveMetadata(context: android.content.Context, item: MediaItem) {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, item.uri)
            val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            val widthStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val heightStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            
            val dur = durationStr?.toLongOrNull() ?: 0L
            val w = widthStr?.toIntOrNull() ?: 0
            val h = heightStr?.toIntOrNull() ?: 0
            val resolution = if (w > 0 && h > 0) "${w}x${h}" else ""
            
            if (dur > 0L || resolution.isNotBlank()) {
                val repository = MediaRepository(context.applicationContext)
                repository.saveMetadata(item, dur, resolution)
            }
        } catch (e: Exception) {
            // Ignore
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun String.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0) return "00:00"
    val totalSeconds = durationMs / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) "%02d:%02d:%02d".format(Locale.US, hours, minutes, seconds)
    else "%02d:%02d".format(Locale.US, minutes, seconds)
}

fun formatFileSize(sizeBytes: Long): String {
    if (sizeBytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var size = sizeBytes.toDouble()
    var unitIndex = 0
    while (size >= 1024 && unitIndex < units.lastIndex) {
        size /= 1024
        unitIndex++
    }
    return "%.2f %s".format(Locale.US, size, units[unitIndex])
}
