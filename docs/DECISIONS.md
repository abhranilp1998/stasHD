# Decisions

Short ADR-style records. Status is "accepted" unless noted. Related: [ARCHITECTURE.md](ARCHITECTURE.md), [ROADMAP.md](ROADMAP.md), [../SETUP.md](../SETUP.md).

## ADR-1: Kotlin + Jetpack Compose, native Android
- **Context:** an Android-only hobby app that needs SAF, foreground and background jobs, and tight control of notifications.
- **Decision:** Kotlin 2.2, Jetpack Compose with Material 3, Hilt, Room, Coroutines/Flow, and OkHttp straight against the Drive v3 REST API (no Google API client library).
- **Consequences:**
  - Platform APIs (UIDT, AuthorizationClient, SAF) are used directly.
  - The REST client is small and fully testable with MockWebServer.
  - No iOS or desktop.

## ADR-2: `drive.readonly` scope, in Testing mode
- **Context:** the app must list and download folders that were **shared by link** by someone else, including old `resourcekey` links. `drive.file` only covers files the user opened with the app or Picker.
- **Decision:** request only `https://www.googleapis.com/auth/drive.readonly` through AuthorizationClient, after a Credential Manager sign-in. The OAuth app stays in **Testing** with the owner as test user.
- **Consequences:**
  - It is a *restricted* scope. That's fine for a personal app; a public release would need Google verification or `drive.file` + Picker (v2 backlog).
  - Testing-mode grants expire after 7 days. The app asks for consent again, and downloads pause with `NEEDS_CONSENT`/`AUTH` rather than failing.
  - It is read-only, so the app can never modify Drive.

## ADR-3: UIDT on Android 14+, WorkManager fallback, one shared core
- **Context:** multi-GB downloads must survive screen-off, swipe-away and network loss. WorkManager foreground workers are subject to FGS limits (Android 15's dataSync time limit). User-initiated data transfer jobs exist only from API 34.
- **Decision:**
  - Use a JobScheduler UIDT job (`DownloadJobService`) on API 34+, and a WorkManager long-running `dataSync` worker below 34 or when the system refuses the UIDT job.
  - Both call `DownloadExecution` → `BatchRunner`. The downloader core (`download/core`) has no scheduler or UI dependencies.
- **Consequences:**
  - There are two adapters to keep correct.
  - The core is unit-tested once, against fakes.
  - A Task Manager stop on 14+ maps to Pause.
  - Real-device behaviour is still unverified (M3).

## ADR-4: Single Gradle module for now
- **Context:** one developer, a hobby scope, fast iteration.
- **Decision:** one `:app` module with layered packages (`ui`, `data`, `download/core`, `download/worker`, `di`). Testability comes from interfaces (`DriveRepository`, `Destination`, `DownloadScheduler`, `FolderCache`), not module boundaries.
- **Consequences:** simpler builds. `download/core` is ready to become its own module (a v2 any-link source could reuse it) if that is ever worth it.

## ADR-5: One active batch at a time
- **Context:** parallel batches complicate progress, notifications, storage checks and SAF writes. The target use is "download this one folder".
- **Decision:** a batch counts as active while `EXPANDING`, `QUEUED`, `RUNNING` or `PAUSED`. `DownloadDao.createBatch` checks and inserts in one transaction and throws `ActiveBatchExists`, and the UI says "Another download is still active…". Files inside a batch also run one at a time in M2.
- **Consequences:** simple and predictable. A queue and parallel files are later work (M3 for parallel files, v2 for the queue).

## ADR-6: Pause keeps partials; cancel deletes partials
- **Context:** users need a cheap way to stop for a while, and a clean way to give up.
- **Decision:**
  - Pause (and any system stop) keeps `.stashdpart` files and `bytesDone`, and resumes with Range after re-checking the remote fingerprint.
  - Cancel deletes the partial files of unfinished tasks and marks them `CANCELLED`. **Files that already finished are kept.**
- **Consequences:**
  - No half-written files are left behind after a cancel, though empty directories may remain.
  - A cancelled batch can't be resumed; start a new one (and enable "skip files already on phone").

## ADR-7: Name "Stashd"
- **Context:** the app's job is to stash a Drive folder on the phone for offline use.
- **Decision:** the product name is **Stashd**, with package `com.abhranil.stashd`.
- **Consequences:** the name is used in the app label, the OAuth consent screen app name ([SETUP.md](../SETUP.md)) and the repo. Trademark and store checks are needed only if it is ever published.
