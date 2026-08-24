plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("io.gitlab.arturbosch.detekt")
    kotlin("kapt")
}

// The Google Services and Firebase Crashlytics Gradle plugins both require a
// google-services.json. It is gitignored, so on CI (and any fresh clone without it)
// applying them would fail — the Crashlytics plugin's release mapping-upload task
// errors with "Google-Services plugin not configured properly".
// Apply both plugins only when the file is present. Runtime Crashlytics still works
// everywhere via the firebase-crashlytics-ktx SDK dependency; only build-time mapping
// upload is skipped when the config is absent.
val hasGoogleServicesJson =
    file("google-services.json").exists() ||
    file("src/google-services.json").exists() ||
    file("src/debug/google-services.json").exists() ||
    file("src/release/google-services.json").exists()

if (hasGoogleServicesJson) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

android {
    namespace = "com.coparently.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.coparently.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "com.coparently.app.HiltTestRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            buildConfigField("Boolean", "ENABLE_CRASHLYTICS", "false")
            buildConfigField("Boolean", "ENABLE_ANALYTICS", "false")
            // Gemini API key from gradle.properties or environment variable
            buildConfigField("String", "GEMINI_API_KEY", "\"${project.findProperty("GEMINI_API_KEY") ?: System.getenv("GEMINI_API_KEY") ?: ""}\"")
            // Google OAuth client secret — never committed; supplied via ~/.gradle/gradle.properties or env
            buildConfigField("String", "GOOGLE_CLIENT_SECRET", "\"${project.findProperty("GOOGLE_CLIENT_SECRET") ?: System.getenv("GOOGLE_CLIENT_SECRET") ?: ""}\"")
        }

        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("Boolean", "ENABLE_CRASHLYTICS", "true")
            buildConfigField("Boolean", "ENABLE_ANALYTICS", "true")
            // Gemini API key from gradle.properties or environment variable
            buildConfigField("String", "GEMINI_API_KEY", "\"${project.findProperty("GEMINI_API_KEY") ?: System.getenv("GEMINI_API_KEY") ?: ""}\"")
            // Google OAuth client secret — never committed; supplied via ~/.gradle/gradle.properties or env
            buildConfigField("String", "GOOGLE_CLIENT_SECRET", "\"${project.findProperty("GOOGLE_CLIENT_SECRET") ?: System.getenv("GOOGLE_CLIENT_SECRET") ?: ""}\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // Keep BuildConfig enabled for feature flags
        buildConfig = true
    }

    lint {
        // Localization is intentionally partial: strings missing from a locale fall back
        // to the base (English) resources at runtime. This turns the check off entirely
        // (not merely down to a warning) so an incomplete locale never fails the build.
        // Completing translations is tracked separately; verify locale completeness with
        // a direct grep across values*/ instead of relying on lint for it.
        disable += "MissingTranslation"

        // CredManMissingDal fires because CredentialAuthenticator offers password sign-in
        // through Credential Manager (GetPasswordOption / CreatePasswordRequest) without an
        // `asset_statements` <meta-data> in the manifest. That meta-data exists to associate
        // the app with a *website* via Digital Asset Links, so a password saved on one is
        // offered on the other. CoPlanly owns no domain — the same reason the pairing deep
        // link in AndroidManifest.xml is a custom scheme rather than an App Link — so there
        // is no assetlinks.json to point at, and inventing one would fail verification
        // rather than satisfy anything. Password sign-in itself works without it: the
        // credential is then scoped to this package and signing certificate alone.
        // Re-enable this check together with the App Links work, when a domain exists
        // (docs/BACKLOG.md, CQ-16).
        disable += "CredManMissingDal"
    }

    // Compose compiler is applied via the org.jetbrains.kotlin.plugin.compose plugin
    // (its version follows the Kotlin version; composeOptions is no longer needed).

    // Test optimizations
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
        // Disable animations for faster UI tests
        animationsDisabled = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,DEPENDENCIES}"
        }
    }

    // Exported Room schemas (see the "room.schemaLocation" kapt arg below) are the fixtures
    // MigrationTestHelper reads to create a database at a past version and to validate the
    // rebuilt schema after a migration — without this, CoPlanlyDatabaseMigrationTest cannot
    // find 11.json/12.json at instrumentation runtime.
    sourceSets {
        getByName("androidTest") {
            assets.srcDir("$projectDir/schemas")
        }
    }
}

dependencies {
    // Core Android - Updated to latest stable versions
    implementation("androidx.core:core-ktx:1.13.1")
    // AppCompat is required for per-app language preferences
    // (AppCompatDelegate.setApplicationLocales with autoStoreLocales persistence)
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Compose - BOM 2025.10 (Compose 1.9.x, Material 3 1.4.x / M3 Expressive)
    val composeBom = platform("androidx.compose:compose-bom:2025.10.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation - 2.9.x (predictive-back aware transitions)
    implementation("androidx.navigation:navigation-compose:2.9.3")

    // Splash Screen API for Android 12+
    implementation("androidx.core:core-splashscreen:1.0.1")

    // ViewModel - Updated to match lifecycle version
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Hilt - Updated to latest stable
    implementation("com.google.dagger:hilt-android:2.56.2")
    kapt("com.google.dagger:hilt-compiler:2.56.2")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    kapt("androidx.hilt:hilt-compiler:1.2.0")

    // Room - 2.7.x is required for Kotlin 2.x metadata (2.6.x kapt fails on it)
    val roomVersion = "2.7.2"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")
    // MigrationTestHelper — instrumented tests that run a real migration against a real
    // SQLite database and validate the result against the exported schema JSON.
    androidTestImplementation("androidx.room:room-testing:$roomVersion")

    // Coroutines - Updated to latest stable
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // WorkManager for background tasks
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Calendar - Check for updates at https://github.com/kizitonwose/Calendar
    implementation("com.kizitonwose.calendar:compose:2.6.1")

    // Google Sign-In - Migrating to Credential Manager API
    // Old deprecated API (will be removed)
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    // New Credential Manager API
    implementation("androidx.credentials:credentials:1.2.2")
    implementation("androidx.credentials:credentials-play-services-auth:1.2.2")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // Google Calendar API
    implementation("com.google.api-client:google-api-client-android:2.2.0")
    implementation("com.google.apis:google-api-services-calendar:v3-rev20220715-2.0.0")

    // Encrypted SharedPreferences - Updated to stable
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Gson for JSON - Updated to latest
    implementation("com.google.code.gson:gson:2.11.0")

    // Lottie for Compose - Animations library
    implementation("com.airbnb.android:lottie-compose:6.5.2")

    // Jetpack Glance for Widgets
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")

    // ZXing for QR code generation
    implementation("com.google.zxing:core:3.5.3")

    // ML Kit for QR code scanning
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // CameraX — live preview for the pairing QR scanner. ML Kit only analyses
    // frames; something has to produce them.
    val cameraxVersion = "1.4.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // ML Kit text recognition for receipts — bundled Latin model, so OCR works offline
    // and from first launch (~4 MB) instead of waiting on a Play Services download.
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Generative AI - Gemini API
    implementation("com.google.ai.client.generativeai:generativeai:0.9.0")

    // Retrofit for AI API calls
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")

    // OkHttp for HTTP client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Firebase - Updated to latest BOM
    val firebaseBom = platform("com.google.firebase:firebase-bom:33.7.0")
    implementation(firebaseBom)
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.firebase:firebase-messaging-ktx")
    implementation("com.google.firebase:firebase-analytics-ktx")
    implementation("com.google.firebase:firebase-crashlytics-ktx")
    implementation("com.google.firebase:firebase-config-ktx")
    implementation("com.google.firebase:firebase-storage-ktx")

    // Callable Cloud Functions — pairing accept/unpair write both parents'
    // user documents, which is only safe server-side.
    implementation("com.google.firebase:firebase-functions-ktx")

    // Coil for async image loading in Compose (receipt photos)
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Testing - Updated to latest stable
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.10.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // MockK for mocking - Latest stable
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("io.mockk:mockk-android:1.13.13")
    androidTestImplementation("io.mockk:mockk-android:1.13.13")

    // Coroutines Test - Latest stable
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    // Turbine for Flow testing
    testImplementation("app.cash.turbine:turbine:1.2.0")

    // ArchCore Testing for LiveData and ViewModel
    testImplementation("androidx.arch.core:core-testing:2.2.0")

    // Hilt testing - Updated to match Hilt version
    testImplementation("com.google.dagger:hilt-android-testing:2.56.2")
    kaptTest("com.google.dagger:hilt-compiler:2.56.2")
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.56.2")
    kaptAndroidTest("com.google.dagger:hilt-compiler:2.56.2")

    // Navigation Testing
    androidTestImplementation("androidx.navigation:navigation-testing:2.9.3")
}

// androidx.room:room-testing-android pulls in JUnit 5 (junit-jupiter/junit-platform)
// transitively for its own Kotlin-multiplatform test fixtures, which this project never uses
// (everything here is JUnit 4). Left in, six of those jars all ship META-INF/LICENSE.md and
// fail androidTest resource merging. An exclude on the room-testing dependency declaration
// itself does not reach this: room-testing is a Kotlin Multiplatform umbrella artifact that
// Gradle resolves to room-testing-android via variant metadata ("available-at"), and per-
// dependency exclude rules do not propagate across that redirect. A configuration-level
// exclude does, since it is enforced against every resolved module regardless of how it
// entered the graph.
configurations.named("androidTestImplementation") {
    exclude(group = "org.junit.jupiter")
    exclude(group = "org.junit.platform")
}

kapt {
    correctErrorTypes = true
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

// Detekt configuration for static code analysis
detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom("$projectDir/config/detekt/detekt.yml")
    baseline = file("$projectDir/config/detekt/baseline.xml")
}

dependencies {
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.7")
}


// A failing unit test on CI printed only its exception class and a line number — enough to
// know something broke, not enough to know what. `FULL` prints the assertion message and the
// stack, so a red build can be diagnosed from its log instead of by downloading the XML report
// and guessing in the meantime. Only failures are logged; a green run stays quiet.
tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
        showCauses = true
    }
}
