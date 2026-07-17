package com.example.videoplayer

import android.content.Context
import android.content.Intent
import com.example.videoplayer.data.model.MediaItem
import com.example.videoplayer.playback.EngineChoice
import com.example.videoplayer.playback.PlaybackSession
import com.example.videoplayer.service.FloatingPlayerService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

data class FloatingPlaybackRequest(
    val playbackSession: PlaybackSession,
    val playlist: List<MediaItem>,
    val currentIndex: Int,
    val engineChoice: EngineChoice,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0
) {
    init {
        require(currentIndex >= 0) { "Current index must not be negative" }
        require(playlist.isEmpty() || currentIndex in playlist.indices) {
            "Current index must point to an item in the playlist"
        }
    }

    val currentItem: MediaItem?
        get() = playlist.getOrNull(currentIndex)
}

data class FloatingWindowState(
    val width: Int = 640,
    val height: Int = 360,
    val x: Int = 100,
    val y: Int = 200
)

class FloatingPlaybackSessionStore(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clockMs: () -> Long = System::currentTimeMillis
) {
    private data class Entry(
        val request: FloatingPlaybackRequest,
        val expiresAtMs: Long
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    init {
        require(ttlMs > 0L) { "Session TTL must be positive" }
    }

    fun put(request: FloatingPlaybackRequest): String {
        removeExpired()
        return UUID.randomUUID().toString().also { id ->
            entries[id] = Entry(request, clockMs() + ttlMs)
        }
    }

    fun get(sessionId: String?): FloatingPlaybackRequest? {
        if (sessionId.isNullOrBlank()) return null
        val entry = entries[sessionId] ?: return null
        if (entry.expiresAtMs <= clockMs()) {
            entries.remove(sessionId, entry)
            return null
        }
        return entry.request
    }

    fun update(sessionId: String, request: FloatingPlaybackRequest): Boolean {
        if (get(sessionId) == null) return false
        entries[sessionId] = Entry(request, clockMs() + ttlMs)
        return true
    }

    fun take(sessionId: String?): FloatingPlaybackRequest? {
        val request = get(sessionId) ?: return null
        return if (entries.remove(sessionId) != null) request else null
    }

    fun remove(sessionId: String?) {
        if (!sessionId.isNullOrBlank()) entries.remove(sessionId)
    }

    private fun removeExpired() {
        val now = clockMs()
        entries.entries.removeIf { it.value.expiresAtMs <= now }
    }

    companion object {
        const val DEFAULT_TTL_MS = 30 * 60 * 1_000L
    }
}

object FloatingPlayerManager {
    const val EXTRA_SESSION_ID = "com.example.videoplayer.extra.PLAYBACK_SESSION_ID"

    val sessions = FloatingPlaybackSessionStore()

    private val activeSession = AtomicReference<String?>(null)
    private val window = AtomicReference(FloatingWindowState())

    val activeSessionId: String?
        get() = activeSession.get()

    val windowState: FloatingWindowState
        get() = window.get()

    fun markActive(sessionId: String) {
        activeSession.set(sessionId)
    }

    fun markInactive(sessionId: String?) {
        if (sessionId == null) {
            activeSession.set(null)
        } else {
            activeSession.compareAndSet(sessionId, null)
        }
    }

    fun updateWindow(transform: (FloatingWindowState) -> FloatingWindowState) {
        while (true) {
            val current = window.get()
            if (window.compareAndSet(current, transform(current))) return
        }
    }

    fun startIntent(context: Context, sessionId: String): Intent =
        FloatingPlayerService.startIntent(context, sessionId)
}
