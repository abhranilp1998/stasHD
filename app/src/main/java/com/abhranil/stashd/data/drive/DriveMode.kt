package com.abhranil.stashd.data.drive

import com.abhranil.stashd.BuildConfig

/** Sample data (no Google sign-in) when no Web client ID is configured, or a debug build forces it. */
object DriveMode {
    val useFake: Boolean get() = BuildConfig.WEB_CLIENT_ID.isBlank() || BuildConfig.FORCE_FAKE_DRIVE
}
