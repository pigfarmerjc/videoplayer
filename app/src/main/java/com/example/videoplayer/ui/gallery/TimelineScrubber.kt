package com.example.videoplayer.ui.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.videoplayer.ui.theme.GalleryIceBlue
import com.example.videoplayer.ui.theme.GalleryRaisedSurface
import kotlinx.coroutines.delay

@Composable
internal fun TimelineScrubber(
    sections: List<VideoSection<GalleryVideo>>,
    activeSectionIndex: Int,
    isScrolling: Boolean,
    onSectionSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var visible by remember { mutableStateOf(true) }
    var heightPx by remember { mutableIntStateOf(1) }

    LaunchedEffect(activeSectionIndex, isScrolling) {
        visible = true
        if (!isScrolling) {
            delay(1_600)
            visible = false
        }
    }

    AnimatedVisibility(
        visible = visible || isScrolling,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier.testTag("timeline-scrubber")
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .background(GalleryRaisedSurface.copy(alpha = 0.9f), RoundedCornerShape(10.dp))
                .padding(horizontal = 5.dp, vertical = 7.dp)
                .onSizeChanged { heightPx = it.height.coerceAtLeast(1) }
                .pointerInput(sections.size, heightPx) {
                    fun select(position: Offset) {
                        if (sections.isEmpty()) return
                        val index = ((position.y / heightPx) * sections.size)
                            .toInt()
                            .coerceIn(0, sections.lastIndex)
                        visible = true
                        onSectionSelected(index)
                    }
                    detectVerticalDragGestures(
                        onDragStart = { select(it) },
                        onVerticalDrag = { change, _ -> select(change.position) }
                    )
                }
        ) {
            sections.forEachIndexed { index, _ ->
                Box(
                    Modifier
                        .size(
                            width = if (index == activeSectionIndex) 4.dp else 2.dp,
                            height = if (index == activeSectionIndex) 16.dp else 10.dp
                        )
                        .background(
                            color = GalleryIceBlue.copy(
                                alpha = if (index == activeSectionIndex) 1f else 0.42f
                            ),
                            shape = RoundedCornerShape(2.dp)
                        )
                )
            }
        }
    }
}
