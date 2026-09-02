import java.util.Properties

plugins {
    id("com.android.application")
    // No org.jetbrains.kotlin.android: AGP 9 compiles Kotlin itself (see the
    // root build.gradle.kts). Only the Compose compiler plugin is applied.
    id("org.jetbrains.kotlin.plugin.compose")
}

// Load release signing credentials from keystore.properties (gitignored) if present.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "app.anothermorsetrainer"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.anothermorsetrainer"
        minSdk = 24
        // Left at 36 on purpose: the toolchain upgrade that took compileSdk to
        // 37 does not need targetSdk to follow, and raising it changes the
        // app's runtime behaviour on Android 17 devices, which nothing here can
        // test (the smoke test emulator runs API 34). Move it as its own change.
        targetSdk = 36
        versionCode = 17
        versionName = "1.12.2"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 was off, so release builds shipped every generated icon in
            // material-icons-extended (the app references about 30 of them)
            // along with full symbol names and unreachable code. Tree-shaking
            // this is the largest APK-size lever available here.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true   // java.time on minSdk 24
    }
    // No kotlinOptions { jvmTarget } block: AGP 9 removed it, and under built-in
    // Kotlin jvmTarget defaults to compileOptions.targetCompatibility (17).
    buildFeatures {
        compose = true
    }

    // AGP ships lint for free and nothing was running it. Path exclusions live
    // in lint.xml next to this file — the vendored CW decoder is kept
    // byte-identical to a firmware copy, so lint must not have opinions about it.
    // The shared timing fixture lives at the repo root, above both app trees,
    // because it is consumed by the iOS harness too (fixtures/timing.json). Put
    // it on the unit-test classpath so MorseTimingTest can read it by name
    // rather than walking relative paths out of the module.
    sourceSets {
        getByName("test") {
            // `directories` is AGP 9's replacement for the deprecated srcDir();
            // it takes path strings, evaluated as project.file().
            resources.directories += rootProject.file("../fixtures").path
        }
    }

    lint {
        lintConfig = file("lint.xml")
        // Fail the build on lint *errors*; warnings are reported, not fatal.
        // Raising warnings to errors is a decision to make after reading a run,
        // not before.
        abortOnError = true
        warningsAsErrors = false
        // Printed in the CI log. The HTML and XML reports the workflow uploads
        // are always written under AGP 9 (textReport/htmlReport/xmlReport are
        // deprecated no-ops now); printTextReport is what still chooses stdout
        // over the abbreviated console summary.
        printTextReport = true
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    // 2026.08.00 pulls Compose 1.12.0, which requires AGP 9.2+ and compileSdk
    // 37 — both satisfied above. (It was held at 2026.06.01 / Compose 1.11.4
    // until the AGP 9 upgrade landed.)
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // WebSocket transport for the Vail repeater client (and the Short
    // Stories news-feed fetch).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // JVM unit tests: the ported CW decoder core is held to the firmware
    // bench's synthetic-audio checks.
    testImplementation("junit:junit:4.13.2")
    // A real org.json for unit tests. The org.json in android.jar is a stub that
    // throws "Stub!" on every call, so parsing the shared fixture on the JVM
    // needs the actual implementation ahead of it on the classpath.
    testImplementation("org.json:json:20260814")
}
