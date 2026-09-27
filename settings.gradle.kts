pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    // Only the repos listed here, and Google's repo only for Google's groups (no dependency confusion).
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.google\\.android.*")
                includeGroupByRegex("com\\.google\\.mlkit.*")
                includeGroupByRegex("com\\.google\\.ai\\.edge.*")
                includeGroupByRegex("com\\.google\\.firebase.*")
                includeGroupByRegex("com\\.google\\.testing\\.platform.*")
                includeGroupByRegex("com\\.google\\.prefab.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "watch-ai"

include(
    ":app",
    ":core:brain",
    ":core:brain-chatgpt",
    ":core:brain-ondevice",
    ":core:buddy",
    ":core:buddy-ui",
    ":core:security",
    ":core:testing",
    ":core:voice",
    ":core:watchlink",
    ":wear",
)
