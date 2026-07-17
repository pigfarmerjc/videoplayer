package com.example.videoplayer

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.videoplayer.playback.AndroidPlayerEngineFactory
import com.example.videoplayer.playback.EngineChoice
import com.example.videoplayer.playback.PlaybackController
import com.example.videoplayer.playback.PlaybackSession
import com.example.videoplayer.playback.PlaybackState
import com.example.videoplayer.service.FloatingPlayerService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PcmPlaybackInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun pcmS24LeMkvPlaybackAndFloatingRestore() = runFixtureScenario(
        fileName = "video-pcm-s24le.mkv",
        expectedEngine = EngineChoice.VLC
    )

    @Test
    fun pcmS24LeMovPlaybackAndFloatingRestore() = runFixtureScenario(
        fileName = "video-pcm-s24le.mov",
        expectedEngine = EngineChoice.VLC
    )

    @Test
    fun pcmS32LePlaybackAndFloatingRestoreWhenFixtureIsProvided() = runFixtureScenario(
        fileName = "video-pcm-s32le.mkv",
        expectedEngine = EngineChoice.VLC,
        optionalFixture = true
    )

    @Test
    fun pcmFloat32LePlaybackAndFloatingRestoreWhenFixtureIsProvided() = runFixtureScenario(
        fileName = "video-pcm-f32le.mkv",
        expectedEngine = EngineChoice.VLC,
        optionalFixture = true
    )

    @Test
    fun dualTrackSelectsPcmThenAacAndRestoresFromFloating() = runBlocking {
        val fixture = requireFixture("video-aac-pcm-s24le.mkv")
        val controller = controller()
        try {
            controller.load(PlaybackSession(fixture.toURI().toString()))
            val ready = controller.awaitReady()
            assertEquals(EngineChoice.VLC, ready.engineChoice)
            assertTrue("dual-track fixture must expose two audio tracks", ready.audioTracks.size >= 2)

            val pcm = ready.audioTracks.firstOrNull { it.mimeType?.contains("pcm", ignoreCase = true) == true }
                ?: ready.audioTracks.last()
            controller.selectAudioTrack(pcm.id)
            assertEquals(pcm.id, controller.awaitState { it.selectedAudioTrackId == pcm.id }.selectedAudioTrackId)

            val aac = ready.audioTracks.firstOrNull { it.mimeType?.contains("aac", ignoreCase = true) == true }
                ?: ready.audioTracks.first { it.id != pcm.id }
            controller.selectAudioTrack(aac.id)
            assertEquals(aac.id, controller.awaitState { it.selectedAudioTrackId == aac.id }.selectedAudioTrackId)

            exerciseSeekPausePlayAndFloating(controller)
        } finally {
            controller.release()
        }
    }

    private fun runFixtureScenario(
        fileName: String,
        expectedEngine: EngineChoice,
        optionalFixture: Boolean = false
    ) = runBlocking {
        val fixture = requireFixture(fileName, optionalFixture)
        val controller = controller()
        try {
            controller.load(PlaybackSession(fixture.toURI().toString()))
            val ready = controller.awaitReady()
            assertEquals(expectedEngine, ready.engineChoice)
            assertTrue("an audio track must be selected", ready.audioTracks.any { it.isSelected })
            assertNotNull(ready.selectedAudioTrackId)
            exerciseSeekPausePlayAndFloating(controller)
        } finally {
            controller.release()
        }
    }

    private suspend fun exerciseSeekPausePlayAndFloating(controller: PlaybackController) {
        controller.seekTo(3_000L)
        assertTrue(controller.awaitState { it.positionMs >= 2_500L }.positionMs >= 2_500L)

        controller.pause()
        assertFalse(controller.awaitState { !it.playWhenReady }.playWhenReady)
        controller.play()
        assertTrue(controller.awaitState { it.playWhenReady }.playWhenReady)

        assumeTrue(
            "SYSTEM_ALERT_WINDOW permission is required for the real floating-window round trip",
            Settings.canDrawOverlays(context)
        )
        val state = controller.state.value
        val request = FloatingPlaybackRequest(
            playbackSession = requireNotNull(state.session),
            playlist = emptyList(),
            currentIndex = 0,
            engineChoice = requireNotNull(state.engineChoice)
        )
        val sessionId = FloatingPlayerManager.sessions.put(request)
        val startIntent = FloatingPlayerService.startIntent(context, sessionId)
        context.startForegroundService(startIntent)
        FloatingPlayerManager.awaitActive(sessionId)

        val restoreIntent = Intent(context, FloatingPlayerService::class.java).apply {
            action = FloatingPlayerService.ACTION_RESTORE
            putExtra(FloatingPlayerManager.EXTRA_SESSION_ID, sessionId)
        }
        context.startService(restoreIntent)
        FloatingPlayerManager.awaitInactive(sessionId)
        assertNotNull("floating service must preserve a restorable snapshot", FloatingPlayerManager.sessions.get(sessionId))
    }

    private fun controller() = PlaybackController(
        engineFactory = AndroidPlayerEngineFactory(context)
    )

    private fun requireFixture(name: String, optional: Boolean = false): File {
        val directory = InstrumentationRegistry.getArguments().getString(FIXTURE_DIR_ARGUMENT)
        assumeTrue(
            "Pass -Pandroid.testInstrumentationRunnerArguments.$FIXTURE_DIR_ARGUMENT=/device/path/to/pcm-fixtures",
            !directory.isNullOrBlank()
        )
        val file = File(directory, name)
        assumeTrue(
            if (optional) "optional fixture not provided: $name" else "required fixture missing: ${file.absolutePath}",
            file.isFile && file.canRead()
        )
        return file
    }

    private suspend fun PlaybackController.awaitReady(): PlaybackState = awaitState {
        it.hasUsableEngine && it.isReady && it.audioTracks.isNotEmpty() && it.selectedAudioTrackId != null
    }

    private suspend fun PlaybackController.awaitState(predicate: (PlaybackState) -> Boolean): PlaybackState =
        withTimeout(20_000L) {
            while (true) {
                state.value.let { if (predicate(it)) return@withTimeout it }
                delay(50L)
            }
            error("unreachable")
        }

    private suspend fun FloatingPlayerManager.awaitActive(sessionId: String) {
        withTimeout(10_000L) {
            while (activeSessionId != sessionId) delay(50L)
        }
    }

    private suspend fun FloatingPlayerManager.awaitInactive(sessionId: String) {
        withTimeout(10_000L) {
            while (activeSessionId == sessionId) delay(50L)
        }
    }

    private companion object {
        const val FIXTURE_DIR_ARGUMENT = "pcmFixtureDir"
    }
}
