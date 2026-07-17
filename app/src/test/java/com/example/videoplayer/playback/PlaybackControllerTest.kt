package com.example.videoplayer.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
    fun successfulReleaseClosesControllerAndCannotReleaseEngineAgain() = runBlocking {
        val factory = FakeEngineFactory()
        val controller = controller(factory)
        controller.load(session("video"))
        val engine = factory.single(EngineChoice.EXO)

        controller.release()
        assertFails<PlaybackControllerClosedException> {
            withTimeout(1_000) { controller.release() }
        }

        assertEquals(1, engine.releaseAttempts)
        assertEquals(1, engine.successfulReleases)
        assertEquals(0, factory.activeCount)
        assertTrue(controller.state.value.isReleased)
    }

    @Test
    fun releaseFailureKeepsEngineAndCanBeRetriedWithoutFalseReleasedState() = runBlocking {
        val factory = FakeEngineFactory()
        val controller = controller(factory)
        controller.load(session("video"))
        val engine = factory.single(EngineChoice.EXO)
        val releaseFailure = IllegalStateException("release failed")
        engine.releaseFailure = releaseFailure

        assertSame(releaseFailure, assertFails<IllegalStateException> { controller.release() })
        assertEquals(1, factory.activeCount)
        assertEquals(EngineChoice.EXO, controller.state.value.engineChoice)
        assertFalse(controller.state.value.isReleased)
        assertSame(releaseFailure, controller.state.value.error)

        controller.release()

        assertEquals(2, engine.releaseAttempts)
        assertEquals(1, engine.successfulReleases)
        assertEquals(0, factory.activeCount)
        assertTrue(controller.state.value.isReleased)
    }

    @Test
    fun switchReleaseFailureNeverActivatesSecondEngineAndRetryRecovers() = runBlocking {
        val factory = FakeEngineFactory()
        val controller = controller(factory)
        controller.load(session("video"))
        val oldEngine = factory.single(EngineChoice.EXO)
        val releaseFailure = IllegalStateException("release failed")
        oldEngine.releaseFailure = releaseFailure

        assertSame(releaseFailure, assertFails<IllegalStateException> { controller.switchEngine(EngineChoice.VLC) })
        assertEquals(1, factory.activeCount)
        assertEquals(1, factory.all(EngineChoice.EXO).size)
        assertTrue(factory.all(EngineChoice.VLC).isEmpty())
        assertEquals(EngineChoice.EXO, controller.state.value.engineChoice)

        controller.switchEngine(EngineChoice.VLC)

        assertEquals(1, factory.activeCount)
        assertEquals(1, factory.maxActiveCount)
        assertEquals(EngineChoice.VLC, controller.state.value.engineChoice)
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

        newEngine.emit(newEngine.loadedGeneration, EngineEvent.PositionChanged(5_000L))
        oldEngine.emit(oldGeneration, EngineEvent.Error(IllegalStateException("stale")))
        newEngine.emit(newEngine.loadedGeneration, EngineEvent.PositionChanged(6_000L))
        controller.switchEngine(EngineChoice.VLC)

        assertEquals(6_000L, controller.state.value.positionMs)
        assertEquals(newEngine.loadedGeneration, controller.state.value.generation)
        assertNull(controller.state.value.error)
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
            playWhenReady = true,
            isPlaying = false,
            speed = 1.5f,
            selectedAudioTrackId = "audio-pcm",
            selectedSubtitleTrackId = "subtitle-en"
        )

        controller.switchEngine(EngineChoice.VLC)

        val newEngine = factory.single(EngineChoice.VLC)
        assertEquals(1, oldEngine.successfulReleases)
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
        assertTrue(controller.state.value.playWhenReady)
        assertFalse(controller.state.value.isPlaying)
    }

    @Test
    fun failedReloadQuarantinesEngineAndRejectsOldAndFailedGenerationCallbacks() = runBlocking {
        val factory = FakeEngineFactory()
        val controller = controller(factory)
        controller.load(session("first"))
        val engine = factory.single(EngineChoice.EXO)
        val oldGeneration = engine.loadedGeneration
        val loadFailure = IllegalStateException("reload failed")
        engine.loadFailure = loadFailure

        assertSame(loadFailure, assertFails<IllegalStateException> { controller.load(session("second", positionMs = 500L)) })
        val failedGeneration = engine.loadedGeneration
        assertTrue(failedGeneration > oldGeneration)
        assertEquals("second", controller.state.value.session?.mediaUri)
        assertNull(controller.state.value.engineChoice)
        assertFalse(controller.state.value.hasUsableEngine)
        assertSame(loadFailure, controller.state.value.error)

        engine.emit(oldGeneration, EngineEvent.PositionChanged(10L))
        engine.emit(failedGeneration, EngineEvent.PositionChanged(20L))
        engine.emit(failedGeneration, EngineEvent.Error(IllegalStateException("callback")))

        assertEquals(500L, controller.state.value.positionMs)
        assertSame(loadFailure, controller.state.value.error)
    }

    @Test
    fun failedSwitchActivationLeavesNoEngineAndLoadRetriesPreferredChoice() = runBlocking {
        val factory = FakeEngineFactory().apply {
            failNextLoad(EngineChoice.VLC, IllegalStateException("vlc load failed"))
        }
        val controller = controller(factory)
        controller.load(session("movie.mkv"))

        assertFails<IllegalStateException> { controller.switchEngine(EngineChoice.VLC) }
        assertEquals(0, factory.activeCount)
        assertNull(controller.state.value.engineChoice)
        assertFalse(controller.state.value.hasUsableEngine)
        assertFails<IllegalStateException> { controller.play() }
        assertFalse(controller.state.value.playWhenReady)
        assertFalse(controller.state.value.isPlaying)

        controller.load(controller.state.value.session!!)

        assertEquals(1, factory.activeCount)
        assertEquals(EngineChoice.VLC, controller.state.value.engineChoice)
        assertTrue(controller.state.value.hasUsableEngine)
    }

    @Test
    fun activationCleanupFailureRetainsQuarantinedEngineUntilRetryReleasesIt() = runBlocking {
        val factory = FakeEngineFactory().apply {
            failNextLoad(EngineChoice.EXO, IllegalStateException("load failed"))
            failNextRelease(EngineChoice.EXO, IllegalStateException("cleanup failed"))
        }
        val controller = controller(factory)

        assertFails<IllegalStateException> { controller.load(session("video")) }
        assertEquals(1, factory.activeCount)
        assertEquals(EngineChoice.EXO, controller.state.value.engineChoice)
        assertFalse(controller.state.value.hasUsableEngine)

        controller.load(session("video"))

        assertEquals(1, factory.activeCount)
        assertEquals(1, factory.maxActiveCount)
        assertEquals(2, factory.all(EngineChoice.EXO).size)
        assertTrue(controller.state.value.hasUsableEngine)
    }

    @Test
    fun terminalReleaseDrainsQueuedCommandsAndLaterSubmitFailsImmediately() = runBlocking {
        val factory = FakeEngineFactory()
        val controller = controller(factory)
        controller.load(session("video"))
        val engine = factory.single(EngineChoice.EXO)
        engine.blockNextPlay = CompletableDeferred()
        val play = async { controller.play() }
        engine.playStarted.await()
        val release = async { controller.release() }
        val queuedSeek = async { runCatching { controller.seekTo(99L) } }

        engine.blockNextPlay?.complete(Unit)
        play.await()
        release.await()

        assertTrue(withTimeout(1_000) { queuedSeek.await() }.exceptionOrNull() is PlaybackControllerClosedException)
        assertFails<PlaybackControllerClosedException> {
            withTimeout(1_000) { controller.pause() }
        }
        Unit
    }

    @Test
    fun cancellingOwnerScopeReleasesActiveEngineBeforeActorCloses() = runBlocking {
        val factory = FakeEngineFactory()
        val owner = SupervisorJob()
        val controller = controller(
            factory,
            CoroutineScope(owner + Dispatchers.Default)
        )
        controller.load(session("video"))
        val engine = factory.single(EngineChoice.EXO)

        owner.cancelAndJoin()

        assertEquals(1, engine.releaseAttempts)
        assertEquals(1, engine.successfulReleases)
        assertEquals(0, factory.activeCount)
        assertNull(controller.state.value.engineChoice)
        assertFalse(controller.state.value.hasUsableEngine)
        assertFails<PlaybackControllerClosedException> {
            withTimeout(1_000) { controller.play() }
        }
        Unit
    }

    @Test
    fun cancellationReleaseFailureQuarantinesEngineAndDrainsCommands() = runBlocking {
        val factory = FakeEngineFactory()
        val owner = SupervisorJob()
        val controller = controller(
            factory,
            CoroutineScope(owner + Dispatchers.Default)
        )
        controller.load(session("video"))
        val engine = factory.single(EngineChoice.EXO)
        val releaseFailure = IllegalStateException("cancel cleanup failed")
        engine.releaseFailure = releaseFailure

        owner.cancelAndJoin()

        assertEquals(1, engine.releaseAttempts)
        assertEquals(0, engine.successfulReleases)
        assertEquals(1, factory.activeCount)
        assertEquals(EngineChoice.EXO, controller.state.value.engineChoice)
        assertFalse(controller.state.value.hasUsableEngine)
        assertSame(releaseFailure, controller.state.value.error)
        assertFails<PlaybackControllerClosedException> {
            withTimeout(1_000) { controller.load(session("other")) }
        }
        assertEquals(1, factory.all(EngineChoice.EXO).size)
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
    fun unsupportedOrAmbiguousPcmDescriptorsChooseExo() {
        val formats = listOf(
            AudioFormatDescriptor.pcmInteger(bitDepth = 16),
            AudioFormatDescriptor.pcmInteger(bitDepth = 24, isSigned = false),
            AudioFormatDescriptor.pcmInteger(bitDepth = 24, byteOrder = PcmByteOrder.BIG_ENDIAN),
            AudioFormatDescriptor(
                mimeType = "audio/raw",
                pcmEncoding = PcmSampleEncoding.INTEGER,
                bitDepth = 24,
                isSigned = true,
                byteOrder = PcmByteOrder.UNSPECIFIED
            ),
            AudioFormatDescriptor(mimeType = "audio/L24"),
            AudioFormatDescriptor(
                mimeType = "audio/L32",
                pcmEncoding = PcmSampleEncoding.INTEGER,
                bitDepth = 32,
                isSigned = true
            ),
            AudioFormatDescriptor(
                mimeType = "audio/aac",
                pcmEncoding = PcmSampleEncoding.INTEGER,
                bitDepth = 24,
                isSigned = true,
                byteOrder = PcmByteOrder.LITTLE_ENDIAN
            ),
            AudioFormatDescriptor.pcmFloat(bitDepth = 24),
            AudioFormatDescriptor.pcmFloat(bitDepth = 64),
            AudioFormatDescriptor(
                mimeType = "audio/raw",
                pcmEncoding = PcmSampleEncoding.FLOAT,
                bitDepth = 32,
                isSigned = false,
                byteOrder = PcmByteOrder.LITTLE_ENDIAN
            )
        )

        formats.forEach { format ->
            assertEquals(format.toString(), EngineChoice.EXO, PcmCompatibilityPolicy.choose(format))
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

    private fun controller(
        factory: FakeEngineFactory,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    ): PlaybackController =
        PlaybackController(
            engineFactory = factory,
            scope = scope
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
        private val nextLoadFailures = mutableMapOf<EngineChoice, Throwable>()
        private val nextReleaseFailures = mutableMapOf<EngineChoice, Throwable>()
        var maxActiveCount = 0
            private set

        val activeCount: Int
            get() = engines.sumOf { it.activeLoads }

        override fun create(choice: EngineChoice): PlayerEngine = FakeEngine(choice) {
            maxActiveCount = maxOf(maxActiveCount, activeCount)
        }.also {
            it.loadFailure = nextLoadFailures.remove(choice)
            it.releaseFailure = nextReleaseFailures.remove(choice)
            engines += it
        }

        fun single(choice: EngineChoice): FakeEngine = engines.single { it.choice == choice }
        fun all(choice: EngineChoice): List<FakeEngine> = engines.filter { it.choice == choice }
        fun failNextLoad(choice: EngineChoice, error: Throwable) { nextLoadFailures[choice] = error }
        fun failNextRelease(choice: EngineChoice, error: Throwable) { nextReleaseFailures[choice] = error }
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
        var loadFailure: Throwable? = null
        var releaseFailure: Throwable? = null
        var activeLoads = 0
            private set
        var releaseAttempts = 0
            private set
        var successfulReleases = 0
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
            loadFailure?.let {
                loadFailure = null
                throw it
            }
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
            releaseAttempts++
            calls += "release"
            releaseFailure?.let {
                releaseFailure = null
                throw it
            }
            activeLoads = 0
            successfulReleases++
            onActiveCountChanged()
        }

        fun emit(generation: Long, event: EngineEvent) {
            eventListener?.onEvent(generation, event)
        }
    }

    private suspend inline fun <reified T : Throwable> assertFails(
        crossinline block: suspend () -> Unit
    ): T = try {
        block()
        fail("Expected ${T::class.java.simpleName}")
        throw AssertionError("unreachable")
    } catch (error: Throwable) {
        if (error !is T) throw error
        error
    }
}
