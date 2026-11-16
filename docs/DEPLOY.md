# Release acceptance

Candidate: **0.3.1-rc.1**, versionCode 4. Evidence recorded on 16 November 2026. This checklist separates completed checks from work that needs the owner's Google account or physical phone.

## Completed locally

| Check | Evidence |
|---|---|
| Unit/regression tests | 177 tests passed on Windows/JDK 17. Covers stalled socket cancellation, rename-before-DB recovery, distinct folder allocation, changed partial bytes, consent recovery, destination repair and exact-batch routing. |
| Android lint | Zero issues. |
| Debug APK | Built and installed on Android 13. |
| Real upgrade | Installed the candidate over an older schema-v4 APK without uninstalling. Schema became v6; the paused batch, 12 tasks, saved selection and 90,112 partial bytes matched the pre-update snapshot exactly. |
| Resume after upgrade | Completed all 12 files, 43,800,000 bytes. Independently compared every downloaded byte with the deterministic sample generator; paths and sizes matched and no `.stashdpart` files remained. |
| History | The completed batch and its actual counts were visible in the running app. |
| Permanent key | Created outside Git; password protected locally with Windows DPAPI. Public certificate is in `release-certificate.pem`. |
| Signed demo APK | R8/resource shrinking, one signer matching the pinned certificate, expected demo package, SHA-256 manifest and ZIP alignment verified. |
| Signed UIDT runtime | Installed the minified signed demo on an API 37 emulator with 16 KB pages. A user stop left a recoverable pause; the retained job was cancelled and explicit Resume continued the batch. |
| Real-Drive package | Built with the supplied Web client ID, signed by the permanent key, installed and launched on API 37. Welcome displays Continue with Google; live sign-in remains untested. |
| UIDT interruptions | On the signed demo, Stop at 7.14 MB produced a system pause and removed the retained job. Explicit Resume continued, screen-off progress advanced to 22.53 MB, Wi-Fi loss requeued at 23.65 MB, and restoring Wi-Fi completed 12/12 files. All 43,800,000 bytes matched the expected sample and no partials remained. |
| Native page alignment | All four arm64/x86_64 native libraries have 16 KB-aligned ELF LOAD segments. |

The Android 13 upgrade test used a read-only clone of the existing AVD. Its low-storage reserve threshold was reduced inside the disposable clone to allow installation; the original AVD was not modified.

## Pending before regular real-Drive use

- [ ] Install the signed APK on the owner's physical phone and sign in using an allowed test account.
- [ ] Complete a small real-Drive nested folder download and check its files.
- [ ] Check Pause/Resume, screen lock, network loss, notification actions and app/process stop on that phone.
- [ ] Regrant a destination after access is lost; check consent renewal when Google asks for it.
- [ ] Confirm a same-key update preserves the phone's history and queue.

Do not mark these complete based on unit tests, design screenshots, or a successful APK build. Record the actual Android version and result when they are exercised.

## Runtime behavior and platform rules

- **Task Manager Stop is not a pause callback.** Android says it kills the process without calling `onStopJob()` and prevents automatic rescheduling. Stashd reconciles an orphaned active batch when reopened and exposes explicit Resume. [Android UIDT documentation](https://developer.android.com/develop/background-work/background-tasks/uidt).
- **Consent renewal needs UI.** Auth/consent failures pause the batch. Resume requests the necessary consent from the resumed Downloads screen before scheduling work. OAuth Testing restrictions and personal-use exceptions are described by [Google's app-state documentation](https://developers.google.com/identity/protocols/oauth2/production-readiness/overview) and [verification exceptions](https://support.google.com/cloud/answer/13464323).
- **A key is permanent release infrastructure.** Register the release certificate's SHA-1 in the Android OAuth client and use the same key for later APK updates. [Android signing documentation](https://developer.android.com/studio/publish/app-signing).
- **Sideload rules vary by region and install path.** Use current guidance for limited distribution, advanced sideloading and ADB. Do not assume all non-Play installs are exempt. [Android developer verification](https://developer.android.com/developer-verification/guides).

Target SDK remains 35 and compile SDK 36. This project is not being submitted to Play; a Play deadline is not an acceptance test for this sideloaded APK. OEM battery management, removable storage and prolonged background transfers still need physical-device evidence.

## Publishing

See [RELEASING.md](RELEASING.md). Publish the signed APK, SHA256SUMS.txt, public certificate and build-info.json from the reviewed source commit. Keep candidates as prereleases until real-use acceptance is complete. Demo releases must retain their separate package and sample-data label.
