# Task 4: Implement latest-wins thumbnail scheduling

Read and follow Task 4 in `docs/superpowers/plans/2026-07-17-video-gallery-redesign.md`.

Constraints:

- Work only in the isolated worktree.
- Strict TDD for scheduling/coalescing behavior.
- Scheduler core must be pure Kotlin and independently testable despite existing Android screen compile errors.
- Priorities: visible, prefetch, background. Duplicate `(mediaId,width,height)` requests coalesce.
- Fast scrolling must allow memory/disk hits but must not start new heavy decode work; visible requests resume first after settling.
- Cancellation/late completion must never deliver stale results to a requester.
- Decode permits cover decode only. Disk write, metadata, and cache trim use separate queues/maintenance.
- Grid dynamic formats use static first frame; do not add continuous GIF/video animation.
- Preserve UTF-8, existing style, minSdk 26; commit and report.

Write `.superpowers/sdd/task-4-report.md` with RED/GREEN, files, interfaces, commit, self-review and concerns. Do not spawn reviewers.
