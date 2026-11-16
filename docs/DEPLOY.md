# Deploy readiness (sideloaded APK on your own phone)

"Deploy" here means: build an APK, install it on **your own phone** (no Play Store), and use it to download your Drive folders. Researched 16 Nov 2026 (IST); sources are linked inline. Setup steps: [../SETUP.md](../SETUP.md).

## Verdict

**NO-GO for real use yet.** It is **GO for a demo-mode test install** on your phone right now.

The code builds, all tests and lint pass, and the APK installs. But it can't reach your real Drive until **you** create the Google Cloud OAuth clients (blocker 1). It has never run on a real phone (blocker 2). It also needs a signing key you keep for good (blocker 3), because that key's SHA-1 is what you register with Google.

## Blockers (must do before relying on it)

| # | Blocker | Why | What to do | Status |
|---|---|---|---|---|
| 1 | **Google Cloud OAuth setup not done** | Without a Web client ID the app runs in demo mode only (sample data). Every spot is marked `TODO(cloud-setup)`. | Follow [SETUP.md §3](../SETUP.md): project, Drive API, consent screen (External, Testing, `drive.readonly`, you as test user), Android client (package + SHA-1 of the key that signs *your* APK), Web client → `stashd.webClientId` in `~/.gradle/gradle.properties`. | Owner |
| 2 | **Never run on a real phone** | UIDT jobs, SAF writes, notifications and network-loss behaviour are only unit-tested. No KVM emulator was available on the build box. | Install the debug APK in demo mode and run the [SETUP.md §5b](../SETUP.md) checklist. Then, after blocker 1, run §5 with a real folder. | Owner + M3 |
| 3 | **No permanent signing key** | An APK is only updatable by one signed with the same key. The Android OAuth client is tied to that key's SHA-1. The debug key differs per machine (the box's debug SHA-1 is in SETUP.md §2). A future Android developer registration also binds the package to your key. | Create a release keystore **once**, keep it outside git and back it up. Fill in the `stashd.release.*` properties (see "Release signing" below). Register its SHA-1 on the Android OAuth client, or add one Android client per key you use. | Placeholders in the build; key = owner |

Fixed in this phase (was a deploy risk):
- **Resume from the notification on Android 14+.** A UIDT job may only be scheduled while the app is visible, or when it could start an activity from the background. A broadcast from a notification button doesn't qualify ([UIDT docs](https://developer.android.com/develop/background-work/background-tasks/uidt)). The **Resume** action now opens the app, which resumes while visible. If UIDT is still refused, the WorkManager fallback runs.

## Checked against 2026 requirements

| Topic | Requirement (2026) | Stashd | Verdict |
|---|---|---|---|
| **Android developer verification / sideloading** | From 30 Sep 2026 apps must come from verified developers, but only for installs **from participating app stores** in Brazil, Indonesia, Singapore and Thailand. Global rollout to all certified devices is planned for **2027** ([Android developer verification](https://developer.android.com/developer-verification), [guide](https://developer.android.com/developer-verification/guides)). Unregistered apps can still be installed with **ADB** (no wait) or the **advanced flow**: Developer options → "Apps from unverified developers", restart, a one-time 24 h wait, then allow ([Android Authority](https://www.androidauthority.com/how-to-bypass-android-developer-verification-sideloading-rules-3718002/)). A free **limited-distribution** account (students/hobbyists, up to 20 devices, no ID or fee) is the official path for personal apps. | India, direct APK: **not affected today.** | OK now. For 2027, either use `adb install`, enable the advanced flow once, or register a limited-distribution account (needs the permanent key from blocker 3). |
| **targetSdk** | Play requires targetSdk 36 for new apps and updates from 31 Aug 2026 ([Play target API](https://support.google.com/googleplay/android-developer/answer/11926878)). Sideloading has no such rule; Android 14+ only blocks installs targeting below API 23. | targetSdk 35, compileSdk 36, minSdk 26 | OK for sideloading. Moving to 36 is a nice-to-have (it opts into Android 16 behaviours that need testing). |
| **UIDT (Android 14+)** | `setUserInitiated(true)`, `RUN_USER_INITIATED_JOBS`, network constraint, notification via `setNotification`, schedule while visible; exempt from standby-bucket quotas ([UIDT](https://developer.android.com/develop/background-work/background-tasks/uidt)). | All present (`UidtScheduler`, `DownloadJobService`). `RESULT_FAILURE` falls back to WorkManager. Task Manager stop = pause. | OK in code; needs a phone test. |
| **Android 16 job quotas** | Regular and expedited jobs, including WorkManager, follow runtime quotas even while the app is visible or a foreground service runs. Use UIDT for user-initiated transfers ([Android 16 changes](https://developer.android.com/about/versions/16/behavior-changes-all), [FGS changes](https://developer.android.com/develop/background-work/services/fgs/changes)). | Android 14+ uses UIDT; WorkManager runs only below 14 or if UIDT is refused. | OK. The fallback on 16+ can be stopped by quota; the runner re-queues and resumes from saved offsets. |
| **Android 15 dataSync FGS limit** | Apps targeting 35+: `dataSync` foreground services get 6 h per 24 h, then `onTimeout` and they must stop ([Android 15 changes](https://developer.android.com/about/versions/15/behavior-changes-15)). | targetSdk 35. Only the WorkManager fallback uses `dataSync`, and WorkManager stops the worker on timeout; the batch re-queues. | OK. On Android 15+ the main path is UIDT anyway. |
| **Foreground service type** | Android 14+ requires a declared type and its permission. | `SystemForegroundService` declared `dataSync`; `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` declared. | OK |
| **POST_NOTIFICATIONS (13+)** | Runtime permission. | Requested when you tap Start download; downloads still run if you deny it (on 14+ they show in Task Manager). | OK |
| **Battery optimisation** | UIDT ignores standby buckets. Foreground services survive Doze. Some OEM battery savers (Xiaomi, Oppo, Samsung "deep sleep") still kill apps. | No battery-exemption request. | Nice-to-have: set Stashd to "Unrestricted" battery in system settings if the fallback path gets killed on an Android 8–13 phone. |
| **OAuth: `drive.readonly`** | A restricted scope. In **Testing**: at most 100 test users, consent and refresh tokens **expire after 7 days**, and a "testing / unverified" warning is shown ([OAuth 2.0](https://developers.google.com/identity/protocols/oauth2), [consent screen](https://support.google.com/cloud/answer/10311615), [app states](https://developers.google.com/identity/protocols/oauth2/production-readiness/overview), [Drive scopes](https://developers.google.com/workspace/drive/api/guides/api-specific-auth)). | Testing mode, you as test user. On expiry the browser shows "needs your OK", and downloads **pause** (`NEEDS_CONSENT`/`AUTH`) instead of failing. | OK for personal use. Option: set the consent screen to *In production* while unverified. That removes the 7-day expiry but shows Google's "unverified app" screen and caps the app at 100 users. That's fine for one person; your choice. |
| **Release signing** | A signed APK is needed to install; updates need the same key. | `release` build type with signing config **placeholders** read from `~/.gradle/gradle.properties`; without them the release APK is unsigned. Debug APK = debug key. | Blocker 3 |
| **R8 / minify** | Hilt, Room, WorkManager, OkHttp and kotlinx.serialization ship their own consumer rules. Credential Manager needs an explicit keep for `androidx.credentials.playservices.**`. | `isMinifyEnabled = true` + resource shrinking for release; rules in `app/proguard-rules.pro` (Credential Manager keep, serializers, `@Serializable` routes). `assembleRelease` passes R8. | OK at build time. **Not run-tested** (no device). Test sign-in on a release build before relying on it. |
| **16 KB pages (Android 15+ devices)** | Native libs must be 16 KB aligned. | Only AndroidX's two tiny `.so` files; `zipalign -c -P 16` passes and the ELF LOAD alignment is 0x4000. | OK |

## Nice-to-haves (not blocking)
- targetSdk 36 (Android 16 behaviours), after a phone test.
- A "Battery: Unrestricted" shortcut for the Android 8–13 fallback.
- Crash reporting (a release build has none).
- Real-device runs on Android 14, 15 and 16, plus one Android 8–13 phone for the fallback path.

## Install (demo-mode test)
```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk   # ADB stays exempt from the verification flow
```
Or copy the APK to the phone and open it ("Install unknown apps" for your file manager). Play Protect may warn about an unknown developer; choose "Install anyway".

## Release signing (once you have a key)
```bash
keytool -genkeypair -v -keystore ~/keys/stashd-release.jks -alias stashd -keyalg RSA -keysize 4096 -validity 10000
```
```properties
# ~/.gradle/gradle.properties  (never commit these)
stashd.release.storeFile=/home/you/keys/stashd-release.jks
stashd.release.storePassword=…
stashd.release.keyAlias=stashd
stashd.release.keyPassword=…
```
Then `./gradlew assembleRelease` gives `app/build/outputs/apk/release/app-release.apk`. Get the SHA-1 with `./gradlew signingReport` (Variant: release) and add it to the Android OAuth client.
