package com.example.videoplayer

import com.example.videoplayer.playback.EngineChoice
import com.example.videoplayer.playback.PlaybackSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class FloatingPlayerManagerTest {
    @Test
    fun sessionStoreUsesOpaqueIdsAndImmutableSnapshots() {
        var now = 1_000L
        val store = FloatingPlaybackSessionStore(
            ttlMs = 10_000L,
            clockMs = { now }
        )
        val first = request("content://video/1", 250L)
        val second = request("content://video/2", 500L)

        val firstId = store.put(first)
        val secondId = store.put(second)

        assertNotEquals(firstId, secondId)
        assertSame(first, store.get(firstId))
        assertSame(second, store.get(secondId))

        val advanced = first.copy(
            playbackSession = first.playbackSession.copy(positionMs = 3_000L)
        )
        store.update(firstId, advanced)

        assertEquals(250L, first.playbackSession.positionMs)
        assertSame(advanced, store.get(firstId))
    }

    @Test
    fun emptyUnknownAndExpiredSessionIdsNeverResolve() {
        var now = 5_000L
        val store = FloatingPlaybackSessionStore(
            ttlMs = 1_000L,
            clockMs = { now }
        )
        val id = store.put(request("content://video/1", 0L))

        assertNull(store.get(""))
        assertNull(store.get("missing"))

        now += 1_001L
        assertNull(store.get(id))
        assertNull(store.take(id))
    }

    @Test
    fun takeProvidesExactlyOneRestoreSnapshot() {
        val store = FloatingPlaybackSessionStore(ttlMs = 5_000L)
        val expected = request("content://video/restore", 4_200L)
        val id = store.put(expected)

        assertSame(expected, store.take(id))
        assertNull(store.take(id))
        assertNull(store.get(id))
    }

    private fun request(uri: String, positionMs: Long) = FloatingPlaybackRequest(
        playbackSession = PlaybackSession(
            mediaUri = uri,
            positionMs = positionMs,
            playWhenReady = true
        ),
        playlist = emptyList(),
        currentIndex = 0,
        engineChoice = EngineChoice.EXO
    )
}
