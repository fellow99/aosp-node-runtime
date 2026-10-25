plugins {
    alias(libs.plugins.android.library)
    id("com.vanniktech.maven.publish.base")
}

// node-runtime：只装引擎（libnode.so + libc++_shared.so + 版本常量）。
// 版本号 = Node 版本号（由 runtime.lock.json 锁定）。
// 二进制不入库：src/main/jniLibs/ 由构建脚本/CI 投放。

apply(from = rootProject.file("publish.gradle.kts"))

// 不能用 NODE_* 前缀：Gradle 9.4 把所有 NODE_ 前缀属性保留给内置 Node 工具链，-P 覆盖会被忽略。
// 多版本发布：gradle publishToMavenCentral -PRUNTIME_VERSION=22.23.4 -PRELEASE=true
version = (providers.gradleProperty("RUNTIME_VERSION").orNull
    ?: error("node-runtime 需要 RUNTIME_VERSION（= Node 版本）；当前未设置"))
afterEvaluate {
    val publishing = extensions.getByType(org.gradle.api.publish.PublishingExtension::class.java)
    publishing.publications.forEach { pub ->
        (pub as Any).javaClass.methods
            .first { it.name == "setVersion" && it.parameterCount == 1 }
            .invoke(pub, version.toString())
    }
}

android {
    namespace = "org.fellow99.noderuntime"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}
