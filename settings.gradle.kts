pluginManagement {
    plugins {
        id("com.vanniktech.maven.publish.base") version "0.37.0"
    }
    repositories {
        google()
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

rootProject.name = "aosp-node-runtime"
include(":node-runtime")
