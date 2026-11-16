# Changelog

All notable changes, grouped by milestone. Dates are IST (UTC+5:30). Versions are milestone labels; from M3 `versionName` matches (`0.3.0-m3`, versionCode 3). Nothing here has been tested against real Google Drive yet (see [SETUP.md](SETUP.md)).

## [0.3.0-m3] – 2026-11-16 (M3 code: hardening without Cloud credentials)
Commits `3496eb2` … `34e732b`. Not run on a phone yet (no emulator on the build box); see [docs/DEPLOY.md](docs/DEPLOY.md).

### Added
- **Deploy-readiness audit** (`3496eb2`): [docs/DEPLOY.md](docs/DEPLOY.md), the 2026 rules (developer verification, targetSdk, UIDT/FGS, OAuth Testing limits, signing, R8, 16 KB pages) checked against the code, with blockers and nice-to-haves.
- **Real Room migrations** (`d194e1c`): schema v5 with `Migration(4, 5)`; the destructive fallback is limited to pre-M2 v1–v3. `MigrationTest` builds a v4 DB with a paused batch, migrates it and checks that nothing was lost.
- **Faster resume** (`f052fed`): a pure-Kotlin `ResumableMd5` whose midstate is saved every 8 MB in `file_task.md5State`. A resume hashes only the bytes after the checkpoint; a missing or bad checkpoint falls back to hashing the whole partial. The final md5 check against Drive is unchanged.
- **Browser hardening** (`82e61ec`):
  - The path and selection survive process death (JSON in `SavedStateHandle`).
  - 429/5xx listings retry up to 3 times with the downloader's backoff (Retry-After honoured), showing "Drive is busy. Trying again…".
  - Pull to refresh keeps the current list on screen and says so if the refresh fails.
  - A shortcut and its target (or two shortcuts to one item) can't both be picked; a note says which pick already covers it.
- **Sign out / switch account** (`529edf3`): an account menu on Home. Signing out pauses the active download (`AUTH`, resumable), revokes the cached token, clears Credential Manager state, and forgets the email, cached listings and recent folders. Switching opens the account picker right away.
- **History** (`39c4c8c`): a Room-backed list of batches grouped by day, with counts, failed and skipped files with plain-language reasons, "Retry N failed", a per-file retry, "Download again" for cancelled/failed batches (a new batch with skip-existing on), and "Remove".
- **Release build** (`097e4a1`): signing config read from `stashd.release.*` in `~/.gradle/gradle.properties` (`TODO(release-signing)`; no keystore in the repo; unsigned without them). Resource shrinking is on. R8 rules cover Credential Manager, Google ID and serializers. `assembleRelease` passes, and signing was checked with a throwaway key that wasn't kept.

### Changed
- **The notification's Resume opens the app** (`456d754`), which then resumes. Android 14+ only allows a UIDT job to be scheduled while the app is visible.
- Version 0.3.0-m3, versionCode 3 (`34e732b`).
- 154 unit tests (134 at M2).

## [0.3.0-m2] – 2026-11-14 (dependable downloading)
Commits `b99194c` … `90dc6e3`.

### Added
- **Cloud setup markers** (`b99194c`): every credential touchpoint is tagged `TODO(cloud-setup)`, with a greppable list in SETUP.md. Demo mode keeps working without credentials.
- **Media download** (`7a4cd3d`):
  - `files.get?alt=media` with Range and `Accept-Encoding: identity`; the 206 / `Content-Range` reply is validated.
  - A 200 to a Range request means a restart; 416 and Drive's `cannotDownloadFile`, `cannotDownloadAbusiveFile`, `downloadQuotaExceeded` and `fileNotDownloadable` get their own errors.
  - New `fileMetadata`.
  - `FakeDriveRepository` now serves real throttled bytes and includes a Google Doc and a shortcut.
- **Resumable `FileDownloader` and destinations** (`5914699`):
  - `.stashdpart` temp + rename, Range resume, remote-change restart, size + md5 verification.
  - Free-space pre-check, ENOSPC → storage full, revoked SAF access detected.
  - `NameSanitizer` (illegal characters, 200-byte cap, "(2)" collisions); `SafDestination` built on DocumentFile.
- **Selection expansion** (`5ef13c3`): recursive, keeps relative paths (or flattens), dedups by target id (selected folder, shortcuts, multi-parent), cycle-safe, Docs and download-disabled files skipped with a reason.
- **Room v4** (`f0c0356`): `download_batch`, `download_item` and `file_task` with resume offsets and failure reasons; one active batch enforced in a transaction.
- **Batch runner and controller** (`72f7db7`):
  - Bounded retries with jittered exponential backoff and Retry-After.
  - Network loss hands the batch back to the scheduler.
  - One restart on checksum mismatch.
  - Terminal per-file errors with saved reasons; batch pauses for storage full, folder access lost, and auth/consent.
  - Pause/resume/cancel (cancel deletes partials, keeps finished files) and "retry failed files".
- **Schedulers** (`a82e1fb`):
  - A UIDT `DownloadJobService` on Android 14+ (`RUN_USER_INITIATED_JOBS`), with a WorkManager dataSync fallback; both share `DownloadExecution`.
  - Progress, paused and finished notifications, with Pause/Resume/Cancel actions.
- **UI wiring** (`90dc6e3`):
  - "Start download" and "Download this folder" create a batch, and the app asks for notification permission on 13+.
  - The Downloads screen shows the Room batch with live speed and ETA, Pause/Resume/Cancel/Retry, and the failed files.
  - SETUP.md now covers download behaviour and the phone checklist.

### Removed
- The M0 demo download worker (demo mode now downloads real generated bytes through the real pipeline).

### Changed
- `FileTreeDestination` moved to test sources. WorkManager's JobScheduler id range is set to 1000–5000 so it can't collide with the UIDT job ids (7000–7999).

### Tests
- 134 unit tests (75 at M1b).

## [0.2.1-m1b] – 2026-11-14 (M1a defect fixes)
- **Auth** (`d6be0ca`): the cached Drive token is keyed by account and dropped on account change.
- **Drive** (`1a6dbdd`): shortcuts keep their own identity; the target is stored separately.
- **Browser** (`922add2`): explicit incomplete listings (page cap, `incompleteSearch`) with Continue/Retry, never cached as complete.
- **Home** (`4d80916`): superseded link validation is cancelled and late responses are ignored.
- **Browser** (`be9af17`): "Download this folder" for the linked root and the open folder, in the same selection model.
- **Docs** (`59bca4e`): SETUP.md for M1b.
- DB schema v3 (shortcut target columns).

## [0.2.0-m1a] – 2026-11-14 (auth and browsing)
- **Cache** (`4dbc730`): Drive nodes keyed by file id, with a parent→child edge table (fixes the M0 "parent root loses nested folder" bug). DB v2.
- **Net** (`66b972d`): `Authorization`, cookies and resource keys redacted from HTTP logging.
- **Drive** (`4ce322e`): OkHttp `DriveRepository`: files.get, paginated files.list, resource keys.
- **Auth** (`ee83839`): sign-in, then the AuthorizationClient `drive.readonly` consent flow.
- **Links** (`5d35217`): URL-decoded `resourcekey`, empty keys ignored; parser tests.
- **Browser** (`495b7a5`): real folder navigation with resource keys end to end.
- **Docs** (`04e7275`): SETUP.md for M1a (demo mode, `stashd.fakeDrive` flag, live test checklist).

## [0.1.0-m0] – 2026-11-13 (scaffold)
- `a85519e`: project scaffold with Compose Material 3, Hilt, Room, WorkManager, the theme, 7 placeholder screens from the mockups, a demo foreground download worker, and SETUP.md.
