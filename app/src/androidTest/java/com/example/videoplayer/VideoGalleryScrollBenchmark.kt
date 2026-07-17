package com.example.videoplayer

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.MacrobenchmarkRule
import androidx.benchmark.macro.StartupMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import com.example.videoplayer.performance.MediaTrace
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoGalleryScrollBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun flingVideoGallery() = benchmarkRule.measureRepeated(
        packageName = InstrumentationRegistry.getInstrumentation().targetContext.packageName,
        metrics = listOf(FrameTimingMetric()),
        iterations = 1,
        startupMode = StartupMode.WARM,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            waitForVideoGallery()
        }
    ) {
        MediaTrace.section("video-gallery-scroll") {
            repeat(10) {
                device.findObject(By.res(packageName, "video-gallery")).fling(Direction.UP)
                device.waitForIdle()
            }
        }
    }

    private fun MacrobenchmarkScope.waitForVideoGallery() {
        check(
            device.wait(
                Until.hasObject(By.res(packageName, "video-gallery")),
                VIDEO_GALLERY_TIMEOUT_MS
            )
        ) { "视频画廊未在规定时间内显示" }
    }

    private companion object {
        const val VIDEO_GALLERY_TIMEOUT_MS = 5_000L
    }
}
