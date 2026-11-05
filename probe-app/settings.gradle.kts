// probe-app 独立 settings（可选入口：cd probe-app && gradle assembleDebug）
// 注意：本工程同时挂在根 settings.gradle.kts（include(":probe-app")），根构建内此文件不生效（与
// aosp-node-bridge/probe-app 同款注释）；根构建内 probe-app 依赖 :node-runtime（本地模块投影引擎
// .so 与 META-INF 资源），独立入口因缺少该模块引用不用于正式构建。
pluginManagement {
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
rootProject.name = "probe-app"
