import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

fun localOrEnv(key: String, env: String): String {
    System.getenv(env)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val f = rootProject.file("local.properties")
    if (!f.exists()) return ""
    return Properties().apply { f.inputStream().use { load(it) } }
        .getProperty(key)?.trim().orEmpty()
}

/*
 * OAuth client ids. Both optional at build time and both enterable in the app by QR
 * (Settings -> Accounts -> Client IDs), which is what makes a plain release APK usable
 * by anyone: an installed-app client has no secret to leak, and the redirect scheme is
 * fixed by the package name rather than by the id.
 */
val googleClientId = localOrEnv("googleClientId", "GOOGLE_CLIENT_ID")
val microsoftClientId = localOrEnv("microsoftClientId", "MICROSOFT_CLIENT_ID")

/** Shake-to-report key. Never committed; CI passes it from a repository secret. */
val reportToken = localOrEnv("reportToken", "REPORT_TOKEN")

/*
 * The OAuth redirect. Google validates an Android client by package name and signing
 * certificate rather than by redirect URI and accepts a custom scheme matching the
 * package; Microsoft accepts the same shape for a public client. So it is a constant and
 * the manifest can declare it without knowing which id is in use.
 */
val redirectScheme = "com.gios.brightmailbox"

/**
 * Notification sounds are GENERATED, not committed.
 *
 * scripts/*.py synthesize every chime from arithmetic at build time, so the repository
 * contains no audio at all — about 15 KB of Python instead of a folder of WAVs, and
 * nothing anyone else owns. See scripts/README.md.
 */
val genSounds by tasks.registering(Exec::class) {
    val out = layout.buildDirectory.dir("generated/res/sounds/raw")
    outputs.dir(out)
    inputs.files(fileTree(rootProject.file("scripts")) { include("*.py", "*.json") })
    doFirst { out.get().asFile.mkdirs() }
    workingDir = rootProject.file("scripts")
    commandLine("python3", "build_sounds.py", out.get().asFile.absolutePath)
    // A machine with no python3 still builds; the app falls back to the system sound.
    isIgnoreExitValue = true
}

android {
    namespace = "com.gios.brightmailbox"
    compileSdk = 35
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

    sourceSets["main"].res.srcDir(layout.buildDirectory.dir("generated/res/sounds"))

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
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

tasks.withType<com.android.build.gradle.tasks.MergeResources>().configureEach {
    dependsOn(genSounds)
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
