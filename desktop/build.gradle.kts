plugins {
    // Pinned together. Compose Multiplatform 1.9.3 is built against Kotlin
    // 2.2.x, and the Compose compiler plugin's version must equal the Kotlin
    // compiler's. Move all three in one edit, reading the CMP release notes'
    // compatibility table first. Gradle is pinned to 8.14.3 in the wrapper,
    // the newest line these Kotlin and CMP releases were tested against.
    kotlin("jvm") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    id("org.jetbrains.compose") version "1.9.3" apply false
}
