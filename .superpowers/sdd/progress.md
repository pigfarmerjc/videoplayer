# SDD Progress

Baseline: existing branch does not compile (`VideoPlayerScreen.kt:4538` scope error; `VideoPlayerScreen.kt:4620` missing import). User authorized proceeding directly with the redesign on 2026-07-17.

Task 1: complete (commits c8c781b..4dd4877, spec and quality approved). Minor deferred: remove unused `MusicNote` and `Search` imports in `MainScreen.kt` during Task 6/12 cleanup.

Task 2 decision: user approved replacing the invalid app-side Macrobenchmark with a dedicated `com.android.test` benchmark module and a non-debuggable/profileable target. Task 6 must delete the benchmark's temporary `assumeTrue(false)` skip, run it against the real `video-gallery` tag, and record measurements before performance acceptance.

Task 2: complete (commits 4dd4877..83aa5a6, spec and quality approved). Minor deferred: sync the tracked implementation plan and early Task 2 report file list to the dedicated `:benchmark` module.

Task 3: complete (commits 83aa5a6..6911c28, actor rewrite approved by user, spec and quality approved, 11 standalone JUnit tests pass).

Task 4: complete (commits 6911c28..7b2c993, spec and quality approved, 7 scheduler tests pass). Minor deferred: constrain/async memory cache lookup and monitor/bound scheduler command queue.

Task 5: complete (commit 73387bc, spec approved, quality approved with minor findings). Task 6 must use `readClampedColumnCount` so invalid persisted values are repaired, document video ID uniqueness or use resource identity keys, and add continue-watching ordering/bad-progress tests.

Task 6: complete through `5c5ad9b`; final independent specification, quality, and motion review PASS. CompileDebugKotlin, compileDebugAndroidTestKotlin, thumbnail-core tests, and diff checks passed. Device gallery benchmark remains pending.

Task 7: complete through `a2e31ae`; final independent review PASS. Serialized controller covers engine lifecycle failures, callback generations, play intent, cancellation cleanup, and strict signed little-endian PCM 24/32/float routing. Direct JUnit `OK (16 tests)`; app/unit-test compilation passed.

Task 8: WIP checkpoint. Exo/VLC adapters, immutable floating session handoff, PCM instrumentation tests, and full-screen/floating integration are not yet accepted. See `docs/HANDOFF-2026-07-17.md`. Audible PCM and device round-trip verification remain mandatory.
