package com.example.videoplayer.ui.screens

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.example.videoplayer.ui.gallery.commitGalleryColumnCount
import com.example.videoplayer.ui.gallery.previewGalleryColumnCount

fun Modifier.pinchToZoomColumns(
    columns: Int,
    onColumnsChange: (Int) -> Unit
): Modifier = pointerInput(columns) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var cumulativeZoom = 1f
        var previewColumns = columns.toFloat()
        var pinching = false
        do {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } >= 2) {
                pinching = true
                cumulativeZoom *= event.calculateZoom()
                previewColumns = previewGalleryColumnCount(columns, cumulativeZoom)
            }
        } while (event.changes.any { it.pressed })

        if (pinching) onColumnsChange(commitGalleryColumnCount(previewColumns))
    }
}
