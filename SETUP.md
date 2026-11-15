# Stashd: setup (M0)

Package: `com.abhranil.stashd` · minSdk 26 · targetSdk 35 · compileSdk 36 · JDK 17 · Gradle 8.14.3 · AGP 8.13.2 · Kotlin 2.2.21

M0 works **without any Google Cloud setup**. If no Web client ID is set, the Welcome screen skips sign-in, browsing uses a fake Drive repo (sample albums), and **Downloads → "Start demo batch"** runs a simulated 4-file download. That demo uses the real foreground service, notification and progress path.

---

## 1. Open in Android Studio
1. Unzip `stashd-m0.zip` (or clone the repo) somewhere.
2. Android Studio (Narwhal or newer) → **Open** → select the `stashd/` folder.
3. Settings → Build Tools → Gradle → **Gradle JDK = 17** (the bundled JBR 17/21 works too).
4. SDK Manager: install **Android 15 (API 35)** and **Android 16 (API 36)** platforms. Studio writes `local.properties` with your `sdk.dir` for you.
5. Sync, then run the `app` configuration on your phone (USB debugging on). On Android 13+, allow notifications when asked.

CLI equivalent: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.

## 2. Get YOUR debug SHA-1
Each machine has its own `~/.android/debug.keystore`, so the SHA-1 is different on every machine.
```
./gradlew signingReport      # look at Variant: debug → SHA1
```
For reference, the build box's key (only valid for APKs built on the box) is:
`48:20:53:36:66:33:12:AF:05:A6:ED:EF:6B:EE:F6:41:7F:83:F0:C5`

## 3. Google Cloud (you do this; nothing has been created for you)
1. https://console.cloud.google.com → **New project** (e.g. `stashd`).
2. APIs & Services → Library → **Google Drive API** → Enable.
3. **Google Auth Platform / OAuth consent screen**
   - User type: **External**, publishing status: **Testing**.
   - App name Stashd, your email as support and developer contact.
   - Data access / Scopes → add `https://www.googleapis.com/auth/drive.readonly` (restricted scope; that's fine while in Testing).
   - Audience → **Test users** → add your Google account (up to 100).
   - Note: in Testing, restricted-scope grants **expire after 7 days**. The app will ask for consent again; that's expected.
4. Clients → **Create client → Android**
   - Package name: `com.abhranil.stashd`
   - SHA-1: the value from step 2 (add one Android client per machine/keystore you build from).
5. Clients → **Create client → Web application** (name "Stashd web"). No redirect URIs needed. Copy its **Client ID**. Credential Manager needs this as the `serverClientId`.
6. Put the Web client ID in your **user** Gradle properties (not the repo):
   ```
   # ~/.gradle/gradle.properties   (Windows: %USERPROFILE%\.gradle\gradle.properties)
   stashd.webClientId=1234567890-xxxxxxxx.apps.googleusercontent.com
   ```
7. Rebuild. Welcome → **Sign in with Google** now uses Credential Manager, then asks for Drive read access through AuthorizationClient.

## 4. What's real vs stubbed in M0
| Area | Status |
|---|---|
| Theme (tokens, Outfit/Inter, shapes), 7 screens + nav | Done (placeholder UI, mockup layout) |
| Room schema v1 (roots, nodes, batches, items, file tasks) + DAOs | Done |
| Hilt, WorkManager on-demand init, dataSync FGS, notification channel and actions | Done |
| Link parsing (folder/file/open?id/resourcekey) | Done + 6 unit tests |
| SAF folder picker + persisted settings (DataStore) | Done |
| Sign-in (Credential Manager) + Drive token (AuthorizationClient) | Code written, needs step 3 |
| DriveRepository | **Fake** (`FakeDriveRepository`); real Drive v3 client is M1/M2 |
| DownloadWorker | Demo simulation; real `runBatch` (Range resume, SAF writes) is `TODO(M3)` |
| History screen | Static sample data |

## 5. Next milestones
M1 real auth + `files.list` browser · M2 recursive walk into Room · M3 real downloader (resume, parallel, SAF) · M4 polish/history/retry.
