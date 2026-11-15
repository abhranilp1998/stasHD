# Stashd: setup (M1b)

Package: `com.abhranil.stashd` · minSdk 26 · targetSdk 35 · compileSdk 36 · JDK 17 · Gradle 8.14.3 · AGP 8.13.2 · Kotlin 2.2.21

The app works **without any Google Cloud setup** in *demo mode*: if no Web client ID is set, Welcome skips sign-in and the browser uses `FakeDriveRepository` (sample albums with nested folders, a resource-keyed folder, an empty folder, a no-access folder and 2-page listings). **Downloads → "Start demo batch"** still runs the simulated download.

Once `stashd.webClientId` is set (step 3), the real path is used: Sign in with Google → Drive consent (drive.readonly) → `RealDriveRepository` (Drive v3 over OkHttp). To force demo data in a debug build anyway, add `stashd.fakeDrive=true` to `~/.gradle/gradle.properties`.

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

## Cloud setup TODOs
Every place that needs real Google credentials is marked `TODO(cloud-setup)`. List them:
```
git grep -n "TODO(cloud-setup)" -- ':!SETUP.md'
# or: rg -n "TODO\(cloud-setup\)" -g '!SETUP.md'
```
| Where | What you do | Without it |
|---|---|---|
| `gradle.properties` | Nothing in the repo; put `stashd.webClientId=…` in **~/.gradle/gradle.properties** | Demo mode |
| `app/build.gradle.kts` (webClientId) | Web client ID → `BuildConfig.WEB_CLIENT_ID` | Empty placeholder = demo mode |
| `app/build.gradle.kts` (SHA-1 note) | Android OAuth client: `com.abhranil.stashd` + SHA-1 from `./gradlew signingReport` on each build machine | Sign-in fails with DEVELOPER_ERROR |
| `data/drive/DriveMode.kt` | Nothing; flips automatically when the ID is set | `useFake = true` |
| `di/AppModule.kt` (driveRepository) | Nothing; selects `RealDriveRepository` once configured | `FakeDriveRepository` |
| `data/auth/SignInClient.kt` | Uses the **Web** client ID as serverClientId | Sign-in skipped |
| `data/auth/AuthorizationBackend.kt` | Live AuthorizationClient path: Drive API on, consent screen (Testing, drive.readonly, you as test user) | Not called |
| `data/auth/TokenProvider.kt` | Nothing; demo short-circuit ends when configured | Grants without Google |
| `data/drive/RealDriveRepository.kt` | Nothing; first live run = SETUP §5 checklist | MockWebServer-tested only |

## 4. What's real vs stubbed (M1a)
| Area | Status |
|---|---|
| Theme, 7 screens, navigation | Done |
| Drive cache (Room v2): nodes keyed by file id + parent→child edges | Done, Room tests (the "parent root loses nested folder" bug) |
| Resource keys: link → route → view model → `X-Goog-Drive-Resource-Keys` header (folder being listed, shortcut targets) | Done, tests |
| HTTP log redaction (Authorization, cookies, resource keys, bearer/token params) | Done, tests |
| `RealDriveRepository`: files.get, paginated files.list (pageSize 1000), all-drives flags, 401 → clear token → retry once, error mapping | Done, MockWebServer tests; **not run against real Drive yet** |
| Sign-in → AuthorizationClient consent (PendingIntent), cancel/deny/unticked scope, in-memory token | Code done, mapping tested; **needs OAuth clients to test live** |
| Browser: tap folder enters, checkbox selects, breadcrumbs/back, loading/empty/error/consent/offline states, selection across folders | Done, view-model tests |
| M1b fixes: per-account token cache; shortcuts keep own identity (target stored separately); explicit incomplete listings (page cap / incompleteSearch, Continue/Retry, not cached); stale link validation cancelled + guarded; "Download this folder" (root or open folder) in the selection model | Done, regression tests |
| Recursive expansion, dedup, downloader, UIDT/WorkManager engine | M2 |
| History screen | Static sample |

DB note: schema is now v3 (v2: edge table, v3: shortcut target columns), destructive fallback (nothing real stored yet). Proper migrations come before M3.

## 5. First live test once OAuth is set up (M1 acceptance)
1. Install the debug build from **your** machine (its SHA-1 must be on the Android client).
2. Continue with Google → pick your account → allow "See and download your Google Drive files". Try Cancel once: you should get a calm "Drive access is needed…" message, and Continue works again.
3. Paste the music folder link → Open folder. Check the name shows, albums list, tap an album → tracks with sizes.
4. Paste a file link → expect "That's a file link…". Paste a folder you can't access → not-found message.
5. If you have an old link with `?resourcekey=`, open it and a subfolder (exercises the header).
6. Select a file in the root, enter an album, select a track, go back: both stay selected. Then tick "Download this whole folder": the earlier picks fold into it.
6b. Sign out of / switch to a second Google account (if you have one) and open a folder only that account can see.
7. Debug logcat (`OkHttp` tag): `Authorization: ██` and no `ya29.` anywhere.

## 6. Next milestones
M1 remainder (live verification + polish below) · M2 dependable downloading (UIDT on Android 14+, WorkManager fallback, shared downloader, recursive expansion, dedup, pause/resume) · M3 phone acceptance · later polish.
