plugins {
    alias(libs.plugins.android.library)
    id("com.vanniktech.maven.publish.base")
}

// node-runtime：只装引擎（libnode.so + libc++_shared.so + 版本常量）。
// 版本号 = Node 版本号（由 runtime.lock.json 锁定）。
// 二进制不入库：src/main/jniLibs/ 由构建脚本/CI 投放。

apply(from = rootProject.file("publish.gradle.kts"))

version = (providers.gradleProperty("NODE_VERSION").orNull
    ?: error("node-runtime 需要 NODE_VERSION（= Node 版本）；当前未设置"))
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
