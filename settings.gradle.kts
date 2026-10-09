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
    }
}

rootProject.name = "Revv"
include(":app")
// The CarPlay stack (a fork of DiPlay / xcertplay, GPL-3.0): the protocol, transports and media
// in :carplay:shared, the session engine and DiPlay's own screens in :carplay:common.
include(":carplay:shared")
include(":carplay:common")
