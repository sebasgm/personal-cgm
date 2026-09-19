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

// Lets Gradle fetch the JDK 17 toolchain itself, so the build does not depend on
// whatever JDK happens to be on PATH (Android Studio ships its own).
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "personal-cgm"

// :core and :data-llu are deliberately plain Kotlin/JVM modules, not Android
// libraries. The Wear OS app added in stage 3 consumes them unchanged, and the
// stage 6 "watch polls on its own" fallback needs the LLU client to run there too.
include(":core")
include(":data-llu")
include(":app")
