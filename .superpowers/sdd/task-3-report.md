# Task 3 Report: generation-safe media library store

## Status

DONE_WITH_CONCERNS

## RED/GREEN evidence

- RED: Added `MediaLibraryStoreTest` before the production store. It covers a forced refresh that completes ahead of an older refresh, sharing concurrent non-forced refreshes, and publishing photos with a typed video-query failure.
- RED command: `./gradlew.bat testDebugUnitTest --tests "*.MediaLibraryStoreTest"` was run before production code was added. The Android main-source compilation stopped before test compilation because of pre-existing errors in `VideoPlayerScreen.kt`: line 4538 has an invalid implicit `AnimatedVisibility` receiver and line 4620 has an unresolved `collectIsPressedAsState`.
- GREEN command: the same focused command was run after the implementation and is blocked by those same unrelated main-source errors, before it can execute `MediaLibraryStoreTest`. No `VideoPlayerScreen` code was changed.
- Static verification: reviewed the exact state transitions and ran `git diff --check` after the changes; it completed without whitespace errors.

## Interfaces

- `MediaLibraryState` holds generation, videos, photos, refresh status, and typed `LibraryError`.
- `MediaLibraryScanner` is a suspend-only boundary with no `Context` or other Android framework dependency in the store.
- `MediaLibraryStore.state` is a `StateFlow<MediaLibraryState>` and `refresh(force)` coalesces non-forced work while allowing a forced request to advance the generation.
- `MediaRepository` implements the scanner contract and reports video and photo query outcomes independently.

## Files

- `app/src/main/java/com/example/videoplayer/data/library/MediaLibraryState.kt`
- `app/src/main/java/com/example/videoplayer/data/library/MediaLibraryStore.kt`
- `app/src/main/java/com/example/videoplayer/data/repository/MediaRepository.kt`
- `app/src/test/java/com/example/videoplayer/data/library/MediaLibraryStoreTest.kt`

## Self-review

- A non-forced caller reuses the in-flight non-forced request, so it cannot start a duplicate ordinary scan.
- A forced refresh receives a newer atomic generation and may complete before the old scan; only the current generation publishes state.
- Query failures map to `LibraryError.PartialQueryFailure`, while the successful media type remains available.
- The store only exposes videos and photos; repository conversion filters out standalone audio.

## Concerns

- The focused unit suite cannot currently compile or run because of the existing unrelated `VideoPlayerScreen.kt` errors listed above. The test is intentionally pure coroutine-based and does not instantiate Android framework objects, but Android's normal Gradle unit-test task compiles all main sources first.

## Commit

Implementation: `3a09a8f refactor: centralize media library state`.

## Review follow-up: concurrency and pure-boundary fixes

### Correctness changes

- Replaced Android/Compose-bound `MediaItem` state with the pure `LibraryMedia` DTO. The repository keeps Android-specific `MediaItem` values internally and adapts them at the scanner boundary.
- Moved request selection, generation advancement, and the refreshing-state write into the same `Mutex` critical section. Result publication also rechecks generation and writes state while holding that mutex.
- Added separate active forced and non-forced requests. Ordinary callers prefer the current forced request, including a completed forced result retained while an earlier ordinary scan is still running, so they cannot join the stale ordinary request.
- Made owner cancellation clear the in-flight request and converge `isRefreshing` to `false` for the current generation. A cancelled follower does not cancel a shared owner request.
- Replaced repository query `runCatching` with `captureQuery`, which rethrows `CancellationException` and only converts non-cancellation failures to typed partial results.

### Added tests

- Forced latest-wins completion.
- Ordinary A / forced B / ordinary C interleavings, both before and after B completes.
- Non-forced single-flight.
- Video and photo query failures independently preserving the available media type.
- Overall scanner failure.
- Cancellation convergence.

### RED/GREEN evidence for the follow-up

- RED: with forced-result retention temporarily removed, the standalone JUnit run failed exactly at `ordinaryRefreshAfterCompletedForcedRefreshDoesNotJoinStaleOrdinaryScan`, because ordinary C remained joined to stale A.
- GREEN: compiling `MediaLibraryState.kt`, `MediaLibraryStore.kt`, and `MediaLibraryStoreTest.kt` with the repository's Kotlin 1.9.22 compiler dependencies, then invoking JUnit 4.13.2, completed with `OK (8 tests)`.
- `git diff --check` completed without whitespace errors.

## Actor cancellation completion fix

- Added `scannerCancellationCancelsEveryWaitingCaller`, with two ordinary callers sharing a scanner that actively throws `CancellationException`.
- RED command: standalone Kotlin 1.9.22 compilation followed by JUnit 4.13.2 `MediaLibraryStoreTest`. Output: `Tests run: 11, Failures: 1` with `Timed out waiting for 1000 ms`, proving active caller completions were left pending.
- `ScanCancelled` now captures the active request's remaining caller completions, clears actor active/state bookkeeping, and completes each remaining caller with `CancellationException`. A caller that cancelled itself has already been removed by `CallerCancelled` and is not completed twice.
- GREEN command: the same standalone compiler/JUnit command. Output: `OK (11 tests)`.

### Remaining limitation

The normal Android Gradle unit-test task is still blocked before test execution by the pre-existing `VideoPlayerScreen.kt` errors at lines 4538 and 4620. The standalone compile-and-run evidence above verifies the pure store and its tests without that unrelated source file; repository adaptation was source-reviewed because the blocked Android compilation cannot reach it.

## Second review follow-up: cancelled forced refresh

- Added `ordinaryRefreshAfterCancelledForcedRefreshDoesNotJoinStaleOrdinaryScan` for ordinary A, forced B, cancelled B, then ordinary C. It requires C to create generation 3, complete, and publish without waiting for stale A.
- RED command: standalone Kotlin 1.9.22 compilation of the three pure library/test files followed by `org.junit.runner.JUnitCore com.example.videoplayer.data.library.MediaLibraryStoreTest`. Output: `Tests run: 9, Failures: 1`; C produced `[false, true]` instead of `[false, true, false]`.
- GREEN command: the same standalone compiler/JUnit command. Output: `OK (9 tests)`.
- Request selection now reuses an active forced or ordinary request only when its generation equals the current generation; otherwise it creates a new request.

## Final review follow-up: serialized ordinary lane

- Added active ordinary-scan accounting to the A → B → cancel B → C test. RED command: the standalone Kotlin 1.9.22 compile plus JUnit 4.13.2 invocation for `MediaLibraryStoreTest`; output was `expected:<1> but was:<2>`, proving stale A and new C were concurrently scanning with `force=false`.
- Each refresh request now owns a lazy coroutine job. Starting forced B cancels stale ordinary A, while C waits for A's `finished` signal outside the mutex before it can create a new ordinary request.
- The completed-B reuse test retains a cancellation-resistant A until explicit completion, proving that C still reuses completed forced B while the stale ordinary lane is being cleared.
- GREEN command: standalone Kotlin 1.9.22 compile plus JUnit 4.13.2 invocation for `MediaLibraryStoreTest`; output: `OK (9 tests)`.

## Architecture rewrite: single actor command loop

### Decision

The mutex and request-owned-job coordinator was removed. `MediaLibraryStore` now owns one unlimited command channel and one actor loop. The actor is the only code that advances generation, chooses an active scan, updates `StateFlow`, starts pending ordinary work, and completes or removes caller completions. Scan jobs only send `ScanResult`, `ScanFailed`, or `ScanCancelled` commands.

### Semantics

- A forced refresh cancels the prior active scan and immediately starts a newer generation. It also cancels an older ordinary lane that is still clearing.
- An ordinary refresh reuses the active scan, with a forced scan naturally taking priority. While an old ordinary scan is clearing, a completed forced result serves ordinary callers; otherwise ordinary callers are queued until that lane clears. This keeps at most one `force=false` scanner call active.
- Cancelling a caller removes only its completion from actor bookkeeping. It does not cancel the shared scan; a later caller can still reuse and receive that scan's result.
- No cleanup path needs a cancelled job to acquire a mutex, because no mutex coordinates store state.

### Tests and verification

- Migrated the existing scenarios to an `Unconfined` actor test harness and added `cancellationDuringStaleCleanupDoesNotBlockActor`.
- The pre-rewrite ordinary-lane regression remained RED under the old design (`expected:<1> but was:<2>` active ordinary scans).
- GREEN command: standalone Kotlin 1.9.22 compilation of the pure library/test sources followed by JUnit 4.13.2 `MediaLibraryStoreTest`. Output: `OK (10 tests)`.
- `git diff --check` completed without whitespace errors.
