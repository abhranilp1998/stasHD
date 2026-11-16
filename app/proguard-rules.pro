# Stashd R8 rules. Most libraries (Room, Hilt, WorkManager, OkHttp, Compose, DataStore, kotlinx.serialization)
# ship their own consumer rules; only what they don't cover is here. Verified by `./gradlew assembleRelease`.

# kotlinx.serialization: keep serializers of @Serializable classes (navigation routes, saved browser state,
# Drive JSON models). The library's own rules cover most of this; these make it explicit.
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}

# Credential Manager loads its Play Services provider by reflection (documented rule:
# https://developer.android.com/identity/sign-in/credential-manager#proguard).
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** { *; }

# Google ID token credential is parsed from a Bundle by class name.
-keep class com.google.android.libraries.identity.googleid.** { *; }

# Readable crash stacks for a sideloaded app (no Play Console to deobfuscate): keep line numbers.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
