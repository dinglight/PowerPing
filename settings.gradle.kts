pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")   // 新增
        maven("https://maven.aliyun.com/repository/public")   // 新增
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
        maven("https://maven.aliyun.com/repository/google")   // 新增
        maven("https://maven.aliyun.com/repository/public")   // 新增
        google()
        mavenCentral()
    }
}

rootProject.name = "PowerPing"
include(":app")
 
