# Task 6: Replace the main screen with the video timeline gallery and separate photos

Follow Task 6 in the implementation plan and the approved design spec.

Required design direction:

- Default destination is a pure video timeline gallery. Separate Photos and Library destinations; no audio/search.
- Quiet charcoal surfaces, content-led thumbnails, low-saturation ice-blue accent, system typography, no per-item glass/shadow/gradient.
- Sticky date sections, conditional continue-watching (max 5), duration + thin progress only, long-press selection with bottom action surface.
- Real production semantics tag `video-gallery` must be added.
- Task 2 gate: delete the benchmark's `assumeTrue(false)` skip and run/compile it against the real tag; update tracked implementation plan to dedicated `:benchmark` module.
- Pinch presentation must be continuous during gesture; commit nearest columns 2..8 only on release. Use `readClampedColumnCount` and persist repaired values.
- Timeline scrubber fades when idle and jumps by section.
- Use stable resource identity keys, not raw reusable MediaStore ID.
- Wire current MediaLibraryState/Store and ThumbnailScheduler; fast-scroll hook must remain active.
- Fix the two pre-existing VideoPlayerScreen compile errors only as the minimum needed to restore project compilation; record them separately in the task report.
- Add Compose UI tests/test tags; follow TDD where feasible. Preserve UTF-8.
- Read `C:\Users\pigfa\.codex\skills\frontend-design\SKILL.md`, `emil-design-eng\SKILL.md`, and `apple-design\SKILL.md` before visual implementation.

Commit logical slices, report to `.superpowers/sdd/task-6-report.md`, do not spawn reviewers.
