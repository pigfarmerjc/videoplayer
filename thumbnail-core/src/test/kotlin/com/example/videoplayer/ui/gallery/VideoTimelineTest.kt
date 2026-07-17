package com.example.videoplayer.ui.gallery

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoTimelineTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = instant(2026, 7, 17, 0, 5)

    @Test
    fun groupsVideosAcrossMidnightWithoutUsingTheDeviceZone() {
        val justAfterMidnight = video("today", 2026, 7, 17, 0, 1)
        val justBeforeMidnight = video("yesterday", 2026, 7, 16, 23, 59)

        val sections = groupVideos(listOf(justBeforeMidnight, justAfterMidnight), zone, now)

        assertEquals(
            listOf(VideoSectionKey.Today, VideoSectionKey.Yesterday),
            sections.map { it.key }
        )
        assertEquals(listOf("today"), sections[0].items.map { it.id })
        assertEquals(listOf("yesterday"), sections[1].items.map { it.id })
    }

    @Test
    fun keepsSundayInYesterdayWhenNowIsMondayAtTheWeekBoundary() {
        val monday = instant(2026, 7, 20, 9, 0)
        val sunday = video("sunday", 2026, 7, 19, 22, 0)
        val previousThursday = video("previous-week", 2026, 7, 16, 12, 0)

        val sections = groupVideos(listOf(previousThursday, sunday), zone, monday)

        assertEquals(VideoSectionKey.Yesterday, sections[0].key)
        assertEquals(listOf("sunday"), sections[0].items.map { it.id })
        assertEquals(
            VideoSectionKey.Month(java.time.YearMonth.of(2026, 7)),
            sections[1].key
        )
    }

    @Test
    fun groupsOlderVideosByYearMonthAtMonthBoundary() {
        val newestJuly = video("july", 2026, 7, 13, 8, 0)
        val june = video("june", 2026, 6, 30, 23, 59)
        val may = video("may", 2026, 5, 31, 23, 59)

        val sections = groupVideos(listOf(may, june, newestJuly), zone, now)

        assertEquals(VideoSectionKey.ThisWeek, sections[0].key)
        assertEquals(
            listOf(
                VideoSectionKey.Month(java.time.YearMonth.of(2026, 6)),
                VideoSectionKey.Month(java.time.YearMonth.of(2026, 5))
            ),
            sections.drop(1).map { it.key }
        )
    }

    @Test
    fun sortsSectionsAndItemsByDescendingAddedTimeWithStableItemKeys() {
        val oldest = video("oldest", 2026, 6, 1, 10, 0)
        val newer = video("newer", 2026, 7, 16, 9, 0)
        val newest = video("newest", 2026, 7, 16, 10, 0)

        val sections = groupVideos(listOf(oldest, newer, newest), zone, now)

        assertEquals(listOf("newest", "newer"), sections.first().items.map { it.id })
        assertEquals("yesterday", sections.first().stableKey)
        assertEquals(listOf("newest", "newer"), sections.first().itemKeys)
    }

    @Test
    fun returnsNoSectionsForAnEmptyLibrary() {
        assertTrue(groupVideos(emptyList(), zone, now).isEmpty())
    }

    @Test
    fun derivesOnlyUnfinishedVideosForContinueWatchingAndClampsPersistedColumns() {
        val partial = video("partial", 2026, 7, 16, 10, 0)
        val fresh = video("fresh", 2026, 7, 16, 9, 0)
        val finished = video("finished", 2026, 7, 16, 8, 0)
        val store = RecordingColumnStore(initial = 20)

        val state = VideoGalleryState.create(
            videos = listOf(fresh, finished, partial),
            playbackProgress = mapOf("partial" to 0.4f, "fresh" to 0f, "finished" to 1f),
            persistedColumns = store.read()
        )

        assertEquals(listOf("partial"), state.continueWatching.map { it.video.id })
        assertEquals(8, state.columnCount)
        assertEquals(2, state.persistColumnCount(-5, store))
        assertEquals(2, store.value)
    }

    @Test
    fun repairsAnOutOfRangePersistedColumnCountBeforeTheGalleryUsesIt() {
        val store = RecordingColumnStore(initial = 1)

        val columnCount = store.readClampedColumnCount()

        assertEquals(2, columnCount)
        assertEquals(2, store.value)
    }

    @Test
    fun pinchPreviewIsContinuousAndCommitSnapsOnlyOnRelease() {
        assertEquals(3.2f, previewGalleryColumnCount(startColumns = 4, zoom = 1.25f), 0.001f)
        assertEquals(3, commitGalleryColumnCount(previewColumns = 3.2f))
        assertEquals(2, commitGalleryColumnCount(previewColumns = 1.4f))
        assertEquals(8, commitGalleryColumnCount(previewColumns = 9.1f))
    }

    @Test
    fun continueWatchingIsLimitedToFiveNewestVideos() {
        val videos = (1..6).map { index ->
            TimelineVideo(id = "video-$index", dateAddedEpochSeconds = index.toLong())
        }

        val result = deriveContinueWatching(
            videos = videos,
            playbackProgress = videos.associate { it.id to 0.5f }
        )

        assertEquals(listOf("video-6", "video-5", "video-4", "video-3", "video-2"), result.map { it.video.id })
    }

    private fun video(id: String, year: Int, month: Int, day: Int, hour: Int, minute: Int) =
        TimelineVideo(id, instant(year, month, day, hour, minute).epochSecond)

    private fun instant(year: Int, month: Int, day: Int, hour: Int, minute: Int): Instant =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant()

    private class RecordingColumnStore(initial: Int) : GalleryColumnStore {
        var value = initial

        override fun read(): Int = value

        override fun write(columnCount: Int) {
            value = columnCount
        }
    }
}
