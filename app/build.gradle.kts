plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

fun setting(property: String, environment: String): String? =
    providers.environmentVariable(environment).orElse(providers.gradleProperty(property)).orNull

val oauthWebClientId = setting("stashd.webClientId", "STASHD_WEB_CLIENT_ID")?.trim().orEmpty()
val releaseStorePath = setting("stashd.release.storeFile", "STASHD_RELEASE_STORE_FILE")?.takeIf { it.isNotBlank() }
val releaseStoreSecret = setting("stashd.release.storePassword", "STASHD_RELEASE_STORE_PASSWORD")
val releaseAlias = setting("stashd.release.keyAlias", "STASHD_RELEASE_KEY_ALIAS")
val releaseKeySecret = setting("stashd.release.keyPassword", "STASHD_RELEASE_KEY_PASSWORD")

android {
    namespace = "com.abhranil.stashd"
    // Libraries in the catalog need compileSdk 36; the app still targets 35.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.abhranil.stashd"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.3.1-rc.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // TODO(cloud-setup): Web OAuth client ID. Empty placeholder = demo mode. Set stashd.webClientId in
        //   ~/.gradle/gradle.properties (never in the repo); it becomes BuildConfig.WEB_CLIENT_ID.
        // TODO(cloud-setup): the Android OAuth client must list package com.abhranil.stashd + the SHA-1 of the
        //   keystore that signs THIS build (debug: ~/.android/debug.keystore on the building machine).
        //   Get it with `./gradlew signingReport` (Variant: debug → SHA1). Each build machine needs its own entry.
        require(oauthWebClientId.isEmpty() || oauthWebClientId.matches(Regex("[0-9]+-[A-Za-z0-9_-]+\\.apps\\.googleusercontent\\.com"))) { "stashd.webClientId must be an OAuth Web client ID." }
        buildConfigField("String", "WEB_CLIENT_ID", "\"$oauthWebClientId\"")
        // Debug aid: stashd.fakeDrive=true forces the sample-data repository even when a client ID is set.
        val fakeDrive = (project.findProperty("stashd.fakeDrive") as String?)?.toBoolean() ?: false
        buildConfigField("boolean", "FORCE_FAKE_DRIVE", fakeDrive.toString())
    }

    // Release signing stays outside this repo. See docs/RELEASING.md for the local signing scripts and backup.
    // Gradle properties can also supply the following settings:
    //     stashd.release.storeFile=/absolute/path/stashd-release.jks
    //     stashd.release.storePassword=...
    //     stashd.release.keyAlias=stashd
    //     stashd.release.keyPassword=...
    // TODO(cloud-setup): the release key's SHA-1 needs its OWN Android OAuth client entry (see SETUP.md);
    //   the debug SHA-1 doesn't cover release builds.
    // Release tasks reject missing signing configuration; real-Drive releases also require the Web client ID.
    signingConfigs {
        if (releaseStorePath != null) create("release") {
            storeFile = file(releaseStorePath)
            storePassword = releaseStoreSecret
            keyAlias = releaseAlias
            keyPassword = releaseKeySecret
        }
    }

    buildTypes {
        release {
            buildConfigField("boolean", "FORCE_FAKE_DRIVE", "false")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseStorePath != null) signingConfig = signingConfigs.getByName("release")
        }
        create("demoRelease") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
            buildConfigField("String", "WEB_CLIENT_ID", "\"\"")
            buildConfigField("boolean", "FORCE_FAKE_DRIVE", "true")
            resValue("string", "app_name", "Stashd Demo")
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

fun verifySigning() {
    require(releaseStorePath != null && file(releaseStorePath).isFile) { "A permanent release keystore is required. See docs/RELEASING.md." }
    require(!releaseStoreSecret.isNullOrEmpty() && !releaseAlias.isNullOrBlank() && !releaseKeySecret.isNullOrEmpty()) { "Release signing credentials are incomplete. See docs/RELEASING.md." }
}

val verifyReleaseConfiguration = tasks.register("verifyReleaseConfiguration") {
    doLast {
        verifySigning()
        require(oauthWebClientId.isNotBlank()) { "Real-Drive releases require STASHD_WEB_CLIENT_ID. Use assembleDemoRelease for the separately labelled demo." }
    }
}
val verifyDemoSigning = tasks.register("verifyDemoSigning") { doLast { verifySigning() } }
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(verifyReleaseConfiguration) }
tasks.matching { it.name == "preDemoReleaseBuild" }.configureEach { dependsOn(verifyDemoSigning) }

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
