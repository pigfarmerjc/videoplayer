package com.example.videoplayer.media.thumbnail

class ThumbnailScrollController(
    private val setFastScrolling: (Boolean) -> Unit
) {
    private var isFastScrolling = false

    fun onScrollInProgressChanged(isInProgress: Boolean) {
        if (isFastScrolling == isInProgress) return
        isFastScrolling = isInProgress
        setFastScrolling(isInProgress)
    }
}
