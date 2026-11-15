package com.abhranil.stashd.data.drive

import com.abhranil.stashd.BuildConfig

/**
 * Sample data (no Google sign-in) when no Web client ID is configured, or a debug build forces it.
 * TODO(cloud-setup): becomes false automatically once stashd.webClientId is set (see app/build.gradle.kts);
 *   then sign-in, AuthorizationClient and RealDriveRepository are used. Nothing to change here.
 */
object DriveMode {
    val useFake: Boolean get() = BuildConfig.WEB_CLIENT_ID.isBlank() || BuildConfig.FORCE_FAKE_DRIVE
}
