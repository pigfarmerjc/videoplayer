# Task 2: Establish performance trace points and benchmark fixtures

Read and follow Task 2 in `docs/superpowers/plans/2026-07-17-video-gallery-redesign.md`.

Decisions and constraints:

- Work only inside `C:\临时文件\videoplayer\.worktrees\video-gallery-redesign`.
- Use TDD/test-first where behavior is introduced. Do not repair the two documented baseline `VideoPlayerScreen` compile errors.
- Benchmark and fixture infrastructure must be minimal and compatible with minSdk 26 / targetSdk 34.
- Do not add generated media binaries to git; `test-media/pcm/` must be ignored.
- `generate_pcm_fixtures.ps1` must create MKV H.264 + pcm_s24le, MOV H.264 + pcm_s24le, and dual-track MKV AAC + pcm_s24le, then verify codecs with ffprobe. Fail clearly if ffmpeg/ffprobe are missing.
- Preserve UTF-8 and existing Gradle style.
- Commit when complete.

Write the full report to `.superpowers/sdd/task-2-report.md`, including files, RED/GREEN evidence, fixture command result, commit, self-review and concerns. Return DONE, DONE_WITH_CONCERNS, NEEDS_CONTEXT or BLOCKED plus commit and one-line tests.
