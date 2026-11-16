# Stashd

Save selected Google Drive files and folders to your Android phone. Folder downloads are expanded recursively, overlapping selections are deduplicated, and partial downloads can resume.

**Current version: 0.3.1-rc.1.** This is a personal sideloaded app. Real Google access uses the owner's OAuth project and its allowed test accounts. Live Google sign-in and acceptance on the owner's physical phone must be completed before treating the candidate as ready for regular use.

## Install

Use the APK attached to [GitHub Releases](https://github.com/abhranilp1998/stasHD/releases). Release candidates are marked as prereleases.

- **Stashd** (`com.abhranil.stashd`): real Google Drive; requires the configured OAuth project.
- **Stashd Demo** (`com.abhranil.stashd.demo`): generated sample files, clearly labelled, with no Google sign-in. It can coexist with Stashd.

APK releases use a permanent signing certificate. Updates keep the same key and increase versionCode. A debug APK normally uses a different key and cannot be replaced by a release APK in place. See [signing and release instructions](docs/RELEASING.md).

## Download workflow

1. Sign in and grant read-only Drive access.
2. Paste a Drive folder link and select files or folders.
3. Choose a destination folder on the phone.
4. Start the download. Pause keeps partial files; Cancel removes unfinished partials and keeps completed files.

The app supports nested paths, shortcuts, resource-key links, retry/backoff, size/MD5 verification, and History with per-file retry. Android 14+ uses user-initiated data-transfer jobs; older versions use WorkManager. When permission or consent is needed, Downloads provides the recovery action.

One batch and one file run at a time. Google Docs/Sheets/Slides export is not implemented; skipped files and reasons appear in History. Remote files must be accessible to the selected Google account.

## Verification

- 177 JVM tests passed on Windows; Android lint reported zero issues.
- The installed Android 13 emulator upgrade from database v4 to v6 kept a paused batch, all 12 tasks, saved picks and 90,112 partial bytes exactly.
- That upgraded app resumed and completed 12 files (43,800,000 bytes). Independent checks matched every byte to the generated sample and found no remaining partial files.
- Signed demo packaging passed certificate, package-name, SHA-256, ZIP alignment and 64-bit native ELF alignment checks.

These checks do not substitute for live Google authorization or a physical phone. Current acceptance evidence and remaining checks are in [DEPLOY.md](docs/DEPLOY.md).

## Development

Kotlin / Jetpack Compose, Room v6, OkHttp, Hilt and WorkManager. minSdk 26, targetSdk 35, compileSdk 36; JDK 17 and Android SDK are required.

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
pwsh -File scripts/Build-Release.ps1 -Demo
```

Debug builds use sample data unless configured for real Drive. Real releases fail the build when signing or the Web OAuth client ID is missing. Signing keys and passwords stay outside Git and are not sent to GitHub Actions.

- [Setup and device checks](SETUP.md)
- [Build, sign and publish APKs](docs/RELEASING.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Changelog](CHANGELOG.md)
- [Longer-term roadmap](docs/ROADMAP.md)

The older images in `docs/screenshots/01-*` through `07-*` are design mockups, not evidence of running-app behavior.
