# Task 6 Review Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 Task 6 复审剩余的滚动速度、权限恢复、扫描错误、删除结果和继续观看交互问题。

**Architecture:** 将滚动位移、权限恢复动作和删除结果格式化提取为可在 `thumbnail-core` 中直接单测的纯逻辑；Compose 层只负责从 `LazyGridLayoutInfo`、Android 权限 API、Activity Result 和 MediaStore 采集真实状态并驱动 UI。库扫描错误按视频与照片分别映射，删除选区只由成功回调清理。

**Tech Stack:** Kotlin、Jetpack Compose、MediaStore、Activity Result API、JUnit4、Gradle。

## Global Constraints

- 所有前后端代码参照现有风格，中文使用 UTF-8，前端样式保持美观。
- 严格执行 RED → GREEN → REFACTOR；不派 reviewer。
- API 30+ 删除取消必须保留选区；API 29 及以下必须报告成功数和失败数。

---

### Task 1: 基于真实网格行距的快速滚动判定

**Files:**
- Modify: `thumbnail-core/src/main/kotlin/com/example/videoplayer/media/thumbnail/FastScrollGate.kt`
- Modify: `thumbnail-core/src/test/kotlin/com/example/videoplayer/media/thumbnail/ThumbnailSchedulerTest.kt`
- Modify: `app/src/main/java/com/example/videoplayer/ui/gallery/VideoGalleryGrid.kt`

**Interfaces:**
- Produces: `GridScrollVelocityTracker.update(index, offset, columns, averageLineSizePx, elapsedSeconds): Float`
- Consumes: `LazyGridLayoutInfo.visibleItemsInfo` 的实际 Y 偏移、尺寸与 `maxSpan`。

- [ ] **Step 1: Write the failing tests**

```kotlin
assertFalse(slowGate.update(true, tracker.update(4, 10, 4, 200f, 0.1f)))
assertTrue(fastGate.update(true, tracker.update(12, 20, 4, 200f, 0.1f)))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :thumbnail-core:test --tests "*ThumbnailSchedulerTest*" --rerun-tasks`
Expected: FAIL because `GridScrollVelocityTracker` does not exist.

- [ ] **Step 3: Implement minimal continuous row displacement**

```kotlin
val rowDelta = currentIndex / columns - previousIndex / columns
val distance = rowDelta * averageLineSizePx + currentOffset - previousOffset
return abs(distance) / elapsedSeconds
```

- [ ] **Step 4: Bind actual LazyGrid layout metrics and verify**

Run: `.\gradlew.bat :thumbnail-core:test --rerun-tasks :app:compileDebugKotlin`
Expected: PASS.

### Task 2: 双向权限恢复与永久拒绝持久化

**Files:**
- Modify: `thumbnail-core/src/main/kotlin/com/example/videoplayer/ui/gallery/VideoGalleryState.kt`
- Modify: `thumbnail-core/src/test/kotlin/com/example/videoplayer/ui/gallery/VideoTimelineTest.kt`
- Modify: `app/src/main/java/com/example/videoplayer/data/repository/MediaRepository.kt`
- Modify: `app/src/main/java/com/example/videoplayer/ui/screens/MainScreen.kt`

**Interfaces:**
- Produces: `resolvePermissionRecoveryAction(granted, requestedOnce, shouldShowRationale)`。
- Produces: `MediaRepository.wasPhotoPermissionRequestedOnce()` 与 `setPhotoPermissionRequestedOnce()`。

- [ ] **Step 1: Write failing resolver tests**

```kotlin
assertEquals(REQUEST, resolvePermissionRecoveryAction(false, false, false))
assertEquals(OPEN_SETTINGS, resolvePermissionRecoveryAction(false, true, false))
```

- [ ] **Step 2: Verify RED**

Run: `.\gradlew.bat :thumbnail-core:test --tests "*VideoTimelineTest*" --rerun-tasks`
Expected: FAIL because permission recovery API is missing.

- [ ] **Step 3: Implement and wire lifecycle updates**

```kotlin
hasVideoPermission = videoGranted
hasPhotoPermission = photoGranted
photoAccessState = resolvePhotoAccess(photoGranted, requestedOnce, rationale)
if (permissionsChanged) refreshLibrary()
```

- [ ] **Step 4: Verify GREEN**

Run: `.\gradlew.bat :thumbnail-core:test --rerun-tasks :app:compileDebugKotlin`
Expected: PASS.

### Task 3: 分类型展示扫描失败并允许重试

**Files:**
- Modify: `app/src/main/java/com/example/videoplayer/ui/screens/MainScreen.kt`
- Modify: `app/src/main/java/com/example/videoplayer/ui/gallery/VideoGalleryScreen.kt`
- Modify: `app/src/main/java/com/example/videoplayer/ui/photos/PhotoGalleryScreen.kt`
- Modify: `app/src/androidTest/java/com/example/videoplayer/VideoGalleryUiTest.kt`

**Interfaces:**
- Produces: 视频页 `videoQueryError`/`onRetryVideoQuery`，照片页沿用 `PhotoAccessState.QueryFailed`。

- [ ] **Step 1: Add failing UI compile tests for video and photo retry states**

```kotlin
composeRule.onNodeWithTag("video-query-error").assertIsDisplayed()
composeRule.onNodeWithTag("retry-video-query").performClick()
composeRule.onNodeWithTag("photo-query-error").assertIsDisplayed()
```

- [ ] **Step 2: Verify RED**

Run: `.\gradlew.bat :app:compileDebugAndroidTestKotlin --rerun-tasks`
Expected: FAIL because video query error parameters are missing.

- [ ] **Step 3: Map `ScanFailure` to both pages and `PartialQueryFailure` independently**

```kotlin
val videoFailure = when (error) { is ScanFailure -> error.cause; is PartialQueryFailure -> error.video; null -> null }
val photoFailure = when (error) { is ScanFailure -> error.cause; is PartialQueryFailure -> error.photo; null -> null }
```

- [ ] **Step 4: Verify GREEN**

Run: `.\gradlew.bat :app:compileDebugAndroidTestKotlin --rerun-tasks`
Expected: PASS.

### Task 4: 删除结果、选区生命周期和继续观看语义

**Files:**
- Modify: `thumbnail-core/src/main/kotlin/com/example/videoplayer/ui/gallery/VideoGalleryState.kt`
- Modify: `thumbnail-core/src/test/kotlin/com/example/videoplayer/ui/gallery/VideoTimelineTest.kt`
- Modify: `app/src/main/java/com/example/videoplayer/ui/screens/MainScreen.kt`
- Modify: `app/src/main/java/com/example/videoplayer/ui/gallery/VideoGalleryScreen.kt`
- Modify: `app/src/androidTest/java/com/example/videoplayer/VideoGalleryUiTest.kt`

**Interfaces:**
- Produces: `GalleryDeleteResult(successCount, failureCount).message`。
- Changes: `onDelete(items, onConfirmed)`，只有确认删除才调用 `onConfirmed`。

- [ ] **Step 1: Add failing unit/UI tests**

```kotlin
assertEquals("已删除 2 个，1 个失败", GalleryDeleteResult(2, 1).message)
composeRule.onNodeWithTag("continue-item:...").performClick()
```

- [ ] **Step 2: Verify RED**

Run: `.\gradlew.bat :thumbnail-core:test --rerun-tasks :app:compileDebugAndroidTestKotlin --rerun-tasks`
Expected: FAIL because result and callback APIs are missing.

- [ ] **Step 3: Implement confirmed selection clearing and Snackbar reporting**

```kotlin
if (result.resultCode == Activity.RESULT_OK) pendingDeleteCompletion?.invoke()
snackbarHostState.showSnackbar(GalleryDeleteResult(deleted, total - deleted).message)
```

- [ ] **Step 4: Replace pointer-only continue cards with clickable semantics and verify**

Run: `.\gradlew.bat :app:compileDebugAndroidTestKotlin :app:assembleDebug :thumbnail-core:test --rerun-tasks`
Expected: PASS.

### Task 5: Report and commits

**Files:**
- Modify: `.superpowers/sdd/task-6-report.md`

- [ ] **Step 1: Update RED/GREEN evidence and review-fix summary**
- [ ] **Step 2: Run `git diff --check` and final Gradle verification**
- [ ] **Step 3: Commit logical changes and report commit hashes**

### Task 6: Final blocker — visible-line scroll tracking

**Files:**
- Modify: `thumbnail-core/src/main/kotlin/com/example/videoplayer/media/thumbnail/FastScrollGate.kt`
- Modify: `thumbnail-core/src/test/kotlin/com/example/videoplayer/media/thumbnail/ThumbnailSchedulerTest.kt`
- Modify: `app/src/main/java/com/example/videoplayer/ui/gallery/VideoGalleryGrid.kt`

**Interfaces:**
- Produces: allocation-free `beginSample` / `addVisibleLine` / `endSample` tracker API using real LazyGrid row and Y offsets.

- [ ] **Step 1: Add failing header and column-reset tests**

```kotlin
tracker.beginSample(); tracker.addVisibleLine(0, -20, 40); tracker.addVisibleLine(1, 30, 100)
assertEquals(100f, tracker.endSample(0.1f, 2), 0.01f)
tracker.reset() // models LaunchedEffect restart when columns changes
```

- [ ] **Step 2: Verify RED with `:thumbnail-core:test --rerun-tasks`**
- [ ] **Step 3: Implement fixed-array visible-line tracking and key `LaunchedEffect(state, columns)`**
- [ ] **Step 4: Verify GREEN with core tests and app compile**

### Task 7: Final blocker — video permanent denial

**Files:**
- Modify: `app/src/main/java/com/example/videoplayer/data/repository/MediaRepository.kt`
- Modify: `app/src/main/java/com/example/videoplayer/ui/screens/MainScreen.kt`
- Modify: `app/src/androidTest/java/com/example/videoplayer/VideoGalleryUiTest.kt`

**Interfaces:**
- Produces: persisted video requested-once history and a permission screen that routes `REQUEST` to the runtime prompt and `OPEN_SETTINGS` to app settings.

- [ ] **Step 1: Add failing UI compile test for `open-video-settings`**
- [ ] **Step 2: Verify RED with `:app:compileDebugAndroidTestKotlin --rerun-tasks`**
- [ ] **Step 3: Persist request history, recompute action after result/ON_RESUME, and wire settings**
- [ ] **Step 4: Run final minimal verification and commit**
