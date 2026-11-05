plugins {
    alias(libs.plugins.android.application)
}

// ─────────────────────────────────────────────────────────────────────────────
// probe-app —— 引擎载荷探针载体（specs/101-probe-app）
//
// · 挂在根 Gradle 工程下（根 settings.gradle.kts include(":probe-app")），
//   `gradle :probe-app:assembleDebug` 直出（Gradle 9.4.1 + AGP 9.2.1）。
// · 引擎载荷经本地模块投影：implementation(project(":node-runtime")) ⇒ APK 带
//   lib/arm64-v8a/{libnode.so,libc++_shared.so} + META-INF/node-runtime.properties。
// · 仅 arm64-v8a（与 :node-runtime 一致，本仓唯一 ABI）。
// · useLegacyPackaging=true：nativeLibraryDir 物理落盘，PRB-01 文件存在性判据可行。
// · assets source dir 加 node-runtime/src/main/resources ⇒ assets/META-INF/
//   node-runtime.properties（PRB-03 的 assets 兜底通道，见 spec FR-101-PROBE-003）。
// ─────────────────────────────────────────────────────────────────────────────

android {
    namespace = "org.fellow99.nodeprobe"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.fellow99.nodeprobe"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        // 探针 app 不发布；版本 = 配对 Node 版本（C5）
        versionName = "26.13.0"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDir("$rootDir/node-runtime/src/main/resources")
        }
    }

    buildTypes {
        getByName("debug") {
            isDebuggable = true
        }
    }
}

dependencies {
    // 本仓 node-runtime 模块：投影 libnode.so/libc++_shared.so/META-INF，继承 minSdk 24/compileSdk 36/ABI
    implementation(project(":node-runtime"))
}
