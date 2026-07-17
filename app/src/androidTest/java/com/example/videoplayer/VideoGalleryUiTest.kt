package com.example.videoplayer

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.example.videoplayer.data.model.MediaItem
import com.example.videoplayer.data.model.MediaType
import com.example.videoplayer.ui.screens.MainGalleryContent
import com.example.videoplayer.ui.theme.VideoPlayerTheme
import org.junit.Rule
import org.junit.Test

class VideoGalleryUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun videoIsInitialDestinationWithoutSearchOrAudio() {
        setGalleryContent()

        composeRule.onNodeWithTag("video-gallery").assertIsDisplayed()
        composeRule.onNodeWithTag("timeline-scrubber").assertIsDisplayed()
        composeRule.onNodeWithTag("continue-watching").assertDoesNotExist()
        composeRule.onNodeWithTag("search-action").assertDoesNotExist()
        composeRule.onNodeWithTag("destination-audio").assertDoesNotExist()
    }

    @Test
    fun continueWatchingOnlyAppearsForUnfinishedVideo() {
        setGalleryContent(progress = mapOf(video.storageKey to 0.42f))

        composeRule.onNodeWithTag("continue-watching").assertIsDisplayed()
    }

    @Test
    fun longPressEntersSelectionMode() {
        setGalleryContent()

        composeRule.onNodeWithTag("video-item:${video.storageKey}")
            .performTouchInput { longClick() }

        composeRule.onNodeWithTag("selection-actions").assertIsDisplayed()
    }

    @Test
    fun photosOpenAsDistinctDestination() {
        setGalleryContent()

        composeRule.onNodeWithTag("destination-photos").performClick()

        composeRule.onNodeWithTag("photo-gallery").assertIsDisplayed()
        composeRule.onNodeWithTag("video-gallery").assertDoesNotExist()
    }

    private fun setGalleryContent(progress: Map<String, Float> = emptyMap()) {
        composeRule.setContent {
            VideoPlayerTheme {
                MainGalleryContent(
                    videos = listOf(video),
                    photos = listOf(photo),
                    playbackProgress = progress,
                    initialColumns = 4,
                    onColumnsChange = {},
                    onNavigateToVideo = { _, _ -> },
                    onNavigateToPhoto = { _, _ -> },
                    onNavigateToLibrary = {},
                    onRefresh = {}
                )
            }
        }
    }

    private companion object {
        val video = MediaItem(
            id = 7L,
            uri = Uri.parse("content://media/video/7"),
            title = "山间清晨",
            displayName = "morning.mp4",
            path = "/storage/emulated/0/Movies/morning.mp4",
            folderName = "Movies",
            size = 1_024L,
            dateAdded = 1_700_000_000L,
            duration = 90_000L,
            type = MediaType.VIDEO
        )
        val photo = MediaItem(
            id = 11L,
            uri = Uri.parse("content://media/images/11"),
            title = "海边",
            displayName = "coast.jpg",
            path = "/storage/emulated/0/Pictures/coast.jpg",
            folderName = "Pictures",
            size = 2_048L,
            dateAdded = 1_699_000_000L,
            type = MediaType.PHOTO
        )
    }
}
