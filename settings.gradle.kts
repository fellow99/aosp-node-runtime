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
// probe-app：101-probe-app 探针载体（引擎载荷/加载/版本契约/入口符号），挂根工程直出
// `gradle :probe-app:assembleDebug`；依赖本地 :node-runtime（implementation(project(...))）。
include(":probe-app")
