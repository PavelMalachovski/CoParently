# R8 configuration for the release build (`isMinifyEnabled = true`).
#
# This file was empty until the August 2026 security audit, which is how the two
# problems below shipped: neither is reproducible in debug, because debug does not
# minify.

# ---- Crash reports must stay readable ------------------------------------
# Without these, every release stack trace in Crashlytics is a list of obfuscated
# frames with no line numbers, so a production crash cannot be located in the source
# even though the mapping file is uploaded. `-renamesourcefileattribute` keeps the
# original file names out of the shipped binary while the mapping still resolves them.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- Gson reflects over these classes ------------------------------------
# `ChildInfoRepositoryImpl` stores a child's medications, activities, emergency
# contacts, school details and medical profile as Gson JSON in Room, and
# `ChildInfoRepositoryImpl.toFirestoreMap()` puts the medical profile on the wire as a
# map produced by `gson.fromJson(gson.toJson(profile), Map::class.java)`. Gson derives
# every one of those keys from the Kotlin *field names* by reflection.
#
# R8 renames fields it cannot see being read. Left unkept, a release build writes
# `{"a":"penicillin"}` where the model says `{"allergies":"penicillin"}` — and that is
# not a cosmetic difference:
#   * the co-parent's device reads the Firestore map by real key names and finds none,
#     so a child's medical profile arrives empty on the other parent's phone;
#   * the obfuscated names are not stable across builds, so a row written by version N
#     no longer parses after the user updates to version N+1 — data already on the
#     device silently reads back as blank.
# Field names, not just the classes, therefore have to survive.
-keepclassmembers class com.coparently.app.domain.model.** {
    <fields>;
}
-keepclassmembers class com.coparently.app.presentation.event.EventDraft {
    <fields>;
}

# Gson's own requirements for reflective (de)serialization: generic signatures for
# `Array<T>`/`Map` targets, and the annotations it reads off members.
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**

# ---- Strip verbose logging from the release binary -----------------------
# `Log.d`/`v`/`i` calls in this codebase carry family data: calendar contents, chat text,
# sign-in emails, sync payloads. In a release build
# those land in logcat, where any app holding READ_LOGS on a rooted or developer
# device — and any bug-report capture — can read them. The call sites that logged
# personal data outright were removed in the same audit; this is the backstop that
# keeps a future `Log.d("...", "$message")` out of a shipped APK.
#
# `Log.w` and `Log.e` are deliberately kept: they are what makes a production crash
# report legible, and they are reviewed not to carry content.
# Requires the optimizing configuration, which `proguard-android-optimize.txt`
# (referenced from `build.gradle.kts`) supplies.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}

# ---- SQLCipher (SEC-2) ---------------------------------------------------
# The database is opened through `net.zetetic.database.sqlcipher`, whose native layer
# calls back into Java by name: R8 renaming one of those members produces a
# `NoSuchMethodError` at the first query rather than a build error, in a release
# build only, before Room's first statement runs — a launch crash, not a degradation.
#
# This is the first rule of the `proguard.txt` the AAR itself ships, copied verbatim
# (AGP extracts and applies that file, so this is a duplicate rather than a fix). It is
# repeated here on purpose: if that extraction ever silently stops happening, the
# failure is a crash on a shipped build, and the cost of the duplicate is nothing. The
# two `-keepclassmembers` rules the AAR also carries are left to it — they cover
# `SQLiteCustomFunction` and `SQLiteDebug$PagerStats`, neither of which this app
# reaches. Deliberately not a blanket `{ *; }` keep of the package: that would pin
# megabytes of unreachable code and hide which members actually have to survive.
-keep class net.zetetic.** {
    native <methods>;
    private native <methods>;
    public <init>(...);
    long mNativeHandle;
}
-dontwarn net.zetetic.database.**
