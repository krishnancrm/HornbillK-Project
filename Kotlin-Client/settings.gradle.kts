// settings.gradle.kts
// This file defines settings for the entire Gradle build, including plugins and dependency resolution.

pluginManagement {
    repositories {
        // Essential for finding Gradle plugins (including Kotlin plugins)
        gradlePluginPortal()
        // Essential for finding AndroidX and Google Play Services plugins/artifacts
        google()
        // Essential for finding other common libraries
        mavenCentral()
        // Add any other custom plugin repositories here if needed
    }
}

dependencyResolutionManagement {
    // This enforces that all dependency repositories must be declared here.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Essential for finding AndroidX and Google Play Services dependencies
        google()
        // Essential for finding other common library dependencies
        mavenCentral()
        // Add any other custom dependency repositories here if needed
        // For example: maven { url "https://jitpack.io" }
    }
}

// Declares the root project's name
rootProject.name = "Hornbill.K"

// Includes sub-modules in the project
include(":app")
