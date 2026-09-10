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

        // TEMPORARY, remove at SDK 0.15.0.
        //
        // The SIROS SDK is on GitHub Packages, which is per-repository and
        // authenticated. Unlike the vendor packages below this is NOT optional -
        // the proximity capability is built on it - so a build without
        // credentials fails loudly here rather than silently skipping the
        // repository and failing later with an unresolved dependency.
        //
        // The SDK is moving to Maven Central for 0.15.0. When it lands, delete
        // this whole block: mavenCentral() above already covers it, and the
        // GITHUB_ACTOR/PACKAGES_TOKEN pair in .github/workflows/push.yml goes
        // with it. Grep for 0.15.0 to find both places.
        maven {
            name = "SirosSdk"
            url = uri("https://maven.pkg.github.com/sirosfoundation/siros-sdk-kotlin")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                    ?: providers.gradleProperty("gpr.user").orNull
                    ?: error(
                        "The SIROS SDK is published to GitHub Packages, which requires a token. " +
                            "Set GITHUB_ACTOR/GITHUB_TOKEN, or gpr.user/gpr.key in ~/.gradle/gradle.properties " +
                            "(a personal access token with read:packages).",
                    )
                password = System.getenv("GITHUB_TOKEN")
                    ?: providers.gradleProperty("gpr.key").orNull
                    ?: error("SIROS SDK credentials: gpr.key (or GITHUB_TOKEN) is not set.")
            }
        }

        if (!(System.getenv("GITHUB_ACTOR") ?: providers.gradleProperty("gpr.user").orNull).isNullOrBlank()) {
            maven {
                url = uri("https://maven.pkg.github.com/sirosfoundation/vendor-maven-packages")
                credentials {
                    username = System.getenv("GITHUB_ACTOR") ?: providers.gradleProperty("gpr.user").orNull
                    password = System.getenv("GITHUB_TOKEN") ?: providers.gradleProperty("gpr.key").orNull
                }
            }
        }
    }
}

rootProject.name = "wwwallet-android-wrapper"
include(":wrapper")
