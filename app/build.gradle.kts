plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.abhranil.stashd"
    // Libraries in the catalog need compileSdk 36; the app still targets 35.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.abhranil.stashd"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0-m1a"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // TODO(cloud-setup): Web OAuth client ID. Empty placeholder = demo mode. Set stashd.webClientId in
        //   ~/.gradle/gradle.properties (never in the repo); it becomes BuildConfig.WEB_CLIENT_ID.
        // TODO(cloud-setup): the Android OAuth client must list package com.abhranil.stashd + the SHA-1 of the
        //   keystore that signs THIS build (debug: ~/.android/debug.keystore on the building machine).
        //   Get it with `./gradlew signingReport` (Variant: debug → SHA1). Each build machine needs its own entry.
        val webClientId = (project.findProperty("stashd.webClientId") as String?)?.trim() ?: ""
        buildConfigField("String", "WEB_CLIENT_ID", "\"$webClientId\"")
        // Debug aid: stashd.fakeDrive=true forces the sample-data repository even when a client ID is set.
        val fakeDrive = (project.findProperty("stashd.fakeDrive") as String?)?.toBoolean() ?: false
        buildConfigField("boolean", "FORCE_FAKE_DRIVE", fakeDrive.toString())
    }

    // Release signing. NO keystore lives in this repo. TODO(release-signing): create one once (see docs/DEPLOY.md,
    //   "Release signing"), keep it and its passwords OUTSIDE the repo, back it up (losing it means users must
    //   uninstall to update), and put these four in ~/.gradle/gradle.properties:
    //     stashd.release.storeFile=/absolute/path/stashd-release.jks
    //     stashd.release.storePassword=...
    //     stashd.release.keyAlias=stashd
    //     stashd.release.keyPassword=...
    // TODO(cloud-setup): the release key's SHA-1 needs its OWN Android OAuth client entry (see SETUP.md);
    //   the debug SHA-1 doesn't cover release builds.
    // Without the properties, assembleRelease still builds (and runs R8) but the APK is unsigned.
    val releaseStoreFile = (project.findProperty("stashd.release.storeFile") as String?)?.takeIf { it.isNotBlank() }
    signingConfigs {
        if (releaseStoreFile != null) create("release") {
            storeFile = file(releaseStoreFile)
            storePassword = project.findProperty("stashd.release.storePassword") as String?
            keyAlias = project.findProperty("stashd.release.keyAlias") as String?
            keyPassword = project.findProperty("stashd.release.keyPassword") as String?
        }
    }

    buildTypes {
        release {
            buildConfigField("boolean", "FORCE_FAKE_DRIVE", "false")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseStoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    // Exported Room schemas as debug-only assets: Robolectric unit tests read the app variant's merged assets,
    // so this is how MigrationTestHelper finds old versions. Not in the release APK.
    sourceSets.getByName("debug").assets.srcDir("$projectDir/schemas")
    lint {
        warningsAsErrors = false
        abortOnError = true
        // targetSdk 35 is requested for M0; bump in M5.
        disable += setOf("OldTargetApi", "GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
    }
}

kotlin {
    jvmToolchain(17)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.documentfile)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)
    implementation(libs.play.services.auth)

    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
}
