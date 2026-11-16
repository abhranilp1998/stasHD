# Setup

## 1. Build on a PC

Open this folder in Android Studio, or install JDK 17 and Android SDK platform/build-tools 36. `local.properties` must point to the SDK. The app supports Android 8.0+ (API 26), targets API 35 and compiles against API 36.

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Debug builds default to generated sample data when no Web OAuth client ID is configured. The real download engine writes those sample bytes into the destination you choose. The sample root also includes an intentionally inaccessible folder; choose an accessible album for a successful demo download.

## 2. Configure Google access

Follow [RELEASING.md → Google Cloud setup](docs/RELEASING.md#google-cloud-setup). Create both an Android OAuth client and a Web application client. The Android client uses package `com.abhranil.stashd` and the permanent release certificate's SHA-1. The app uses the Web client's ID; it does not use a client secret.

Google Auth Platform must allow the account used on the phone as a test user, with `drive.readonly`. Enable Google Drive API in that same project. Testing access can expire and require renewed consent.

For a debug build that uses real Google, register the debug key separately; it does not share the release certificate. `./gradlew signingReport` prints the debug SHA-1. Use the signed release for normal phone testing.

## 3. Build signed APKs

PowerShell 7, Python 3, JDK 17 and Android SDK build-tools are needed for the release scripts.

```powershell
pwsh -File scripts/Setup-ReleaseSigning.ps1
pwsh -File scripts/Set-WebClientId.ps1 -ClientId 'YOUR_WEB_CLIENT_ID.apps.googleusercontent.com'
pwsh -File scripts/Build-Release.ps1
```

Use `-Demo` for the separately labelled sample-data package. A real release will fail to build if signing or OAuth configuration is absent. Back up the permanent key and its password as described in RELEASING.md.

## 4. Install

Download the APK attached to the GitHub release on the phone. Allow installation from the browser/file manager when Android asks. Same-key updates preserve app data; replacing a debug build with a differently signed release requires a separate migration plan or uninstall. Do not remove a build with useful state casually.

## 5. First real Drive run

1. Sign in with the Google account added to the project's test users and approve read-only Drive access.
2. Paste the link to a small folder you can access. Browse into a child folder and back.
3. Select a file and a folder containing that file. Start a download to a dedicated test destination.
4. Confirm the file was downloaded once, nested paths are correct, and file sizes/content match.
5. Check History for failures or skipped files. Native Google Docs/Sheets/Slides are deliberately skipped because export is not implemented.
6. Try denying/cancelling consent once, then grant it and resume. Old resource-key links should work when pasted in full.

## 5b. Device acceptance

Use a sample album or a sufficiently large real test file for interruption checks. Record the phone's Android version and each observed result in docs/DEPLOY.md.

- [ ] Pause during a file, then Resume. The partial remains usable.
- [ ] Lock the screen during downloading, then unlock and inspect progress.
- [ ] Lose the network and restore it. The same batch remains recoverable.
- [ ] Swipe the app out of Recents during a transfer.
- [ ] Use Android Task Manager Stop, reopen the app and explicitly resume the interrupted batch.
- [ ] Pause/Cancel/Resume through notifications; the correct batch opens.
- [ ] Deny notification permission and check that scheduling still behaves correctly.
- [ ] Regrant the chosen destination after access is lost. Selecting the same folder preserves partials; a different folder restarts unfinished files and leaves completed files where they were.
- [ ] Cancel: completed files remain and unfinished partials are removed where the destination is accessible.
- [ ] Install a newer APK signed with the same release key over a paused download and check that History, picks and offsets survive.

Emulator evidence is useful but does not establish OEM battery behavior, removable SD-card handling or live Google authorization. The current evidence is in [DEPLOY.md](docs/DEPLOY.md).
