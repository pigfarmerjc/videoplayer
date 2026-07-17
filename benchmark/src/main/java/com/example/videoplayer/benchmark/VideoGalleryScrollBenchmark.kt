package com.example.videoplayer.benchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoGalleryScrollBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun flingVideoGallery() {
        assumeVideoGalleryScenarioIsAvailable()

        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            iterations = 1,
            startupMode = StartupMode.WARM,
            setupBlock = {
                pressHome()
                startActivityAndWait()
                check(
                    device.wait(
                        Until.hasObject(By.res(packageName, VIDEO_GALLERY_TAG)),
                        VIDEO_GALLERY_TIMEOUT_MS
                    )
                ) { "Video gallery did not appear within the timeout." }
            }
        ) {
            repeat(10) {
                device.findObject(By.res(packageName, VIDEO_GALLERY_TAG)).fling(Direction.UP)
                device.waitForIdle()
            }
        }
    }

    private fun assumeVideoGalleryScenarioIsAvailable() {
        assumeTrue(
            "Task 6 must remove this prerequisite after it adds the video-gallery tag.",
            false
        )
    }

    private companion object {
        const val TARGET_PACKAGE = "com.example.videoplayer"
        const val VIDEO_GALLERY_TAG = "video-gallery"
        const val VIDEO_GALLERY_TIMEOUT_MS = 5_000L
    }
}
