# Task 6 Report: video timeline gallery and separate photos

## Status

DONE_WITH_CONCERNS

## Delivered

- Replaced the main dashboard with a video-first timeline gallery and separate Photos and Library destinations. No audio or search destination is present.
- Added sticky date context, conditional continue-watching (newest five maximum), duration plus a 3dp ice-blue progress line, long-press multi-selection, and a bottom batch-action surface.
- Added the production `video-gallery` test tag and enabled `testTagsAsResourceId` so the Macrobenchmark UIAutomator lookup reaches the real Compose node.
- Added continuous pinch presentation. The grid uses a graphics-layer scale during the gesture, previews a floating density, and recreates `GridCells.Fixed` only once after release at the nearest persisted value in `2..8`.
- Added a section-index timeline scrubber that jumps directly to section starts and fades after 1.6 seconds of inactivity.
- Uses stable resource-version keys (`storageKey`, URI, and `dateModified`) for Compose identity and thumbnail requests rather than reusable MediaStore IDs.
- Connected `MediaLibraryStore.state` to `MainScreen`, adapted its pure media DTO back to Android media values at the UI boundary, and connected gallery/photo thumbnails to `ThumbnailSchedulerProvider`.
- Kept fast-scroll hooks active in video and photo grids. The video grid samples scroll position/time as well as `isScrollInProgress` before forwarding state through `ThumbnailScrollController`.
- Updated the charcoal/ice-blue theme and retained Android system typography.
- Removed the Macrobenchmark's permanent `assumeTrue(false)` prerequisite and updated the tracked implementation plan to document the dedicated `:benchmark` module.

## TDD evidence

### RED

- `./gradlew.bat :thumbnail-core:test --tests "*.VideoTimelineTest" --rerun-tasks` failed at test compilation because `previewGalleryColumnCount` and `commitGalleryColumnCount` did not exist.
- After the pinch functions were green, the continue-watching limit test failed with one assertion failure because all six unfinished videos were returned.
- `./gradlew.bat :app:compileDebugAndroidTestKotlin` failed because the planned `MainGalleryContent` production API did not exist. The tests already required `video-gallery`, `timeline-scrubber`, conditional `continue-watching`, `selection-actions`, and distinct `photo-gallery` semantics.

### GREEN

- `./gradlew.bat :thumbnail-core:test --tests "*.VideoTimelineTest" --rerun-tasks` passed after adding continuous preview/release-commit helpers.
- `./gradlew.bat :app:compileDebugAndroidTestKotlin :thumbnail-core:test --tests "*.VideoTimelineTest" --rerun-tasks` passed after the Compose implementation and five-item limit were added.

## Baseline compilation repair (separate scope and commit)

The brief authorized only the two pre-existing `VideoPlayerScreen.kt` errors required to restore project compilation:

1. Imported `collectIsPressedAsState` for the playlist row interaction source.
2. Used the explicit top-level `androidx.compose.animation.AnimatedVisibility` call where an inaccessible implicit `ColumnScope` receiver was selected.

RED: `:app:compileDebugKotlin --rerun-tasks` failed only at the two documented lines.  
GREEN: the same command completed successfully after those two changes.  
Commit: `8b5f4cf fix: restore player screen compilation`.

## Verification

- `./gradlew.bat :benchmark:compileBenchmarkKotlin :app:compileDebugAndroidTestKotlin` — PASS.
- `./gradlew.bat :thumbnail-core:test` — PASS (including scheduler and gallery suites).
- `./gradlew.bat :app:assembleDebug --rerun-tasks` — PASS.
- `connectedDebugAndroidTest` — environment-blocked: `adb` is not installed/available, so no device test was started or awaited. AndroidTest Kotlin compilation passes.
- `:app:testDebugUnitTest` — environment-blocked before test execution. Both discovered test classes fail uniformly with `ClassNotFoundException`, while their compiled `.class` files are present under `app/build/tmp/kotlin-classes/debugUnitTest`. This is the existing Gradle test-worker/classpath issue on the repository's Chinese Windows path recorded by earlier tasks; no test assertion ran. The brief did not authorize build-system changes beyond the two player-source baseline fixes.
- `git diff --check` — PASS before commits.

## Commits

- `8b5f4cf fix: restore player screen compilation`
- `2f6eede feat: define continuous gallery pinch state`
- `27d3255 feat: redesign library as video timeline gallery`
- `87519b6 test: activate video gallery benchmark`

## Design-skill influence

- The approved direction overrode generic visual exploration: media supplies the color, while chrome stays quiet charcoal with low-saturation ice blue only for progress, selection, and key actions.
- Repeated thumbnail interactions avoid decorative animation; the only continuous transform is the direct-manipulation pinch presentation.
- The selection surface uses a short, symmetric enter/exit transition and does not animate from zero scale.

## Remaining environment verification

- Run `connectedDebugAndroidTest` and the activated Macrobenchmark on a device with a populated media library.
- Re-run `:app:testDebugUnitTest` from an ASCII-only checkout or after the repository-wide Gradle test-worker path issue is addressed in an authorized build-system task.

## Reject review remediation

### Safety and persisted actions

- Replaced every empty batch-action callback. Share now sends all selected URIs with `ACTION_SEND_MULTIPLE`, `ClipData`, and temporary read grants. Playlist adds persist each selected item through `MediaRepository.setInPlaylist`.
- Delete uses `MediaStore.createDeleteRequest` on Android 11+ and only refreshes after `RESULT_OK`. Older Android versions delete each content URI through `ContentResolver`; failures are surfaced and refresh only follows at least one successful deletion.
- Added Compose tests proving the playlist action receives the selected video and selection semantics expose the selected state.

### State and permissions

- Added a `playerReturnGeneration` owned by `MainActivity`. Closing the player increments it, causing `MainScreen` to reload the progress snapshot even when the video list itself did not change.
- Video and photo `LazyGridState` instances now live above destination switching, preserving index and offset when navigating between destinations and across column/aspect changes.
- Photos now have explicit requesting, requestable denial, permanent denial/system-settings, partial-query failure/retry, available-empty, and available-content states. Returning from system settings rechecks permissions on resume.
- Added persisted square/original thumbnail aspect mode and a top-bar layout control without restoring search.

### Scrolling, timeline, and allocation fixes

- Added pure `FastScrollGate` hysteresis. Slow scrolling leaves decoding enabled; only velocity above the enter threshold pauses new decodes, and two slow samples or scroll settlement resume it.
- Precomputed the lazy-entry-to-section `IntArray`, removing `take`/`filterIsInstance` allocation from fling-time sticky-header lookup.
- Timeline scrub requests use a drop-oldest `MutableSharedFlow`, `distinctUntilChanged`, and `collectLatest`; pointer moves no longer launch independent coroutines. The scrubber now shows the active date or month.
- Thumbnail requests now wait for a non-zero measured size. Video and photo items expose button/click semantics, and video selection exposes the selected semantic state.
- Removed the obsolete `onNavigateToAudio` folder-detail parameter and caller.

### Additional RED/GREEN evidence

- RED: `:thumbnail-core:test --rerun-tasks` failed at test compilation for missing fast-scroll gate, aspect store, and section index-map APIs.
- RED: `:app:compileDebugAndroidTestKotlin` failed for missing batch callbacks, photo access state, and aspect-mode parameters.
- GREEN: `:thumbnail-core:test --rerun-tasks` passed after the pure implementations.
- GREEN: `:app:compileDebugAndroidTestKotlin --rerun-tasks` passed after the Compose wiring and semantic tests.

### Remediation commits

- `1008c58 fix: gate thumbnail pauses on fast scroll`
- `20f0748 fix: complete gallery actions and state handling`

## Second review remediation

- Replaced the synthetic `index * 1000` position with `GridScrollVelocityTracker`. It converts first-visible indices to row deltas using the actual column count and uses average line spacing measured from `LazyGridLayoutInfo.visibleItemsInfo`, keeping displacement continuous across multi-column row boundaries.
- Added regression coverage proving a slow four-column row crossing remains below the fast-scroll gate while a three-row fling enters it.
- Permission state now updates in both directions on `ON_RESUME`. Revoked media is hidden immediately and a forced scan reconciles the remaining accessible type. Photo request history is persisted so `shouldShowRequestPermissionRationale == false` is not mistaken for a first request after recomposition or process restart.
- `ScanFailure` is shown on both video and photo destinations with retry actions. `PartialQueryFailure.video` and `.photo` are mapped independently, so one failed media type does not replace the other destination.
- Android 11+ delete keeps selection until the system result is `RESULT_OK`; cancellation leaves the selected videos intact. Legacy deletion always reports successful and failed counts through the gallery Snackbar, including explicit all-failed copy.
- Continue-watching cards now use Compose `clickable(role = Role.Button)` semantics and have stable test tags.

### Second review RED/GREEN evidence

- RED: `:thumbnail-core:test --rerun-tasks` failed at test compilation for missing `GridScrollVelocityTracker`, `PermissionRecoveryAction`, and `GalleryDeleteResult`.
- RED: `:app:compileDebugAndroidTestKotlin --rerun-tasks` failed for missing video query-error and confirmed-delete callback APIs.
- GREEN: `:app:compileDebugAndroidTestKotlin --rerun-tasks :thumbnail-core:test` completed successfully after the minimal implementations.

### Second review commit

- `81a9383 fix: finish video gallery review remediation`
