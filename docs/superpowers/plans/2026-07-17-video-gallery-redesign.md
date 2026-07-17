# Video Gallery Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将应用重构为高性能视频时间画廊与独立图片入口，移除独立音频和搜索，并提供可打断手势、跟手进度预览及可靠的 PCM S24 LE 双内核播放。

**Architecture:** 先用纯 Kotlin 状态与调度组件建立测试边界，再让 Compose 页面和平台播放器适配这些边界。媒体库以 `StateFlow` 和 generation 保证唯一快照；播放以 `PlaybackSession`/`PlaybackController` 串行化资源；所有手势共享 `GestureSession`，缩略图和预览帧都采用 latest-wins 请求模型。

**Tech Stack:** Kotlin 1.9.22、Jetpack Compose、Media3 ExoPlayer 1.3.1、LibVLC 3.7.0、Coroutines/Flow、Coil 2.6.0、JUnit4、AndroidX Benchmark/Perfetto（仪器验证）。

## Global Constraints

- `minSdk = 26`、`targetSdk = 34`，不得降低现有 ABI 覆盖。
- 所有中文源码与资源使用 UTF-8，修复乱码后不得引入新的乱码。
- 删除独立音频库、音频权限、音频页面与搜索；保留视频内音轨选择、均衡器和高精度 PCM。
- PCM S24 LE 是发布红线：MKV/MOV 有声、可 seek、可切音轨、可完成全屏与浮窗往返。
- 快速滚动时不启动新的重型缩略图解码；过期结果不得提交 UI。
- 手势从 presentation value 继续、携带释放速度、允许中断，并响应系统动画缩放。
- 每个任务只修改列出的职责，保持现有 Kotlin/Compose 风格。

---

## Phase A — Product surface and observable baselines

### Task 1: Remove standalone audio and search

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/example/videoplayer/MainActivity.kt`
- Modify: `app/src/main/java/com/example/videoplayer/data/repository/MediaRepository.kt`
- Modify: `app/src/main/java/com/example/videoplayer/ui/screens/MainScreen.kt`
- Delete: `app/src/main/java/com/example/videoplayer/ui/screens/AudioPlayerScreen.kt`
- Test: `app/src/test/java/com/example/videoplayer/ProductSurfaceContractTest.kt`

**Interfaces:**
- Produces: navigation destinations `main`, `folder/{folderName}`, `video/{videoId}/{folderName}`, `photo/{photoId}/{folderName}`, `settings`; no `audio/*` destination.
- Produces: permission request set containing video and image permissions only.

- [ ] **Step 1: Add a failing source-contract test**

```kotlin
class ProductSurfaceContractTest {
    @Test fun standaloneAudioAndSearchAreAbsent() {
        val root = File(System.getProperty("user.dir"))
        val source = File(root, "src/main/java/com/example/videoplayer").walkTopDown()
            .filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertFalse(source.contains("AudioPlayerScreen("))
        assertFalse(source.contains("READ_MEDIA_AUDIO"))
        assertFalse(source.contains("SearchBar("))
        assertFalse(source.contains("Icons.Default.Search"))
    }
}
```

- [ ] **Step 2: Run the contract test and verify failure**

Run: `./gradlew.bat testDebugUnitTest --tests "*.ProductSurfaceContractTest"`  
Expected: FAIL because audio route/permission and search UI still exist.

- [ ] **Step 3: Remove audio routes, page, scan branch, permission, tab and search state**

Keep `MediaItem` audio-track semantics out of scope; remove only standalone `MediaType.AUDIO` UI/data paths. Change permission construction to:

```kotlin
val requiredPermissions = if (Build.VERSION.SDK_INT >= 33) {
    arrayOf(
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_IMAGES
    )
} else {
    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}
```

- [ ] **Step 4: Run unit tests and assemble**

Run: `./gradlew.bat testDebugUnitTest assembleDebug`  
Expected: BUILD SUCCESSFUL; no unresolved audio/search references.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main app/src/test
git commit -m "refactor: focus library on video and photos"
```

### Task 2: Establish performance trace points and benchmark fixtures

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Create: `benchmark/build.gradle.kts`
- Create: `benchmark/src/main/java/com/example/videoplayer/benchmark/VideoGalleryScrollBenchmark.kt`
- Create: `app/src/main/java/com/example/videoplayer/performance/MediaTrace.kt`
- Create: `scripts/generate_pcm_fixtures.ps1`
- Modify: `.gitignore`

**Interfaces:**
- Produces: `MediaTrace.section(name: String, block: () -> T): T`.
- Produces: generated fixtures under ignored `test-media/pcm/`.

- [ ] **Step 1: Add a dedicated Android benchmark module and failing smoke benchmark**

```kotlin
include(":benchmark")

// benchmark/build.gradle.kts
plugins { alias(libs.plugins.android.test) }
android { targetProjectPath = ":app" }
dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.uiautomator)
}
```

The benchmark must launch the main activity, wait for the video grid and fling ten times while collecting `FrameTimingMetric()`.

- [ ] **Step 2: Run instrumentation compilation**

Run: `./gradlew.bat :benchmark:compileBenchmarkKotlin`
Expected: initial FAIL until benchmark runner and source compile.

- [ ] **Step 3: Add trace wrapper and fixture generator**

```kotlin
object MediaTrace {
    inline fun <T> section(name: String, block: () -> T): T =
        androidx.tracing.trace(name, block)
}
```

The PowerShell script invokes `ffmpeg` to generate 5-second color-bar videos with sine audio for MKV/H.264/pcm_s24le, MOV/H.264/pcm_s24le, and MKV dual AAC+pcm_s24le. It exits non-zero when `ffmpeg` or `ffprobe` is missing and verifies codec names with `ffprobe`.

- [ ] **Step 4: Compile tests and generate fixtures where ffmpeg exists**

Run: `./gradlew.bat :benchmark:compileBenchmarkKotlin`
Expected: BUILD SUCCESSFUL.  
Run: `powershell -ExecutionPolicy Bypass -File scripts/generate_pcm_fixtures.ps1`  
Expected: three verified files, or a clear prerequisite error without modifying source.

- [ ] **Step 5: Commit**

```powershell
git add settings.gradle.kts gradle/libs.versions.toml benchmark app/src/main/java/com/example/videoplayer/performance scripts .gitignore
git commit -m "test: add media performance and pcm fixtures"
```

## Phase B — Media state and thumbnail scheduling

### Task 3: Create a generation-safe media library store

**Files:**
- Create: `app/src/main/java/com/example/videoplayer/data/library/MediaLibraryState.kt`
- Create: `app/src/main/java/com/example/videoplayer/data/library/MediaLibraryStore.kt`
- Modify: `app/src/main/java/com/example/videoplayer/data/repository/MediaRepository.kt`
- Test: `app/src/test/java/com/example/videoplayer/data/library/MediaLibraryStoreTest.kt`

**Interfaces:**
- Produces: `data class MediaLibraryState(val generation: Long, val videos: List<MediaItem>, val photos: List<MediaItem>, val isRefreshing: Boolean, val error: LibraryError?)`.
- Produces: `MediaLibraryStore.state: StateFlow<MediaLibraryState>` and `suspend fun refresh(force: Boolean)`.

- [ ] **Step 1: Write failing tests for single-flight and stale generation rejection**

Use a fake scanner with two deferred results. Start refresh A, then force refresh B; complete B first and A last. Assert the final state contains B and that at most one non-forced scan runs concurrently.

- [ ] **Step 2: Run focused tests**

Run: `./gradlew.bat testDebugUnitTest --tests "*.MediaLibraryStoreTest"`  
Expected: FAIL because store types do not exist.

- [ ] **Step 3: Implement the store with Mutex and generation**

```kotlin
private val refreshMutex = Mutex()
private val generation = AtomicLong(0)

suspend fun refresh(force: Boolean) {
    val request = generation.incrementAndGet()
    refreshMutex.withLock {
        val result = scanner.scan(force)
        if (request == generation.get()) {
            _state.value = result.toState(request)
        }
    }
}
```

Represent video and photo query failures independently; publish available partial results.

- [ ] **Step 4: Pass focused and full unit tests**

Run: `./gradlew.bat testDebugUnitTest --tests "*.MediaLibraryStoreTest"`  
Expected: PASS.  
Run: `./gradlew.bat testDebugUnitTest`  
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/example/videoplayer/data app/src/test/java/com/example/videoplayer/data
git commit -m "refactor: centralize media library state"
```

### Task 4: Implement latest-wins thumbnail scheduling

**Files:**
- Create: `app/src/main/java/com/example/videoplayer/media/thumbnail/ThumbnailRequest.kt`
- Create: `app/src/main/java/com/example/videoplayer/media/thumbnail/ThumbnailScheduler.kt`
- Create: `app/src/main/java/com/example/videoplayer/media/thumbnail/ThumbnailDiskCache.kt`
- Modify: `app/src/main/java/com/example/videoplayer/ui/components/MediaGrid.kt`
- Test: `app/src/test/java/com/example/videoplayer/media/thumbnail/ThumbnailSchedulerTest.kt`

**Interfaces:**
- Produces: `enum class ThumbnailPriority { VISIBLE, PREFETCH, BACKGROUND }`.
- Produces: `request(mediaId, size, priority): Flow<ThumbnailResult>` and `setFastScrolling(Boolean)`.

- [ ] **Step 1: Write failing tests**

Test duplicate request coalescing, visible-before-prefetch ordering, no new decode while fast scrolling, and no delivery after requester cancellation.

- [ ] **Step 2: Verify test failure**

Run: `./gradlew.bat testDebugUnitTest --tests "*.ThumbnailSchedulerTest"`  
Expected: FAIL because scheduler does not exist.

- [ ] **Step 3: Implement scheduler and split decode from disk maintenance**

Use a priority channel, an in-flight map keyed by `(mediaId,width,height)`, two decode workers, and a separate single-thread disk queue. `setFastScrolling(true)` allows memory/disk hits but delays new decode work below `VISIBLE` completion.

- [ ] **Step 4: Replace per-item cold pipeline in MediaGrid**

`LightweightMediaPreview` consumes the scheduler flow and renders memory/disk hit, then placeholder. Remove per-card cache trimming and metadata persistence from the decode permit. Dynamic formats use a static first frame in grids.

- [ ] **Step 5: Run tests and assemble**

Run: `./gradlew.bat testDebugUnitTest --tests "*.ThumbnailSchedulerTest" assembleDebug`  
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/example/videoplayer/media app/src/main/java/com/example/videoplayer/ui/components/MediaGrid.kt app/src/test
git commit -m "perf: prioritize and coalesce video thumbnails"
```

## Phase C — Gallery UI

### Task 5: Build video timeline groups and gallery state

**Files:**
- Create: `app/src/main/java/com/example/videoplayer/ui/gallery/VideoTimeline.kt`
- Create: `app/src/main/java/com/example/videoplayer/ui/gallery/VideoGalleryState.kt`
- Test: `app/src/test/java/com/example/videoplayer/ui/gallery/VideoTimelineTest.kt`

**Interfaces:**
- Produces: `groupVideos(items: List<MediaItem>, zoneId: ZoneId, now: Instant): List<VideoSection>`.
- Produces: sections `TODAY`, `YESTERDAY`, `THIS_WEEK`, `MONTH` with stable keys.

- [ ] **Step 1: Write boundary tests**

Cover midnight, week boundary, month boundary, stable descending ordering, and empty input.

- [ ] **Step 2: Verify failure**

Run: `./gradlew.bat testDebugUnitTest --tests "*.VideoTimelineTest"`  
Expected: FAIL because timeline types do not exist.

- [ ] **Step 3: Implement pure grouping functions**

Use `Instant`/`ZoneId`, never device-locale string parsing. Section keys are enum plus `YearMonth` for month sections.

- [ ] **Step 4: Run focused tests**

Run: `./gradlew.bat testDebugUnitTest --tests "*.VideoTimelineTest"`  
Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/example/videoplayer/ui/gallery app/src/test/java/com/example/videoplayer/ui/gallery
git commit -m "feat: group videos into timeline sections"
```

### Task 6: Replace the main screen with the video gallery and separate photos

**Files:**
- Create: `app/src/main/java/com/example/videoplayer/ui/gallery/VideoGalleryScreen.kt`
- Create: `app/src/main/java/com/example/videoplayer/ui/gallery/VideoGalleryGrid.kt`
- Create: `app/src/main/java/com/example/videoplayer/ui/gallery/TimelineScrubber.kt`
- Create: `app/src/main/java/com/example/videoplayer/ui/photos/PhotoGalleryScreen.kt`
- Modify: `app/src/main/java/com/example/videoplayer/ui/screens/MainScreen.kt`
- Modify: `app/src/main/java/com/example/videoplayer/ui/theme/Color.kt`
- Modify: `app/src/main/java/com/example/videoplayer/ui/theme/Type.kt`
- Test: `app/src/androidTest/java/com/example/videoplayer/VideoGalleryUiTest.kt`

**Interfaces:**
- Consumes: `MediaLibraryState`, `ThumbnailScheduler`, `List<VideoSection>`.
- Produces: semantic test tags `video-gallery`, `continue-watching`, `timeline-scrubber`, `photo-gallery`.

- [ ] **Step 1: Add failing Compose UI tests**

Assert video is the initial destination, no search/audio nodes exist, continue-watching is conditional, long press enters selection, and photo navigation opens a distinct screen.

- [ ] **Step 2: Run UI test compilation**

Run: `./gradlew.bat compileDebugAndroidTestKotlin`  
Expected: FAIL until new screens/test tags exist.

- [ ] **Step 3: Implement quiet timeline gallery**

Use sticky date headers, media-content color, neutral charcoal background, low-saturation ice-blue progress, no per-item shadow/glass/gradient. Preserve duration and 2–3dp progress line only.

- [ ] **Step 4: Implement continuous pinch presentation**

During gesture, apply a graphics-layer scale to the grid presentation and update a preview density. Commit the nearest integer column count only on release; persist values 2–8. Do not recreate columns at every pinch delta.

- [ ] **Step 5: Connect fast-scroll state to thumbnail scheduler**

Derive `isScrollInProgress` plus velocity sampling; call `setFastScrolling(true)` during fling and false after settling. The timeline scrubber uses section indices and fades after inactivity.

- [ ] **Step 6: Compile, run tests on device when available, and assemble**

Run: `./gradlew.bat testDebugUnitTest connectedDebugAndroidTest assembleDebug`  
Expected: all available tasks PASS; if no device is connected, record `connectedDebugAndroidTest` as environment-blocked and require `compileDebugAndroidTestKotlin` PASS.

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/java/com/example/videoplayer/ui app/src/androidTest
git commit -m "feat: redesign library as video timeline gallery"
```

## Phase D — Playback core and PCM

### Task 7: Introduce a serialized playback controller

**Files:**
- Create: `app/src/main/java/com/example/videoplayer/playback/PlaybackSession.kt`
- Create: `app/src/main/java/com/example/videoplayer/playback/PlayerEngine.kt`
- Create: `app/src/main/java/com/example/videoplayer/playback/PlaybackController.kt`
- Create: `app/src/main/java/com/example/videoplayer/playback/PcmCompatibilityPolicy.kt`
- Test: `app/src/test/java/com/example/videoplayer/playback/PlaybackControllerTest.kt`

**Interfaces:**
- Produces: `PlaybackController.state: StateFlow<PlaybackState>`.
- Produces: commands `load`, `play`, `pause`, `seekTo`, `selectAudioTrack`, `switchEngine`, `release`.
- Produces: `PcmCompatibilityPolicy.choose(format): EngineChoice`.

- [ ] **Step 1: Write fake-engine tests**

Verify one active engine, exact-once release, old callback rejection by session generation, preservation of position/play state across fallback, PCM 24/32/float choosing VLC, and AAC/Opus choosing Exo.

- [ ] **Step 2: Verify failure**

Run: `./gradlew.bat testDebugUnitTest --tests "*.PlaybackControllerTest"`  
Expected: FAIL because playback core types do not exist.

- [ ] **Step 3: Implement serialized commands**

Process commands in one controller scope/channel. Every engine callback includes session generation. `switchEngine` snapshots position, play state, speed, subtitle and audio selection before releasing old engine and loading the new one.

- [ ] **Step 4: Run focused tests**

Run: `./gradlew.bat testDebugUnitTest --tests "*.PlaybackControllerTest"`  
Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/example/videoplayer/playback app/src/test/java/com/example/videoplayer/playback
git commit -m "refactor: serialize dual-engine playback"
```

### Task 8: Adapt ExoPlayer, VLC, full-screen and floating playback

**Files:**
- Create: `app/src/main/java/com/example/videoplayer/playback/ExoPlayerEngine.kt`
- Create: `app/src/main/java/com/example/videoplayer/playback/VlcPlayerEngine.kt`
- Modify: `app/src/main/java/com/example/videoplayer/ui/screens/VideoPlayerScreen.kt`
- Modify: `app/src/main/java/com/example/videoplayer/FloatingPlayerManager.kt`
- Modify: `app/src/main/java/com/example/videoplayer/service/FloatingPlayerService.kt`
- Test: `app/src/androidTest/java/com/example/videoplayer/PcmPlaybackInstrumentedTest.kt`

**Interfaces:**
- Consumes: `PlaybackController` and generated PCM fixtures.
- Produces: immutable `PlaybackSession` handoff by session ID/Intent payload.

- [ ] **Step 1: Add failing PCM instrumentation scenarios**

For each fixture: load, await ready, assert audio track selected, seek to 3 seconds, pause/play, enter floating mode and restore. The dual-track sample explicitly selects PCM then AAC.

- [ ] **Step 2: Implement engine adapters**

Move Exo/VLC creation, listeners, track mapping and release from Composable/service into adapters. Detect `AUDIO_RAW` encodings other than 8/16-bit before audible playback; retain error fallback for unparseable containers.

- [ ] **Step 3: Replace static multi-field handoff**

Create one `PlaybackSession` snapshot and pass a session ID through the service Intent. Empty or expired sessions must stop service without creating a window.

- [ ] **Step 4: Compile, run fixtures and instrumentation when available**

Run: `./gradlew.bat testDebugUnitTest compileDebugAndroidTestKotlin assembleDebug`  
Expected: BUILD SUCCESSFUL.  
On a device: `./gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.videoplayer.PcmPlaybackInstrumentedTest`  
Expected: all PCM scenarios PASS.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/example/videoplayer/playback app/src/main/java/com/example/videoplayer/ui/screens/VideoPlayerScreen.kt app/src/main/java/com/example/videoplayer/FloatingPlayerManager.kt app/src/main/java/com/example/videoplayer/service app/src/androidTest
git commit -m "feat: guarantee pcm s24 le video playback"
```

## Phase E — Gesture and scrub preview

### Task 9: Build a velocity-aware GestureSession

**Files:**
- Create: `app/src/main/java/com/example/videoplayer/ui/gesture/GestureSession.kt`
- Create: `app/src/main/java/com/example/videoplayer/ui/gesture/GesturePhysics.kt`
- Modify: `app/src/main/java/com/example/videoplayer/ui/components/PlayerGestureOverlay.kt`
- Test: `app/src/test/java/com/example/videoplayer/ui/gesture/GestureSessionTest.kt`

**Interfaces:**
- Produces: `GestureUpdate(position, delta, velocity, axis, phase)`.
- Produces: `DismissDecision` and `PageDecision` based on projected position plus velocity sign.

- [ ] **Step 1: Write failing gesture tests**

Cover short fast flick commit, long slow drag commit, below-threshold cancel, downward-only dismiss, horizontal/vertical axis lock, edge rubber-band monotonic resistance, and interruption from current presentation value.

- [ ] **Step 2: Verify failure**

Run: `./gradlew.bat testDebugUnitTest --tests "*.GestureSessionTest"`  
Expected: FAIL because gesture types do not exist.

- [ ] **Step 3: Implement physics as pure Kotlin**

Use density-independent thresholds converted once at gesture start, Compose `VelocityTracker`, velocity projection, and progressive resistance. Do not use fixed raw `200f/240f` pixel thresholds or two independent exit coroutines.

- [ ] **Step 4: Integrate gesture overlay**

Recognize plausible gestures from first movement, lock the winner after touch slop, cancel losers, and keep animations interruptible with `Animatable` initialized from current presentation values.

- [ ] **Step 5: Run tests and assemble**

Run: `./gradlew.bat testDebugUnitTest --tests "*.GestureSessionTest" assembleDebug`  
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/example/videoplayer/ui/gesture app/src/main/java/com/example/videoplayer/ui/components/PlayerGestureOverlay.kt app/src/test
git commit -m "feat: make player gestures continuous and interruptible"
```

### Task 10: Unify page switching, dismiss and reduced motion

**Files:**
- Create: `app/src/main/java/com/example/videoplayer/ui/player/PlayerMotion.kt`
- Modify: `app/src/main/java/com/example/videoplayer/ui/screens/VideoPlayerScreen.kt`
- Modify: `app/src/main/java/com/example/videoplayer/ui/screens/PhotoViewerScreen.kt`
- Test: `app/src/androidTest/java/com/example/videoplayer/PlayerGestureUiTest.kt`

**Interfaces:**
- Consumes: `GestureSession` decisions.
- Produces: shared motion specs for page switch, dismiss, controls and reduced-motion fallback.

- [ ] **Step 1: Add gesture UI tests**

Verify horizontal swipe exposes adjacent cover, downward drag exposes gallery background, horizontal drag cannot dismiss, animation can be interrupted, and animation scale 0 uses fade/static behavior.

- [ ] **Step 2: Replace input lock and fixed tweens**

Remove `isSwipeAnimating` early-return behavior. Page and dismiss animation starts with tracked velocity and remains targetable. Replace raw-pixel thresholds and fixed 2000px/180ms exit.

- [ ] **Step 3: Add shared reduced-motion policy**

Read `LocalMotionDurationScale`; when scale is zero, replace full-screen translation/spring with a short alpha transition and static target swap. Remove scale-from-zero indicator defaults.

- [ ] **Step 4: Compile and run available tests**

Run: `./gradlew.bat testDebugUnitTest compileDebugAndroidTestKotlin assembleDebug`  
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/example/videoplayer/ui app/src/androidTest
git commit -m "feat: unify player motion and reduced-motion behavior"
```

### Task 11: Implement latest-wins frame scrub preview

**Files:**
- Create: `app/src/main/java/com/example/videoplayer/playback/preview/FramePreviewRequest.kt`
- Create: `app/src/main/java/com/example/videoplayer/playback/preview/FramePreviewController.kt`
- Modify: `app/src/main/java/com/example/videoplayer/ui/screens/VideoPlayerScreen.kt`
- Test: `app/src/test/java/com/example/videoplayer/playback/preview/FramePreviewControllerTest.kt`

**Interfaces:**
- Produces: `submit(targetMs: Long, velocityPxPerSecond: Float)` and `preview: StateFlow<PreviewFrame?>`.
- Produces: `finish(targetMs: Long)` causing exactly one final player seek.

- [ ] **Step 1: Write failing latest-wins tests**

Use a fake decoder with deferred frames. Submit 10, 20 and 30 seconds rapidly; complete out of order; assert only 30 seconds is published. Assert `finish` invokes one seek and cancellation publishes nothing.

- [ ] **Step 2: Verify failure**

Run: `./gradlew.bat testDebugUnitTest --tests "*.FramePreviewControllerTest"`  
Expected: FAIL because preview controller does not exist.

- [ ] **Step 3: Implement conflated preview requests**

Use `MutableSharedFlow(extraBufferCapacity = 1, onBufferOverflow = DROP_OLDEST)` plus `collectLatest`. Select low resolution and slower sampling for high gesture velocity; request a precise frame after a short dwell. Keep frame decode off main.

- [ ] **Step 4: Connect progress UI and both engines**

Progress, time text and preview consume one target timestamp. During drag, render preview layer; on release perform one controller seek and fade to ready playback frame. Exo and VLC expose the same preview interface.

- [ ] **Step 5: Run tests and assemble**

Run: `./gradlew.bat testDebugUnitTest --tests "*.FramePreviewControllerTest" assembleDebug`  
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/example/videoplayer/playback/preview app/src/main/java/com/example/videoplayer/ui/screens/VideoPlayerScreen.kt app/src/test
git commit -m "feat: add responsive frame scrub previews"
```

## Phase F — Final integration and polish

### Task 12: Settings, permissions, error states and UTF-8 cleanup

**Files:**
- Create: `app/src/main/java/com/example/videoplayer/settings/PlayerSettings.kt`
- Create: `app/src/main/java/com/example/videoplayer/settings/SettingsRepository.kt`
- Modify: `app/src/main/java/com/example/videoplayer/ui/screens/SettingsScreen.kt`
- Modify: `app/src/main/java/com/example/videoplayer/ui/screens/MainScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: all touched Kotlin files containing confirmed mojibake
- Test: `app/src/test/java/com/example/videoplayer/Utf8AndPermissionContractTest.kt`

**Interfaces:**
- Produces: typed settings `Flow<PlayerSettings>`.
- Produces: per-media capability state for video and photos.

- [ ] **Step 1: Add contract tests**

Scan UTF-8 source/resources for replacement characters and known mojibake sequences; verify video permission alone enables video gallery and image denial only affects photo destination.

- [ ] **Step 2: Migrate settings and permission state**

Use DataStore typed keys/model, separate playback history, and map permission states to first request, retry, permanent denial and settings return. No audio permission remains.

- [ ] **Step 3: Replace ambiguous errors and corrupted strings**

Use actionable Chinese messages for scan failure, missing file, decoder switch failure and storage permission. Keep action names consistent between buttons and confirmations.

- [ ] **Step 4: Run tests and assemble release**

Run: `./gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleRelease`  
Expected: all tasks BUILD SUCCESSFUL with no encoding or resource errors.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main app/src/test
git commit -m "refactor: finalize settings permissions and utf8 copy"
```

### Task 13: Full verification and motion review

**Files:**
- Modify: `README.md`
- Create: `docs/verification/video-gallery-redesign.md`

**Interfaces:**
- Consumes: all earlier deliverables.
- Produces: reproducible verification record with commands, device details and remaining limitations.

- [ ] **Step 1: Run complete automated verification**

Run: `./gradlew.bat clean testDebugUnitTest lintDebug compileDebugAndroidTestKotlin assembleDebug assembleRelease`  
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run device suites when a device is present**

Run PCM playback, gallery UI, gesture UI and Macrobenchmark suites. Record p50/p90/p95 frame duration, jank percentage, cold vs hot cache, memory high-water mark and test device.

- [ ] **Step 3: Review animation code with review-animations standards**

Reject fixed raw-pixel dismiss thresholds, input locks, scale-from-zero, layout-property animation, missing reduced-motion branches and inconsistent page-switch specs. Feel-check page switch/dismiss/scrub at 0.25x speed and frame-by-frame.

- [ ] **Step 4: Update docs and final verification record**

README describes video-first scope, separate photos, removed audio/search, PCM S24 LE support and performance behavior. Verification doc distinguishes measured results from environment-blocked device tests.

- [ ] **Step 5: Commit**

```powershell
git add README.md docs/verification
git commit -m "docs: record gallery redesign verification"
```

---

## Execution order and review gates

1. Tasks 1–2 establish scope and baselines.
2. Tasks 3–4 must pass before gallery UI work.
3. Tasks 5–6 deliver the first independently usable video-first App.
4. Tasks 7–8 establish PCM-safe playback before gesture rewrites.
5. Tasks 9–11 share gesture/preview contracts and execute sequentially.
6. Tasks 12–13 finish migration, visual polish and evidence-backed release verification.

Every task requires a focused spec review, code-quality review, targeted tests and a commit before the next task begins.
