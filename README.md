# aosp-node-runtime

Node.js 的 **Android arm64-v8a 运行时**（`libnode.so` 共享库），供 [aosp-node-bridge](https://github.com/fellow99/aosp-node-bridge) 在应用进程内 `dlopen` 加载。

## Maven 坐标

```
io.github.fellow99:node-runtime:<node-version>
```

版本号 = Node.js 版本号（如 `24.2.0`）。

## 消费

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.fellow99:node-bridge:0.2.0")
    runtimeOnly("io.github.fellow99:node-runtime:24.2.0")
}
```

## 构建

交叉编译在 GitHub Actions 完成（`build-node-android.yml`），产物上传为 GitHub Release。

手动构建（需要 Linux + Android NDK）：

```bash
./scripts/build-node-android.sh <node-source> <ndk> <output-dir> [api-level]
```

## 仓库结构

```
node-runtime/           # Android library 模块（AAR 容器）
  include/node/         # Node.js NAPI + V8 头文件（入库）
  src/main/jniLibs/     # libnode.so + libc++_shared.so（不入库，CI 投放）
  src/main/resources/   # node-runtime.properties（版本/SHA-256 元数据）
scripts/                # 交叉编译脚本
tools/provision.mjs     # 产物投放 + ELF 门禁
runtime.lock.json       # 版本 + SHA-256 + 门禁 + 出处（唯一事实源）
```

## License

MIT（见 [LICENSE](LICENSE)）
