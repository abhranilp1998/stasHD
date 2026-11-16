Signed Stashd release candidate for personal Google Drive downloads. Android 8.0+; package `com.abhranil.stashd`.

Download **Stashd-v0.3.1-rc.1.apk** from Assets on your phone and open it to install. Allow installation from that browser/file manager if Android asks. The attached SHA256SUMS.txt, public certificate and build-info.json identify the package and source commit.

This candidate fixes stalled Pause/Cancel, download recovery after a completed rename, folder-name collisions, partial-file checksum verification, Android user-stop recovery, consent/destination repair and History navigation. Database migrations preserve download-capable v4/v5 queues and history.

The APK uses the owner's configured Google OAuth project. Only that project's allowed test accounts can sign in while it is in Testing. Read-only Drive access is requested; no client secret is bundled.

**Acceptance status:** regression tests and emulator checks are recorded in docs/DEPLOY.md at this release commit. Live Google sign-in and the owner's physical-phone checks still need completion. This is a prerelease for that testing.

If an older debug APK is installed, Android may reject the different signing key. Do not uninstall a build containing useful history or queued downloads without recording what you need first. Subsequent releases keep this permanent key for in-place updates.
