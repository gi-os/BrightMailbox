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
 * The Microsoft OAuth client id — the only one left.
 *
 * Gmail signs in with an app password in v2, so there is no Google client here and no
 * Cloud project for the user to build. Microsoft imposes no user cap on a multi-tenant
 * public client, so ONE registration shipped in the APK serves everybody: a public
 * client has no secret to leak, and the redirect scheme is fixed by the package name
 * rather than by the id. Overridable at runtime by QR for anyone running their own.
 */
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
 * see the workflows under .github and scripts/README.md. That directory is gitignored, so
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
        versionName = "2.26.0"

        // The LPIII is arm64 only. Four ABIs tripled an earlier APK for nothing.
        ndk { abiFilters += "arm64-v8a" }

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

    /*
     * Angus Mail ships its license and notice files at the same archive paths that other
     * dependencies use, and the merger treats a collision as an error. Picking the first
     * is what the Angus Android page prescribes.
     *
     * module-info.class is a JPMS descriptor D8 has no use for; excluding it keeps the
     * dexer quiet rather than relying on it to skip the file.
     */
    packaging {
        resources {
            pickFirsts += "META-INF/LICENSE.md"
            pickFirsts += "META-INF/NOTICE.md"
            excludes += "module-info.class"
            excludes += "META-INF/versions/**/module-info.class"
        }
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

    // Microsoft's OAuth token exchange over plain HTTP. MSAL wants a browser
    // abstraction that does not work on this device, for four form posts.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    /*
     * IMAP, SMTP and — the part that matters — MIME.
     *
     * Angus Mail is Jakarta Mail's continuation at Eclipse, and it publishes a supported
     * Android build (min API 19). The comment this replaces claimed JavaMail could not
     * run here, which was true in 2017 and is not now. Its one documented Android gap is
     * SASL, which used to be how OAuth2 was done; XOAUTH2 is a built-in mechanism today,
     * so the gap does not bite.
     *
     * `org.eclipse.angus:jakarta.mail` is the single fat artifact carrying both the
     * jakarta.mail API and the org.eclipse.angus.mail providers — 727 KB. Do not also
     * add jakarta.mail-api or angus-mail: two copies of the API on the classpath is a
     * duplicate-class build failure.
     *
     * IMAP is simple enough to hand-roll. MIME is not, and MIME is what breaks a mail
     * client on one message in twenty.
     */
    implementation("org.eclipse.angus:jakarta.mail:2.0.5")
    implementation("org.eclipse.angus:angus-activation:2.0.3")
    implementation("jakarta.activation:jakarta.activation-api:2.1.3")

    /*
     * Shake-to-report. The BuildConfig.REPORT_TOKEN field and the CI plumbing existed
     * from v1, but nothing consumed them — the field was set and no reporter read it, so
     * a shake did nothing. This is the missing half.
     */
    implementation("com.gios:light-common:1.10.0")

    /*
     * QR scanning, so a sixteen-character app password is never typed on a 3.9" keyboard.
     *
     * CameraX plus ZXing's core, rather than `com.journeyapps:zxing-android-embedded`. The
     * embedded library works, but it brings its own activity and its own layout: a scanner that
     * looks like a different app, appearing in the middle of signing in to this one. The decoder
     * is `scan/QrAnalyzer.kt`, lifted from Roll; the viewfinder is `ui/Scan.kt` and is ours.
     *
     * ZXing rather than ML Kit is not a preference — ML Kit's model is downloaded through Play
     * Services, and LightOS has no GMS, so it would bind and never return a result.
     */
    implementation("androidx.camera:camera-core:1.5.3")
    implementation("androidx.camera:camera-camera2:1.5.3")
    implementation("androidx.camera:camera-lifecycle:1.5.3")
    implementation("androidx.camera:camera-view:1.5.3")
    implementation("com.google.zxing:core:3.5.3")

    // HTML: rewrite for the panel, and extract text when a message has no plain part.
    implementation("org.jsoup:jsoup:1.18.3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // The sort/ and text/ packages have no Android imports, so they test on the JVM.
    testImplementation("junit:junit:4.13.2")
}
