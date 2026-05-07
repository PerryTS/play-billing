// One-module Gradle project that builds the PlayBillingBridge AAR.
// The output (`build/outputs/aar/play-billing-bridge-release.aar`) is
// renamed to `play-billing-bridge.aar` and committed to the repo so
// app developers can `implementation(files(...))` it directly without
// having to run Gradle themselves.

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "play-billing-bridge"
