# Stashd priorities

Current candidate: **0.3.1-rc.1**. The immediate goal is dependable selected-file and folder downloads on the owner's phone, installed from GitHub Releases. [DEPLOY.md](DEPLOY.md) records completed checks and remaining acceptance work.

## Implemented

- Google account sign-in and separate read-only Drive authorization; paginated browsing, resource keys and shortcuts.
- Persistent selections, recursive expansion, folder structure, duplicate-file handling and distinct destination names.
- Resumable transfers, actual partial-file checksum verification, bounded retries, storage failures and cancellation of stalled network reads.
- Room migrations from download-capable schema v4 through v6, plus recovery when the app dies between file rename and database completion.
- One active batch, Pause/Resume/Cancel, destination repair, History and retries that open the selected batch.
- Android 14+ user-initiated jobs and WorkManager fallback on Android 8–13.
- Permanent local signing key, distinct signed demo package, release checks and GitHub publishing scripts.

These features being implemented does not mean every device or live Google scenario has passed. The owner account's sign-in and physical-phone checks remain acceptance work.

## Before regular use

- Finish the Web OAuth client and configure the real-Drive release.
- Run the physical-phone checks in [SETUP.md](../SETUP.md), including background execution, network loss, notification actions, access renewal and a same-key update.
- Back up the permanent signing key and its password outside this PC.
- Publish the verified signed candidate and its checksum from the reviewed commit.

## Later functionality

- Google Docs/Sheets/Slides export; these currently appear as skipped items.
- Empty-directory creation, very large/deep folder acceptance and removable-storage testing.
- Several simultaneous files and a multi-batch queue.
- Per-file progress details, Open folder from History and accessibility acceptance on a phone.
- General HTTP sources, scheduled transfers and optional network/speed controls.

Target SDK upgrades and a public-distribution OAuth strategy belong to a later distribution review. They do not replace the phone and download checks above.
