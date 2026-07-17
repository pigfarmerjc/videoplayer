package com.example.videoplayer.ui.gallery

import java.time.DayOfWeek
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

interface VideoTimelineItem {
    val id: String
    val dateAddedEpochSeconds: Long
}

data class TimelineVideo(
    override val id: String,
    override val dateAddedEpochSeconds: Long
) : VideoTimelineItem

enum class VideoSectionKind {
    TODAY,
    YESTERDAY,
    THIS_WEEK,
    MONTH
}

sealed class VideoSectionKey {
    abstract val kind: VideoSectionKind
    abstract val stableKey: String

    object Today : VideoSectionKey() {
        override val kind = VideoSectionKind.TODAY
        override val stableKey = "today"
    }

    object Yesterday : VideoSectionKey() {
        override val kind = VideoSectionKind.YESTERDAY
        override val stableKey = "yesterday"
    }

    object ThisWeek : VideoSectionKey() {
        override val kind = VideoSectionKind.THIS_WEEK
        override val stableKey = "this-week"
    }

    data class Month(val yearMonth: YearMonth) : VideoSectionKey() {
        override val kind = VideoSectionKind.MONTH
        override val stableKey = "month:$yearMonth"
    }
}

data class VideoSection<T : VideoTimelineItem>(
    val key: VideoSectionKey,
    val items: List<T>
) {
    val stableKey: String
        get() = key.stableKey

    val itemKeys: List<String>
        get() = items.map { it.id }
}

fun <T : VideoTimelineItem> groupVideos(
    items: List<T>,
    zoneId: ZoneId,
    now: Instant
): List<VideoSection<T>> {
    if (items.isEmpty()) return emptyList()

    val today = now.atZone(zoneId).toLocalDate()
    val yesterday = today.minusDays(1)
    val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val sortedItems = items.sortedWith(
        compareByDescending<T> { it.dateAddedEpochSeconds }.thenBy { it.id }
    )
    val todayItems = mutableListOf<T>()
    val yesterdayItems = mutableListOf<T>()
    val thisWeekItems = mutableListOf<T>()
    val monthlyItems = linkedMapOf<YearMonth, MutableList<T>>()

    sortedItems.forEach { item ->
        val date = Instant.ofEpochSecond(item.dateAddedEpochSeconds).atZone(zoneId).toLocalDate()
        when {
            date == today -> todayItems += item
            date == yesterday -> yesterdayItems += item
            date in weekStart..today -> thisWeekItems += item
            else -> monthlyItems.getOrPut(YearMonth.from(date)) { mutableListOf() } += item
        }
    }

    return buildList {
        if (todayItems.isNotEmpty()) add(VideoSection(VideoSectionKey.Today, todayItems))
        if (yesterdayItems.isNotEmpty()) add(VideoSection(VideoSectionKey.Yesterday, yesterdayItems))
        if (thisWeekItems.isNotEmpty()) add(VideoSection(VideoSectionKey.ThisWeek, thisWeekItems))
        monthlyItems.entries
            .sortedByDescending { it.key }
            .forEach { (yearMonth, videos) ->
                add(VideoSection(VideoSectionKey.Month(yearMonth), videos))
            }
    }
}
