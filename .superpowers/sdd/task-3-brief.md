# Task 3: Create a generation-safe media library store

Read and follow Task 3 in `docs/superpowers/plans/2026-07-17-video-gallery-redesign.md`.

Decisions and constraints:

- Work only inside `C:\临时文件\videoplayer\.worktrees\video-gallery-redesign`.
- Strict TDD: write and run focused failing tests before production files.
- Build a pure Kotlin/coroutines boundary so unit tests do not require Android framework objects.
- State contains videos and photos only; standalone audio remains removed.
- Single-flight must avoid duplicate concurrent scans. A newer forced refresh must win over an older result; stale generation must never publish.
- A video query failure may still publish photos and vice versa, with typed partial error state.
- Do not repair unrelated VideoPlayerScreen baseline errors.
- Preserve UTF-8 and existing style; commit the task.

Write `.superpowers/sdd/task-3-report.md` with RED/GREEN evidence, interfaces, files, commit, self-review and concerns. Return one of DONE/DONE_WITH_CONCERNS/NEEDS_CONTEXT/BLOCKED plus commit and test summary. Do not spawn reviewers.
