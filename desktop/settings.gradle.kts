// The desktop app's own Gradle root, independent of android/ (see
// docs/desktop-design.md). Nothing here includes, depends on or reads code from
// the other two trees; the only thing shared is fixtures/, as test data.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}
rootProject.name = "AnotherMorseTrainerDesktop"
include(":app")
