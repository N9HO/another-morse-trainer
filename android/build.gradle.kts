plugins {
    // AGP 9.4 is the newest stable line; it needs Gradle 9.6+ (the wrapper is on
    // 9.7.1) and supports compileSdk 37, which Compose 1.12 requires. Since 9.0
    // AGP builds Kotlin itself (android.builtInKotlin, on by default) and bundles
    // its own Kotlin Gradle plugin — 2.2.10 for this release — so
    // org.jetbrains.kotlin.android is no longer applied: applying it alongside
    // built-in Kotlin is an error ("extension already registered").
    id("com.android.application") version "9.4.0" apply false
    // The Compose compiler plugin is still applied explicitly. Its version must
    // match the Kotlin compiler that builds the app, which is the KGP bundled
    // with AGP above — read it from com.android.tools.build:gradle's POM when
    // moving AGP, and move this in the same edit.
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
