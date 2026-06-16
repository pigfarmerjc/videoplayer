package com.example.videoplayer

import com.example.videoplayer.data.model.MediaItem

object FloatingPlayerManager {
    @Volatile var playlist: List<MediaItem> = emptyList()
    @Volatile var currentIndex: Int = 0
    @Volatile var currentPosition: Long = 0L
    @Volatile var useVlcFallback: Boolean = false
    @Volatile var isFloating: Boolean = false
    
    // Dimensions
    @Volatile var width: Int = 640
    @Volatile var height: Int = 360
    
    // Screen position
    @Volatile var x: Int = 100
    @Volatile var y: Int = 200
}
