import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

/*
 * NOTE: no top-level `fun` in this file, deliberately.
 *
 * A build script that declares top-level functions is compiled into a different shape by
 * the Kotlin DSL, and AGP then fails at afterEvaluate with "compileSdkVersion is not
 * specified" even though the android block plainly sets it — the block never ran. Cost
 * three CI rounds to find, because the error names the one thing that is not wrong.
 *
 * The `val x: String = run { }` idiom below does the same job and keeps the script a
 * plain sequence of statements.
 */

/*
 * OAuth client ids. Both optional at build time and both enterable in the app by QR
 * (Settings -> Accounts -> Client IDs), which is what makes a plain release APK usable
 * by anyone: an installed-app client has no secret to leak, and the redirect scheme is
 * fixed by the package name rather than by the id.
 */
val googleClientId: String = run {
    System.getenv("GOOGLE_CLIENT_ID")?.trim()?.takeIf { it.isNotEmpty() }?.let { return@run it }
    val f = rootProject.file("local.properties")
    if (!f.exists()) return@run ""
    Properties().apply { f.inputStream().use { load(it) } }
        .getProperty("googleClientId")?.trim().orEmpty()
}
val microsoftClientId: String = run {
    System.getenv("MICROSOFT_CLIENT_ID")?.trim()?.takeIf { it.isNotEmpty() }?.let { return@run it }
    val f = rootProject.file("local.properties")
    if (!f.exists()) return@run ""
    Properties().apply { f.inputStream().use { load(it) } }
        .getProperty("microsoftClientId")?.trim().orEmpty()
}

/*
 * Notification sounds are GENERATED, never committed.
 *
 * scripts/build_sounds.py writes them into app/src/main/res/raw before Gradle runs —
 * see .github/workflows/*.yml and scripts/README.md. That directory is gitignored, so
 * the repository holds no audio at all.
 *
 * Deliberately a build step rather than a Gradle task: Notifier looks its resources up
 * by name, so a checkout with no python3 still compiles and simply falls back to the
 * system sound at runtime.
 */

/** Shake-to-report key. Never committed; CI passes it from a repository secret. */
val reportToken: String = run {
    System.getenv("REPORT_TOKEN")?.trim()?.takeIf { it.isNotEmpty() }?.let { return@run it }
    val f = rootProject.file("local.properties")
    if (!f.exists()) return@run ""
    Properties().apply { f.inputStream().use { load(it) } }
        .getProperty("reportToken")?.trim().orEmpty()
}

/*
 * The OAuth redirect. Google validates an Android client by package name and signing
 * certificate rather than by redirect URI and accepts a custom scheme matching the
 * package; Microsoft accepts the same shape for a public client. So it is a constant and
 * the manifest can declare it without knowing which id is in use.
 */
val redirectScheme = "com.gios.brightmailbox"

logger.lifecycle("BMX-DIAG: script reached the android block")

android {
    logger.lifecycle("BMX-DIAG: inside android {}")
    namespace = "com.gios.brightmailbox"
    compileSdk = 35
    logger.lifecycle("BMX-DIAG: compileSdk is now " + compileSdk)
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.gios.brightmailbox"
        minSdk = 29
        targetSdk = 35
        // CI overwrites both from the run number; see .github/workflows/build.yml
        versionCode = 1
        versionName = "1.0.0"

        // The LPIII is arm64 only. Four ABIs tripled an earlier APK for nothing.
        ndk { abiFilters += "arm64-v8a" }

        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"$googleClientId\"")
        buildConfigField("String", "MICROSOFT_CLIENT_ID", "\"$microsoftClientId\"")
        buildConfigField("String", "OAUTH_REDIRECT", "\"$redirectScheme:/oauth2redirect\"")
        buildConfigField("String", "REPORT_TOKEN", "\"$reportToken\"")
        buildConfigField("String", "REPORT_REPO", "\"gi-os/light-reports\"")
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("../keystore/brightmailbox.jks")
            storePassword = "brightmailbox"
            keyAlias = "brightmailbox"
            keyPassword = "brightmailbox"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Same committed key as debug, so either APK upgrades over the other and the
            // SHA-1 registered on the OAuth clients keeps matching both.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Room — message metadata and the learned model's training rows. Bodies go to
    // files, not rows; a mailbox of newsletters would otherwise bloat the database.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Background sync. WorkManager sits on JobScheduler, so it needs no Play Services —
    // which is the whole reason it can run on LightOS.
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Gmail REST and Microsoft Graph over plain HTTP. The official client libraries drag
    // in half of GAX and MSAL respectively, and MSAL wants a browser abstraction that
    // does not work on this device anyway.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // QR scanning, so a client id never has to be typed on a 3.9" keyboard.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // HTML: rewrite for the panel, and extract text when a message has no plain part.
    implementation("org.jsoup:jsoup:1.18.3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // The sort/ and text/ packages have no Android imports, so they test on the JVM.
    testImplementation("junit:junit:4.13.2")
}

logger.lifecycle("BMX-DIAG: end of script, android.compileSdk = " + android.compileSdk)
