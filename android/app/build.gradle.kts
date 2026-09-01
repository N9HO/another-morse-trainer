import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Load release signing credentials from keystore.properties (gitignored) if present.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "app.anothermorsetrainer"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.anothermorsetrainer"
        minSdk = 24
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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }

    // AGP ships lint for free and nothing was running it. Path exclusions live
    // in lint.xml next to this file — the vendored CW decoder is kept
    // byte-identical to a firmware copy, so lint must not have opinions about it.
    lint {
        lintConfig = file("lint.xml")
        // Fail the build on lint *errors*; warnings are reported, not fatal.
        // Raising warnings to errors is a decision to make after reading a run,
        // not before.
        abortOnError = true
        warningsAsErrors = false
        // Printed in the CI log, and uploaded as a browsable report.
        textReport = true
        htmlReport = true
        xmlReport = true
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
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
}
