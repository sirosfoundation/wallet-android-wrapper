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
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://www.jitpack.io") }

        // FaceTec ships its SDK as a bare .aar with no Maven repository; consume it
        // from wrapper/libs via flatDir module-style coordinates instead.
        flatDir { dirs(rootDir.resolve("wrapper/libs")) }
    }
}

rootProject.name = "wwwallet-android-wrapper"
include(":wrapper")
