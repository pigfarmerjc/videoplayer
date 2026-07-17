# Task 1: Remove standalone audio and search

Read and follow Task 1 in `docs/superpowers/plans/2026-07-17-video-gallery-redesign.md` verbatim.

Additional decisions:

- Work only inside `C:\临时文件\videoplayer\.worktrees\video-gallery-redesign`.
- This branch has two pre-existing compile errors in `VideoPlayerScreen.kt:4538` and `:4620`; do not repair them as unrelated baseline patches. If your changes delete or naturally alter the failing code, that is acceptable.
- Removing standalone audio does not remove audio tracks inside video, equalizer controls, `AudioEffectManager`, or PCM S24 LE fallback.
- Follow TDD: add the contract test, run it and capture the expected red result before production edits.
- Preserve UTF-8 Chinese and existing code style.
- Commit the task when complete.

Report status as DONE, DONE_WITH_CONCERNS, NEEDS_CONTEXT, or BLOCKED. Write a full report to `.superpowers/sdd/task-1-report.md` containing files changed, RED and GREEN commands/results, commit hash, self-review, and concerns.
