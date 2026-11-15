# Stashd roadmap

Status as of `main` @ `90dc6e3` (M2). ✅ done · 🔜 next · ⏳ later. Every "done" item is done **in code with unit tests**. None of it has been run against real Google sign-in or Drive yet, and none of it has been checked on a phone yet (that is M3).

> The original build plan (`stashd-build-plan.pdf`) numbered the milestones differently (M2 browser, M3 engine, M4 progress UI). The owner later revised it: browsing became M1, dependable downloading M2, and device acceptance M3. M4 and M5 below keep the plan's progress-UI and hardening/release scope.

## ✅ M0: setup and scaffold
- [x] Compose Material 3 project, Hilt, Room, WorkManager, DataStore, OkHttp, type-safe navigation
- [x] Theme and 7 placeholder screens from the mockups
- [x] Demo worker exercising the foreground service + notification (replaced in M2)

## ✅ M1: auth and browsing (M1a + M1b)
- [x] Sign-in (Credential Manager) → `drive.readonly` consent (AuthorizationClient), cancel/deny handling
- [x] `RealDriveRepository`: files.get, paginated files.list, all-drives flags, 401 → refresh once, error mapping
- [x] Link parsing incl. `resourcekey`; header sent for the listed folder and shortcut targets
- [x] HTTP log redaction
- [x] Drive cache keyed by file id with a parent→child edge table
- [x] Folder browser: navigation, breadcrumbs, selection across folders, all UI states
- [x] M1b fixes: per-account token cache, shortcut identity, explicit incomplete listings, stale link validation, "Download this folder"
- [x] Demo mode (`FakeDriveRepository`) when no credentials are set
- [x] `TODO(cloud-setup)` markers + SETUP.md list

## ✅ M2: dependable downloading
- [x] Media download with Range, validated 206/Content-Range, Drive 403 reasons mapped
- [x] Scheduler-independent `FileDownloader`:
  - [x] temp file + rename
  - [x] resume
  - [x] remote-change restart
  - [x] md5 verify
  - [x] name sanitizing and collisions
  - [x] low storage
  - [x] revoked SAF access
- [x] Recursive selection expansion with path preservation and dedup (selected folder, shortcuts, multi-parent); Docs skipped
- [x] Room v4 batches/items/tasks, one active batch, persisted pause state and resume offset
- [x] Bounded retries with jittered backoff; terminal errors and batch pauses with saved reasons
- [x] Pause ≠ cancel; cancel deletes partials; retry failed files
- [x] UIDT job on Android 14+, WorkManager dataSync fallback, one shared execution path
- [x] "Start download" / "Download this folder" create batches; Downloads screen and notification wired to real state

## 🔜 M3: device acceptance and hardening
- [ ] Run the phone checklist in [SETUP.md §5b](../SETUP.md) (demo mode is enough):
  - [ ] UIDT on Android 14+: screen off, swipe away, Task Manager stop = pause
  - [ ] airplane mode mid-file → waits, then resumes by itself
  - [ ] pause/resume/cancel from the notification
  - [ ] SD card / USB destination ejected → paused
  - [ ] WorkManager path on Android 8–13
  - [ ] notification permission denied
- [ ] Real History screen backed by `download_batch` / `file_task` (currently static sample)
- [ ] Real Room migrations (replace the destructive fallback; add migration tests)
- [ ] Faster resume for large files (avoid re-hashing the whole partial file on each resume)
- [ ] Sign-out / switch-account UI
- [ ] Pull-to-refresh in the browser
- [ ] Automatic backoff/retry for browser listings on 429/5xx
- [ ] Restore browser state (path, selection) after process death
- [ ] Google Docs/Sheets/Slides export (currently skipped and listed)
- [ ] Several files at once (use the existing parallelism setting)
- [ ] Bump `versionName` (still `0.2.0-m1a` in `app/build.gradle.kts`)

## ⏳ Cloud setup (owner; unblocks real Drive)
- [ ] Google Cloud project + Drive API enabled
- [ ] OAuth consent screen: External, Testing, `drive.readonly`, yourself as test user
- [ ] Android OAuth client (package + debug SHA-1 per build machine)
- [ ] Web OAuth client → `stashd.webClientId` in `~/.gradle/gradle.properties`
- [ ] First live run: [SETUP.md §5](../SETUP.md) checklist, then a real multi-GB folder with md5s matching

## ⏳ M4: progress UI, notification, history (polish)
- [ ] Downloads screen: per-item rows with expandable per-file rows
- [ ] History: batch summary, failed-file card with reason and Retry, Retry all failed, Open folder
- [ ] Notification: Open action; check that the UI, notification and Room numbers agree within about 1 s
- [ ] Compare the screens with the mockups in [screenshots/](screenshots/) (light variant included)

## ⏳ M5: hardening and release
- [ ] Platform limits: Android 15 dataSync timeout on the WorkManager path, Doze, process-death recovery
- [ ] Edge content: 0-byte files, empty folders, very deep trees, huge folders
- [ ] Accessibility (TalkBack labels on progress, 200% font scale), R8, baseline profile, crash reporting
- [ ] Bump targetSdk (currently 35)
- [ ] Signed release APK for sideloading (release keystore kept out of git; release SHA-1 on the Android OAuth client)
- [ ] Choose a license

## ⏳ v2: any-link downloads (inspired by [Plexo](https://getplexo.app/))
v1 stays Drive-only. v2 reuses the engine (Room tasks, ProgressBus, schedulers) and adds a generic HTTP source, a chunk scheduler and a network pool.
- [ ] Paste any HTTP/HTTPS link: HEAD or `Range: bytes=0-0` for size, name (Content-Disposition), Accept-Ranges, ETag/Last-Modified
- [ ] Parallel Range chunks per file, with a chunk bitmap in Room for resume; race the slowest chunks near the end
- [ ] Wi-Fi + mobile data together (opt-in, per-batch mobile data cap, roaming warning)
- [ ] Download queue with priorities and drag to reorder; files-at-once and chunks-per-file settings
- [ ] Scheduled downloads (time window, Wi-Fi only, charging)
- [ ] Per-network speed limits (token bucket)
- [ ] Share-sheet intent: `ACTION_SEND` text/plain and `ACTION_VIEW` http/https
- [ ] Replace an expired link: accept a new URL only if size and ETag/Last-Modified match, then resume
- [ ] Other backlog: sync/update a folder (only new or changed files), MediaScanner integration, multi-account, `drive.file` + Picker for a public release
