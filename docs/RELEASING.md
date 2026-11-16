# Sideload releases

Stashd is distributed as an APK through [GitHub Releases](https://github.com/abhranilp1998/stasHD/releases). Keep the same signing key for every update. Signing a replacement with another key prevents an in-place update; increasing versionCode and keeping the key allows Android to preserve app data. [Android signing documentation](https://developer.android.com/studio/publish/app-signing).

## Two separate packages

| Build | Package | Purpose |
|---|---|---|
| Release | `com.abhranil.stashd` | Real Google Drive. Build fails if the Web OAuth client ID or signing configuration is missing. |
| Demo release | `com.abhranil.stashd.demo` | Sample Drive data and real local file writes. Labelled **Stashd Demo**; can coexist with the real app. |

Demo releases are not evidence that live OAuth or real Drive downloads work. Release candidates remain prereleases until the acceptance checklist in DEPLOY.md is completed.

## Permanent signing key

On this Windows account, run once with PowerShell 7:

```powershell
pwsh -File scripts/Setup-ReleaseSigning.ps1
```

The key lives at `$env:USERPROFILE\.stashd\signing\stashd-release.p12`, outside the checkout. Its random password is protected by Windows DPAPI for this account. The directory is accessible to this user and SYSTEM. The script never overwrites an existing key. Only the public certificate is committed in `docs/release-certificate.pem`.

Back up the `.p12` key in your normal secure backup and save its password in a password manager. To copy the password locally, run `pwsh -File scripts/Copy-SigningPassword.ps1`; save it, then clear the clipboard. A DPAPI file by itself will not recover the password after reinstalling Windows. Do not put keys or passwords in Git, issues, release assets, or chat. The key stays on the build PC; GitHub Actions does not receive it.

Public release certificate:

- SHA-1: `DE:0F:11:C3:CC:0C:C4:5D:C1:49:06:86:B9:6B:78:D1:3C:DA:A5:7C`
- SHA-256: `FC:47:4F:48:53:E8:EB:B5:C2:2A:C9:00:77:DC:BA:99:14:51:EC:F3:A6:EE:AB:0F:60:57:A1:E9:2C:46:71:3C`

Register this SHA-1 and package `com.abhranil.stashd` in the Android OAuth client. A debug SHA-1 does not authorize this release key.

## Google Cloud setup

1. Open [Google Cloud Console](https://console.cloud.google.com/), select or create a Stashd project, and enable **Google Drive API**.
2. In **Google Auth Platform**, configure the app for **External / Testing**. Add the Google account you will use on the phone to test users. Add `https://www.googleapis.com/auth/drive.readonly` under Data Access.
3. Under **Clients**, create an **Android** client with package `com.abhranil.stashd` and the release SHA-1 above.
4. Create a **Web application** client. Copy its **Client ID**, not its client secret. No server or redirect URI is used by this Android app.
5. Run `pwsh -File scripts/Set-WebClientId.ps1 -ClientId '…apps.googleusercontent.com'`. This stores only the public ID locally for the release script. Alternatively, use `stashd.webClientId` in Gradle's user properties or the `STASHD_WEB_CLIENT_ID` environment variable. Never supply a client secret to this app.

Testing accounts can require renewed consent after seven days. Downloads must request consent from the resumed app, then continue. Personal-use exceptions and the unverified-app warning are described by [Google](https://support.google.com/cloud/answer/13464323); publishing a GitHub APK does not itself verify the OAuth application.

## Build and verify

```powershell
pwsh -File scripts/Build-Release.ps1 -Demo # signed sample-data APK
pwsh -File scripts/Build-Release.ps1       # signed real-Drive APK; needs OAuth ID
```

The script runs unit tests and lint, builds with R8/resource shrinking, verifies the APK signature against the committed public certificate, checks 16 KB ZIP and native ELF alignment, and verifies the package name. It writes the APK, SHA256SUMS.txt, public certificate and build-info.json under `output/releases/`. `-SkipChecks` is for local iteration; publishing always runs the checks. Python 3 is required for the native-library check.

CI independently runs tests, lint and a debug build on Linux. CI artifacts are reports, not installable release downloads.

## Publish to GitHub

Install the official [GitHub CLI](https://cli.github.com/) and authenticate using its normal browser flow:

```powershell
gh auth login --web --git-protocol https
pwsh -File scripts/Publish-Release.ps1 -Demo          # draft demo prerelease
pwsh -File scripts/Publish-Release.ps1                # draft real-Drive release candidate
pwsh -File scripts/Publish-Release.ps1 -Publish       # publish after acceptance
```

Commit and push the reviewed source first. The script targets that exact commit, attaches the verified files, and makes versions containing a suffix such as `-rc.1` prereleases. Review notes live in `docs/releases/` and must describe the checks actually completed. Never present a sample-data APK as the real app.

## Install or update

Download the APK from the release page on the phone. If Android asks, allow installation from the browser/file manager you used. Verify its SHA-256 against SHA256SUMS.txt if checking the download manually.

An existing debug APK normally has a different key from the permanent release. Do not uninstall a build containing important state without exporting or otherwise recording what you need; files in the chosen shared destination remain separate from app data. Demo and real releases use different package IDs and do not replace each other.

For USB testing, `adb install -r path/to/Stashd.apk` updates a same-key installation. Developer-verification install paths depend on Android version, region and distribution; [Android's current guidance](https://developer.android.com/developer-verification/guides) covers limited distribution, advanced sideloading and ADB. Do not infer that every non-Play install is exempt.
