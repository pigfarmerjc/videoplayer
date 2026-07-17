package com.example.videoplayer.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackControllerTest {
    @Test
    fun commandsAreSerializedAndOnlyOneEngineIsActive() = runBlocking {
        val factory = FakeEngineFactory()
        val controller = controller(factory)

        controller.load(session("first"))
        val exo = factory.single(EngineChoice.EXO)
        exo.blockNextPlay = CompletableDeferred()
        val play = async { controller.play() }
        exo.playStarted.await()
        val seek = async { controller.seekTo(4_200L) }

        assertFalse(seek.isCompleted)
        assertEquals(1, factory.activeCount)
        exo.blockNextPlay?.complete(Unit)
        play.await()
        seek.await()

        assertEquals(listOf("load:first", "seek:0", "speed:1.0", "audio:null", "subtitle:null", "pause", "play", "seek:4200"), exo.calls)
        assertEquals(1, factory.maxActiveCount)
    }

    @Test
    fun releaseIsExactOnceEvenWhenRequestedRepeatedly() = runBlocking {
        val factory = FakeEngineFactory()
        val controller = controller(factory)
        controller.load(session("video"))
        val engine = factory.single(EngineChoice.EXO)

        controller.release()
        controller.release()

        assertEquals(1, engine.releaseCount)
        assertEquals(0, factory.activeCount)
        assertTrue(controller.state.value.isReleased)
    }

    @Test
    fun callbacksFromOldGenerationAreRejectedAfterSwitch() = runBlocking {
        val factory = FakeEngineFactory()
        val controller = controller(factory)
        controller.load(session("video"))
        val oldEngine = factory.single(EngineChoice.EXO)
        val oldGeneration = oldEngine.loadedGeneration
        controller.switchEngine(EngineChoice.VLC)
        val newEngine = factory.single(EngineChoice.VLC)

        oldEngine.emit(oldGeneration, EngineEvent.PositionChanged(900L))
        newEngine.emit(newEngine.loadedGeneration, EngineEvent.PositionChanged(5_000L))
        controller.pause()

        assertEquals(5_000L, controller.state.value.positionMs)
        assertEquals(newEngine.loadedGeneration, controller.state.value.generation)
    }

    @Test
    fun switchEngineSnapshotsAndRestoresPlaybackContext() = runBlocking {
        val factory = FakeEngineFactory()
        val controller = controller(factory)
        val initial = session(
            mediaUri = "movie.mkv",
            positionMs = 1_000L,
            playWhenReady = false,
            speed = 1.25f,
            selectedAudioTrackId = "audio-aac",
            selectedSubtitleTrackId = "subtitle-zh"
        )
        controller.load(initial)
        val oldEngine = factory.single(EngineChoice.EXO)
        oldEngine.snapshot = PlaybackSnapshot(
            positionMs = 12_345L,
            isPlaying = true,
            speed = 1.5f,
            selectedAudioTrackId = "audio-pcm",
            selectedSubtitleTrackId = "subtitle-en"
        )

        controller.switchEngine(EngineChoice.VLC)

        val newEngine = factory.single(EngineChoice.VLC)
        assertEquals(1, oldEngine.releaseCount)
        assertEquals(0, oldEngine.activeLoads)
        assertEquals(1, newEngine.activeLoads)
        assertEquals(1, factory.maxActiveCount)
        assertEquals("movie.mkv", newEngine.loadedSession?.mediaUri)
        assertEquals(12_345L, newEngine.loadedSession?.positionMs)
        assertEquals(true, newEngine.loadedSession?.playWhenReady)
        assertEquals(1.5f, newEngine.loadedSession?.speed)
        assertEquals("audio-pcm", newEngine.loadedSession?.selectedAudioTrackId)
        assertEquals("subtitle-en", newEngine.loadedSession?.selectedSubtitleTrackId)
        assertEquals(
            listOf("load:movie.mkv", "seek:12345", "speed:1.5", "audio:audio-pcm", "subtitle:subtitle-en", "play"),
            newEngine.calls
        )
        assertEquals(12_345L, controller.state.value.positionMs)
        assertTrue(controller.state.value.isPlaying)
    }

    @Test
    fun loadPlayPauseSeekAndAudioSelectionPublishState() = runBlocking {
        val factory = FakeEngineFactory()
        val controller = controller(factory)

        controller.load(session("video"))
        controller.play()
        controller.seekTo(8_000L)
        controller.selectAudioTrack("commentary")
        controller.pause()

        val state = controller.state.value
        assertEquals("video", state.session?.mediaUri)
        assertEquals(8_000L, state.positionMs)
        assertEquals("commentary", state.selectedAudioTrackId)
        assertFalse(state.isPlaying)
        assertNull(state.error)
    }

    @Test
    fun pcm24Pcm32AndFloatLittleEndianChooseVlc() {
        val formats = listOf(
            AudioFormatDescriptor.pcmInteger(bitDepth = 24),
            AudioFormatDescriptor.pcmInteger(bitDepth = 32),
            AudioFormatDescriptor.pcmFloat(bitDepth = 32)
        )

        formats.forEach { format ->
            assertEquals(EngineChoice.VLC, PcmCompatibilityPolicy.choose(format))
        }
    }

    @Test
    fun aacAndOpusChooseExo() {
        listOf("audio/mp4a-latm", "audio/aac", "audio/opus").forEach { mimeType ->
            assertEquals(
                EngineChoice.EXO,
                PcmCompatibilityPolicy.choose(AudioFormatDescriptor(mimeType = mimeType))
            )
        }
    }

    private fun controller(factory: FakeEngineFactory): PlaybackController =
        PlaybackController(
            engineFactory = factory,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )

    private fun session(
        mediaUri: String,
        positionMs: Long = 0L,
        playWhenReady: Boolean = false,
        speed: Float = 1f,
        selectedAudioTrackId: String? = null,
        selectedSubtitleTrackId: String? = null
    ) = PlaybackSession(
        mediaUri = mediaUri,
        positionMs = positionMs,
        playWhenReady = playWhenReady,
        speed = speed,
        selectedAudioTrackId = selectedAudioTrackId,
        selectedSubtitleTrackId = selectedSubtitleTrackId
    )

    private class FakeEngineFactory : PlayerEngineFactory {
        private val engines = mutableListOf<FakeEngine>()
        var maxActiveCount = 0
            private set

        val activeCount: Int
            get() = engines.sumOf { it.activeLoads }

        override fun create(choice: EngineChoice): PlayerEngine = FakeEngine(choice) {
            maxActiveCount = maxOf(maxActiveCount, activeCount)
        }.also { engines += it }

        fun single(choice: EngineChoice): FakeEngine = engines.single { it.choice == choice }
    }

    private class FakeEngine(
        override val choice: EngineChoice,
        private val onActiveCountChanged: () -> Unit
    ) : PlayerEngine {
        val calls = mutableListOf<String>()
        val playStarted = CompletableDeferred<Unit>()
        var blockNextPlay: CompletableDeferred<Unit>? = null
        var eventListener: PlayerEngine.Listener? = null
        var loadedGeneration = 0L
        var loadedSession: PlaybackSession? = null
        var snapshot = PlaybackSnapshot()
        var activeLoads = 0
            private set
        var releaseCount = 0
            private set

        override fun setListener(listener: PlayerEngine.Listener?) {
            eventListener = listener
        }

        override suspend fun load(session: PlaybackSession, generation: Long) {
            loadedSession = session
            loadedGeneration = generation
            activeLoads = 1
            calls += "load:${session.mediaUri}"
            onActiveCountChanged()
        }

        override suspend fun play() {
            calls += "play"
            playStarted.complete(Unit)
            blockNextPlay?.await()
        }

        override suspend fun pause() {
            calls += "pause"
        }

        override suspend fun seekTo(positionMs: Long) {
            calls += "seek:$positionMs"
        }

        override suspend fun setPlaybackSpeed(speed: Float) {
            calls += "speed:$speed"
        }

        override suspend fun selectAudioTrack(trackId: String?) {
            calls += "audio:$trackId"
        }

        override suspend fun selectSubtitleTrack(trackId: String?) {
            calls += "subtitle:$trackId"
        }

        override suspend fun snapshot(): PlaybackSnapshot = snapshot

        override suspend fun release() {
            releaseCount++
            activeLoads = 0
            calls += "release"
            onActiveCountChanged()
        }

        fun emit(generation: Long, event: EngineEvent) {
            eventListener?.onEvent(generation, event)
        }
    }
}
