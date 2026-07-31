// project_root/build.gradle.kts

plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.kotlinSerialization) apply false
}

// No 'allprojects { repositories { ... } }' or 'subprojects { repositories { ... } }' here!
// These are now handled by dependencyResolutionManagement in settings.gradle.kts