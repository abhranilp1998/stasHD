package com.abhranil.stashd.download.core

import com.abhranil.stashd.data.auth.NeedsConsentException
import com.abhranil.stashd.data.db.FailureReason
import com.abhranil.stashd.data.db.PauseReason
import com.abhranil.stashd.data.drive.DriveError
import com.abhranil.stashd.download.storage.StorageError
import java.io.IOException
import kotlin.random.Random

/** What to do with a failure while downloading one file. */
sealed interface FailureDecision {
    /** Transient (429, 5xx, network, one checksum restart): try the same file again after a delay. */
    data class Retry(val kind: Kind, val retryAfterMs: Long? = null) : FailureDecision
    /** This file can never succeed as is: mark it FAILED with a reason, continue with the next. */
    data class Terminal(val reason: FailureReason) : FailureDecision
    /** Affects every file (storage, auth): pause the whole batch with a reason, resumable later. */
    data class PauseBatch(val reason: PauseReason) : FailureDecision

    enum class Kind { SERVER, NETWORK, CHECKSUM }
}

/**
 * Bounded retries with exponential backoff + jitter.
 *  - 429 / 5xx: up to [maxAttempts] per file, honouring Retry-After; then the file FAILS (RETRIES_EXHAUSTED).
 *  - Network IOException: up to [maxAttempts] in a row inside one run; then the run stops and asks the
 *    scheduler to retry when the network is back (the file stays queued). A file that keeps failing across
 *    runs fails after [maxNetworkAttemptsTotal].
 *  - Checksum mismatch: one full restart, then FAILED (CHECKSUM).
 */
class RetryPolicy(
    val maxAttempts: Int = 5,
    val maxNetworkAttemptsTotal: Int = 15,
    val maxChecksumAttempts: Int = 2,
    private val baseDelayMs: Long = 2_000,
    private val maxDelayMs: Long = 60_000,
    private val random: Random = Random.Default,
) {
    fun classify(e: Throwable): FailureDecision = when (e) {
        is StorageError.InsufficientSpace -> FailureDecision.PauseBatch(PauseReason.STORAGE_FULL)
        is StorageError.AccessRevoked -> FailureDecision.PauseBatch(PauseReason.DESTINATION_REVOKED)
        is NeedsConsentException -> FailureDecision.PauseBatch(PauseReason.NEEDS_CONSENT)
        is DriveError.Unauthorized -> FailureDecision.PauseBatch(PauseReason.AUTH)
        is DriveError.RateLimited -> FailureDecision.Retry(FailureDecision.Kind.SERVER, e.retryAfterMs)
        is DriveError.Server -> FailureDecision.Retry(FailureDecision.Kind.SERVER)
        is ChecksumMismatch -> FailureDecision.Retry(FailureDecision.Kind.CHECKSUM)
        is DriveError.RangeNotSatisfiable, is DriveError.RangeMismatch -> FailureDecision.Retry(FailureDecision.Kind.SERVER)
        is DriveError.NotFound -> FailureDecision.Terminal(FailureReason.NOT_FOUND)
        is DriveError.CannotDownload -> FailureDecision.Terminal(FailureReason.CANNOT_DOWNLOAD)
        is DriveError.NotDownloadable -> FailureDecision.Terminal(FailureReason.GOOGLE_DOC)
        is DriveError.Forbidden -> FailureDecision.Terminal(FailureReason.FORBIDDEN)
        is DriveError -> FailureDecision.Terminal(FailureReason.OTHER) // BadRequest, NotAFolder
        is IOException -> FailureDecision.Retry(FailureDecision.Kind.NETWORK)
        else -> FailureDecision.Terminal(FailureReason.OTHER)
    }

    /** Delay before attempt number [attempt] + 1 (attempt is 1-based count of failures so far). */
    fun delayMs(attempt: Int, retryAfterMs: Long? = null): Long {
        if (retryAfterMs != null && retryAfterMs > 0) return retryAfterMs.coerceAtMost(maxDelayMs * 5)
        val exp = (baseDelayMs shl (attempt - 1).coerceIn(0, 20)).coerceAtMost(maxDelayMs)
        // "Equal jitter": half fixed, half random, so retries from many phones don't line up.
        return exp / 2 + (random.nextDouble() * (exp / 2)).toLong()
    }
}
