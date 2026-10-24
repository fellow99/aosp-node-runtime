# aosp-node-runtime

[English](README.md) · [**中文**](README_zh.md)

**以 AAR 形式发布的 Android 版 Node.js 引擎**——一份交叉编译的 `libnode.so` 共享库（arm64-v8a），由 [aosp-node-bridge](https://github.com/fellow99/aosp-node-bridge) 在应用进程内通过 `dlopen` + `node::Start` 加载。

本仓库**不包含任何嵌入 API**——只有引擎二进制、构建所用的头文件，以及可复现的交叉编译流水线。bridge / Kotlin API 在 [aosp-node-bridge](https://github.com/fellow99/aosp-node-bridge) 仓库。

## Maven 坐标

```
io.github.fellow99:node-runtime:<node-version>
```

构件版本号**等于 Node.js 版本号**。当前版本：`24.2.0`。

## 用法

引擎与 bridge 一起引入即可。它是 `runtimeOnly` 依赖——业务代码不直接链接它：

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.fellow99:node-bridge:0.2.0")
    runtimeOnly("io.github.fellow99:node-runtime:24.2.0")
}
```

bridge 在运行期从 APK 的 native lib 目录定位 `libnode.so`；切换 Node 版本只需改这一个依赖（无需重新编译 bridge）。

## AAR 内容

```
jni/arm64-v8a/libnode.so          # Node.js 引擎（共享库，ET_DYN，无 PT_INTERP）
jni/arm64-v8a/libc++_shared.so    # C++ 运行时（本包是唯一供给方）
```

环境要求：`minSdk 24` · ABI `arm64-v8a`。

## 引擎是怎么构建出来的

二进制**不入库**，也不手工编译。[`build-node-android.yml`](.github/workflows/build-node-android.yml) 在 GitHub Actions 上运行：

1. 从 nodejs.org 下载官方 `node-v<version>.tar.xz` 并校验 SHA-256。
2. 应用 [`scripts/patch-node-source.py`](scripts/patch-node-source.py)：
   - 给 Android configure 参数加上 `--shared --with-intl=none`，
   - 为交叉编译设置 host 编译器，
   - 在 Android 上禁用 V8 trap handler。
3. 用 Android NDK r28 经 [`scripts/build-node-android.sh`](scripts/build-node-android.sh) 交叉编译（`./android-configure … && make`）。
4. 断言产物为 `ET_DYN`、无 `PT_INTERP`、导出 `node::Start`。
5. 将 `libnode.so` + 头文件发布为 GitHub Release 资产。

可手动传入 Node 版本触发构建：

```bash
gh workflow run build-node-android.yml -f node_version=24.2.0 -f android_api=24
```

## 仓库结构

```
aosp-node-runtime/
├── node-runtime/                 # Android library 模块（AAR 容器）
│   ├── include/node/             # NAPI + V8 头文件（入库）
│   └── src/main/jniLibs/         # libnode.so 投放处（git 忽略；CI/provision 投放）
├── scripts/
│   ├── build-node-android.sh     # NDK 交叉编译
│   └── patch-node-source.py      # 幂等的 Node 源码补丁
├── tools/provision.mjs           # 投放二进制 + 八项 ELF 门禁
├── runtime.lock.json            # 版本 + SHA-256 + 门禁 + 出处（唯一事实源）
└── .github/workflows/build-node-android.yml
```

## 版本锁定与校验

[`runtime.lock.json`](runtime.lock.json) 是当前 Node 版本、二进制 SHA-256、ELF 门禁与出处的唯一事实源。投放构建产物后运行：

```bash
node tools/provision.mjs --check          # 按 lock 校验已投放的 libnode.so
node tools/provision.mjs --inspect <so>   # 打印某个二进制的 ELF 门禁报告
```

## 许可证

[MIT](LICENSE)。Node.js 本体为 MIT；捆绑的第三方组件见 `node-runtime/THIRD-PARTY-NOTICES.md`。
