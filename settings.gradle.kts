// <project-root>/settings.gradle.kts
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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "CVDoor"
include(":app")

// ✅ 自动解析并下载对应的 JDK（推荐 JDK17）
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
