# Task 2 Report: performance traces, scroll benchmark, and PCM fixtures

## Scope completed

- Added `MediaTrace.section(name, block)` backed by AndroidX tracing.
- Added a `FrameTimingMetric` smoke benchmark that launches the main activity, waits for the `video-gallery` node, and flings it ten times.
- Added the requested Android benchmark and UI Automator dependencies.
- Added a PowerShell fixture generator for five-second SMPTE color-bar H.264 videos with PCM S24 LE audio:
  - `video-pcm-s24le.mkv`
  - `video-pcm-s24le.mov`
  - `video-aac-pcm-s24le.mkv` (AAC track followed by PCM S24 LE track)
- The generator verifies H.264 and expected audio codec sequences with `ffprobe`.
- Added `/test-media/pcm/` to `.gitignore`; generated binaries are not staged.

## Files

- Modified: `.gitignore`
- Modified: `app/build.gradle.kts`
- Created: `app/src/main/java/com/example/videoplayer/performance/MediaTrace.kt`
- Created: `app/src/androidTest/java/com/example/videoplayer/VideoGalleryScrollBenchmark.kt`
- Created: `scripts/generate_pcm_fixtures.ps1`

## RED/GREEN evidence

### RED

1. Before the generator existed, `powershell -ExecutionPolicy Bypass -File scripts/generate_pcm_fixtures.ps1` failed because the script file was absent.
2. After adding the smoke benchmark before `MediaTrace`, `./gradlew.bat compileDebugAndroidTestKotlin` was attempted. It stopped in pre-existing `VideoPlayerScreen.kt` compilation before Android-test sources could be compiled. The documented baseline errors were:
   - line 4538: `AnimatedVisibility` implicit `ColumnScope` receiver error
   - line 4620: unresolved `collectIsPressedAsState`

### GREEN / static validation

1. The final `./gradlew.bat compileDebugAndroidTestKotlin` no longer reports any error from Task 2 files. It remains blocked only by the two documented `VideoPlayerScreen.kt` baseline errors above; that file was not changed.
2. PowerShell AST parsing passed for `scripts/generate_pcm_fixtures.ps1`.
3. `git diff --check` passed before the commit.
4. `git check-ignore -v test-media/pcm/fixture.mkv` matched `.gitignore:23`.

## Fixture command result

Command: `powershell -ExecutionPolicy Bypass -File scripts/generate_pcm_fixtures.ps1`

Result: environment-blocked as intended. `ffmpeg` is not on PATH, so the script emitted `Missing ffmpeg. Install FFmpeg and ensure ffmpeg is on PATH, then retry.`, returned exit code `1`, and did not create `test-media/pcm/`. The same prerequisite guard checks `ffprobe` before any output directory or media is created.

## Commit

`7f1c25e5afb6577195b22311ceb926788d6ba21d` - `test: add media performance and pcm fixtures`

## Self-review

- The trace wrapper has one responsibility and preserves the requested generic return type. `crossinline` is required by AndroidX tracing's inline call contract.
- The benchmark uses a semantic resource id expected from the gallery task (`video-gallery`), waits explicitly, and measures ten UI Automator flings with `FrameTimingMetric`.
- Fixture creation uses `smptebars`, 48 kHz sine audio, H.264/yuv420p, five-second duration, and exact `ffprobe` codec verification.
- The script is ASCII-only so it remains parseable by Windows PowerShell 5.x when stored as UTF-8 without a BOM; no mojibake was introduced.

## Concerns

- Instrumentation compilation cannot become green until the two documented `VideoPlayerScreen.kt` baseline errors are repaired by their owner.
- No FFmpeg installation is available in this environment, so actual media creation and codec probing could not run.
- Resolved by the review remediation below: the Macrobenchmark now lives in a dedicated `com.android.test` module. It remains intentionally skipped until Task 6 supplies the real gallery tag.

## Review remediation: isolated Macrobenchmark module

The original app-side Macrobenchmark was not a valid macrobenchmark architecture because the test and target app shared `:app`. It was migrated to a dedicated `:benchmark` module using `com.android.test` through the Gradle version catalog.

### Files changed

- Modified: `settings.gradle.kts` to include `:benchmark`.
- Modified: `build.gradle.kts` and `gradle/libs.versions.toml` to catalog the Android test plugin, macrobenchmark, UI Automator, and tracing dependencies.
- Modified: `app/build.gradle.kts` to add the non-debuggable `benchmark` build type and remove app-side Macrobenchmark dependencies.
- Created: `app/src/benchmark/AndroidManifest.xml` with `<profileable android:shell="true" />`.
- Created: `benchmark/build.gradle.kts` with `targetProjectPath = ":app"`, self-instrumenting mode, and only the `benchmark` test variant enabled.
- Moved: `VideoGalleryScrollBenchmark` from `app/src/androidTest` to `benchmark/src/main/java`.

### Task 6 activation gate

`VideoGalleryScrollBenchmark.flingVideoGallery` now calls a JUnit `assumeTrue(false)` prerequisite before any launch, wait, or fling. Its message explicitly states that Task 6 must remove the prerequisite after adding the real `video-gallery` semantic tag. This makes the current test skip rather than timing out and falsely appearing runnable. The post-Task-6 path retains the intended `FrameTimingMetric`, gallery-tag wait, and ten flings.

### RED/GREEN evidence

1. RED: before module registration, `./gradlew.bat :benchmark:compileDebugKotlin` failed with `project 'benchmark' not found`.
2. RED: after registration, forced `:benchmark:compileBenchmarkSources --rerun-tasks` exposed the incorrect original `MacrobenchmarkRule` package. Correcting it to `androidx.benchmark.macro.junit4.MacrobenchmarkRule` reduced the failure to the optional unavailable `LargeTest` annotation.
3. GREEN: removing the nonessential annotation produced a successful `./gradlew.bat :benchmark:compileBenchmarkSources --rerun-tasks` run. The output included `:benchmark:compileBenchmarkKotlin` and ended with `BUILD SUCCESSFUL in 4s`.
4. GREEN: `./gradlew.bat :app:processBenchmarkManifest :benchmark:assembleBenchmark` ended with `BUILD SUCCESSFUL in 22s` (37 actionable tasks). This verifies the profileable app benchmark manifest and the independently assembled benchmark APK.
5. GREEN: static configuration checks confirmed the legacy app-side source is absent, the catalog maps `android-test` to `com.android.test`, `:benchmark` targets `:app`, the app benchmark build type is non-debuggable, and the Task 6 prerequisite skip is present.

### Review remediation commit

`83aa5a6f77a486980cc6bb63d079997a3ad084de` - `test: isolate gallery macrobenchmark module`

## Review remediation: Task 6 performance acceptance handoff

Task 6 要删除 benchmark 中的 `assumeTrue(false)` 临时 skip，并且在真实 `video-gallery` tag 上跑出测量结果后才能接受性能任务。
