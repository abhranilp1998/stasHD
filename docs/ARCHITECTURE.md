# Architecture

Stashd is one Android Gradle module, `com.abhranil.stashd`. The download engine is independent of Android scheduling; UIDT and WorkManager both call the same batch runner.

## Flow

Welcome authorizes Google access. Home validates a Drive folder link. Browser stores selections with folder IDs, names and resource keys. Start records a batch and its picks before scheduling it. Selection expansion recursively produces one task per target file ID, deduplicating overlaps and shortcuts.

Folder allocation uses Drive identities as well as display names. Distinct sibling folders retain separate destination directories even when their names are identical, differ only by case, or sanitize to the same string. Files and directories share the destination-name reservation set.

## Download storage and integrity

`FileDownloader` writes a `.stashdpart` file through a `Destination` interface. SAF is the phone implementation; tests use ordinary temporary directories. Resume starts at the actual partial length, checks the remote fingerprint, and uses Range. Size and MD5 are checked before the final rename.

Production hashing uses `java.security.MessageDigest`. Partial bytes are re-read on resume. Legacy M3 hash checkpoints remain in the database for upgrade compatibility, but are not trusted: a saved midstate cannot detect a corrupt or edited prefix. The old checkpoint producer exists only in test fixtures to verify this case.

The task stores verified size, checksum and final/temp names before renaming. On recovery, a fully matching final file can be adopted if the rename finished before Room recorded completion. A same-size unrelated file is not accepted.

Coroutine cancellation interrupts a stalled media request using OkHttp `Call.cancel()`. The reader is allowed to unwind before the response body and output file are closed, avoiding concurrent buffered reads/closes. Pause and Cancel wait for the runner before cleaning up files.

## Durable state

Room schema **v6** contains Drive roots/nodes/edges/listings and download batches, picks and file tasks. Migration 4→5 adds the legacy checkpoint. Migration 5→6 adds finalization verification and selected-parent IDs. From v4 onward, upgrades use migrations; destructive fallback is limited to pre-download dev schemas 1–3.

Only one active batch is allowed. Downloads defaults to the active batch, then the most recent batch. History and notification actions can route to a specific batch so a retried older batch is not confused with newer history.

Pause retains partials. Cancel removes unfinished partials and keeps finished files. Changing the destination of a paused batch restarts unfinished files in the new folder; completed files retain their original URIs. Choosing the original folder again keeps resume offsets.

## Scheduling and recovery

Android 14+ uses a user-initiated data-transfer job with its required notification. Earlier versions, or a refused UIDT schedule, use a WorkManager dataSync foreground worker. `RunRegistry` permits one executing batch per process.

System callbacks requeue interrupted tasks where available. Task Manager Stop can kill the process without a callback. Startup recovery checks Android’s recorded exit reason before either scheduler can copy bytes. A new user-stop record pauses the prior batch and cancels retained jobs; an orphan with no live runner or scheduled work also becomes a recoverable system pause. Exit records are acknowledged once, so an old stop does not pause a newly created batch.

Notification taps open Downloads. A Resume action is processed when that screen is resumed, where interactive consent or destination repair can be requested before scheduling.

## Account and release configuration

Google access is read-only. Tokens are in memory, keyed to the selected account; HTTP logs redact credentials and resource keys. Signing out pauses active work, forgets the account/token and clears Drive caches while keeping local download history and files.

Release and demo APKs use separate application IDs. Real releases require a Web OAuth client ID and permanent signing configuration. The demo is explicitly labelled and forces sample data. CI runs checks without signing credentials; the local release script signs, verifies and packages installable assets. See RELEASING.md for the key and publishing procedure.
