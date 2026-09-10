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

        // The SIROS SDK itself is on Maven Central as of 0.14.0, which
        // mavenCentral() above already covers. This block is still needed for
        // the SDK's own native dependencies, which are NOT on Central:
        //
        //   org.siros:siros-wscd-manager      org.siros:zk-cred-vega
        //   org.siros:zk-cred-longfellow      org.siros:zk-cred-bbs
        //   org.siros:siros-dc-matcher
        //
        // GitHub Packages serves every package in the org through any
        // repository-scoped URL the token can read, so one block covers all
        // five. It is NOT optional - the proximity capability needs the SDK,
        // the SDK needs these - so a build without credentials fails loudly
        // here rather than silently skipping the repository and failing later
        // with five unresolved dependencies.
        //
        // Remove this and the token pair in .github/workflows/push.yml when
        // those five reach Central. Publishing the SDK there was necessary but
        // not sufficient.
        maven {
            name = "SirosNativeCrates"
            url = uri("https://maven.pkg.github.com/sirosfoundation/siros-sdk-kotlin")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                    ?: providers.gradleProperty("gpr.user").orNull
                    ?: error(
                        "The SDK's native dependencies are on GitHub Packages, which requires a token. " +
                            "Set GITHUB_ACTOR/GITHUB_TOKEN, or gpr.user/gpr.key in ~/.gradle/gradle.properties " +
                            "(a personal access token with read:packages).",
                    )
                password = System.getenv("GITHUB_TOKEN")
                    ?: providers.gradleProperty("gpr.key").orNull
                    ?: error("SIROS native crates: gpr.key (or GITHUB_TOKEN) is not set.")
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
