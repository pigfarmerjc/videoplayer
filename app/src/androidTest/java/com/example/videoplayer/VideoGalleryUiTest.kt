package com.example.videoplayer

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.example.videoplayer.data.model.MediaItem
import com.example.videoplayer.data.model.MediaType
import com.example.videoplayer.ui.screens.MainGalleryContent
import com.example.videoplayer.ui.gallery.GalleryAspectMode
import com.example.videoplayer.ui.photos.PhotoAccessState
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
        composeRule.onNodeWithTag("timeline-scrubber-label").assertIsDisplayed()
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
        composeRule.onNodeWithTag("video-item:${video.storageKey}").assertIsSelected()
    }

    @Test
    fun photosOpenAsDistinctDestination() {
        setGalleryContent()

        composeRule.onNodeWithTag("destination-photos").performClick()

        composeRule.onNodeWithTag("photo-gallery").assertIsDisplayed()
        composeRule.onNodeWithTag("video-gallery").assertDoesNotExist()
    }

    @Test
    fun playlistActionReceivesTheSelectedVideo() {
        var playlistItems = emptyList<MediaItem>()
        setGalleryContent(onAddToPlaylist = { playlistItems = it })
        composeRule.onNodeWithTag("video-item:${video.storageKey}")
            .performTouchInput { longClick() }

        composeRule.onNodeWithTag("selection-playlist").performClick()

        composeRule.runOnIdle { assert(playlistItems == listOf(video)) }
    }

    @Test
    fun photoPermissionStateOffersAnExplicitAction() {
        var permissionRequested = false
        setGalleryContent(
            photoAccessState = PhotoAccessState.NeedsPermission(canRequest = true),
            onRequestPhotoPermission = { permissionRequested = true }
        )

        composeRule.onNodeWithTag("destination-photos").performClick()
        composeRule.onNodeWithTag("photo-permission-required").assertIsDisplayed()
        composeRule.onNodeWithTag("request-photo-permission").performClick()
        composeRule.runOnIdle { assert(permissionRequested) }
    }

    @Test
    fun layoutOptionPersistsOriginalAspectMode() {
        var requestedMode: GalleryAspectMode? = null
        setGalleryContent(onAspectModeChange = { requestedMode = it })

        composeRule.onNodeWithTag("layout-options").performClick()

        composeRule.runOnIdle { assert(requestedMode == GalleryAspectMode.ORIGINAL) }
    }

    private fun setGalleryContent(
        progress: Map<String, Float> = emptyMap(),
        onAddToPlaylist: (List<MediaItem>) -> Unit = {},
        photoAccessState: PhotoAccessState = PhotoAccessState.Available,
        onRequestPhotoPermission: () -> Unit = {},
        onAspectModeChange: (GalleryAspectMode) -> Unit = {}
    ) {
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
                    onRefresh = {},
                    onAddToPlaylist = onAddToPlaylist,
                    photoAccessState = photoAccessState,
                    onRequestPhotoPermission = onRequestPhotoPermission,
                    aspectMode = GalleryAspectMode.SQUARE,
                    onAspectModeChange = onAspectModeChange
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
