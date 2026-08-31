plugins {
    // AGP 8.10 is the first release that fully supports compileSdk 36 (the
    // Play target-API requirement from Aug 31, 2026); pairs with Gradle 8.11.1.
    id("com.android.application") version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
