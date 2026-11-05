# 101-probe-app — 引擎载荷探针载体（probe-app）技术方案（plan.md）

> **性质**：**实现特定**。本文档记录 `probe-app/` 的真实实现结构、关键决策与构建命令，
> 对照规格 [spec.md](./spec.md) 的 `FR-101-PROBE-001…008`。
> **状态**：🟡 本批次落地（spec → 实现 → host 构建验证）；设备执行 ⬜（编排者统一排队）。
> **结构决策**：probe-app 挂在根 Gradle 工程下（`settings.gradle.kts` `include(":probe-app")`），
> 扁平 Android application 模块（`probe-app/src/main/...`），`gradle :probe-app:assembleDebug` 直出——
> 与 aosp-node-bridge（同样以 dlsym 链嵌入 libnode）的 probe-app 结构一致；
> 依赖本地模块 `implementation(project(":node-runtime"))`，而非 sibling aosp-node-cli 的独立工程 + AAR。

---

## 1. 技术上下文

| 项 | 取值 | 说明 |
|---|---|---|
| 载体模块 | `:probe-app`（applicationId `org.fellow99.nodeprobe`） | `probe-app/build.gradle.kts`；根 settings `include(":probe-app")` |
| ABI | 仅 `arm64-v8a`（`abiFilters`） | 与 `:node-runtime` 一致（唯一 ABI，NFR） |
| 引擎依赖 | `implementation(project(":node-runtime"))` | 把 `jniLibs/arm64-v8a/{libnode.so,libc++_shared.so}` + `META-INF/node-runtime.properties` 投影进 APK；继承 minSdk 24 / compileSdk 36 |
| 期望值 | node 26.13.0 / sha256 `bc5d15f8…`（镜像 `runtime.lock.json` active） | `MainActivity.java` 顶部常量，注释注锁值出处（C2） |
| 垫片 | `libprobe_shim.so`（NDK r28 命令行编译，不入库） | `probe_shim.c`（`src/main/cpp/`）+ 编译命令在 [docs/probe-shim-build.md](../probe-app/docs/probe-shim-build.md) |
| 构建 | Gradle 9.4.1（发行版 `.dist/gradle-dist/gradle-9.4.1/bin/gradle`，aosp-node-cli 侧）+ AGP 9.2.1 | 根 `gradle/libs.versions.toml` agp=9.2.1 |

## 2. 宪法合规

| 条目 | 落点 |
|---|---|
| C2 唯一事实源 | 期望版本/SHA 全部镜像 `runtime.lock.json`（常量带出处注释）；判据不内嵌可枚举版本串副本之外的事实 |
| C5 同版本配对 | 引擎 26.13.0（已按 lock 重投放，`provision.mjs --check` 全过）；探针 `versionName=26.13.0` |
| C6 未实测标 ⬜/不硬造 | FR-101-PROBE-005 进程内执行本批次不实现（BLOCKED + ⬜）；设备跑批 ⬜ |
| C7 未实测不得当已测 | PRB-01…05 均为可证伪判据；PRB-05 明确 BLOCKED 不冒充 PASS；加载失败如实记录异常/dlerror |
| C8 先改文档再改实现 | spec（FR-101-PROBE-001…008）→ plan → 实现 → host 构建验证（本批次顺序即如此） |

## 3. 实现结构（真实文件）

| 文件 | 职责 |
|---|---|
| `probe-app/settings.gradle.kts` | 独立入口镜像（`cd probe-app && gradle assembleDebug` 可选；根构建内不生效，与 aosp-node-bridge 同款注释） |
| `probe-app/build.gradle.kts` | application 模块：agp 插件、namespace/applicationId、minSdk 24/targetSdk 35/compileSdk 36、abiFilters arm64-v8a、`useLegacyPackaging=true`（保证 nativeLibraryDir 物理落盘，PRB-01 可行）、assets source dir 加 `node-runtime/src/main/resources`（PRB-03 assets 兜底） |
| `probe-app/gradle.properties` | `org.gradle.jvmargs=-Xmx2g` · `android.useAndroidX=false`（纯 Java + org.json，无需 AndroidX） · `PROBE_APP_VERSION=26.13.0` |
| `probe-app/.gitignore` | `build/`、`local.properties`、`src/main/jniLibs/**/*.so`（垫片二进制不入库，与仓内二进制纪律一致） |
| `probe-app/src/main/AndroidManifest.xml` | 零权限；`extractNativeLibs=true`；单一 LAUNCHER activity |
| `probe-app/src/main/java/org/fellow99/nodeprobe/MainActivity.java` | UI + 探针表 + 报告 + autorun（详见 §4） |
| `probe-app/src/main/java/org/fellow99/nodeprobe/ShimProbe.java` | libprobe_shim.so 的 Java 面：`ensureLoaded()`（幂等，异常转文本）、native `dlsymNodeStart()`/`getDlError()` |
| `probe-app/src/main/cpp/probe_shim.c` | JNI 垫片：`dlopen("libnode.so"|RTLD_DEFAULT)` → `dlsym("_ZN4node5StartEiPPc")`，失败留 `dlerror` 文本（详见 §4.2） |
| `probe-app/docs/probe-shim-build.md` | 垫片 NDK 交叉编译命令 + 验证（readelf 导出符号） |
| `probe-app/README.md` | 模块说明 / 构建 / 使用 |
| `probe-app/src/main/jniLibs/arm64-v8a/libprobe_shim.so` | 垫片产物（NDK 命令行编译；不入库，本机已投） |

## 4. 关键实现点（可回溯）

### 4.1 工程接入
- 根 `settings.gradle.kts` 增加 `include(":probe-app")`；根 `gradle/libs.versions.toml`
  `[plugins]` 增加 `android-application = { id = "com.android.application", version.ref = "agp" }`。
- app 依赖 `implementation(project(":node-runtime"))`：AAR 的 jniLibs 合并进 APK native libs；
  `META-INF/node-runtime.properties` 是否作为 classpath resource 展开取决于 AGP/AAPT——本模块不赌它，
  另把 `node-runtime/src/main/resources` 声明为 assets source dir ⇒ `assets/META-INF/node-runtime.properties`
  兜底（PRB-03 双通道）。

### 4.2 JNI 垫片（小而稳，不链接 libnode）
`probe_shim.c` 只做一件事：拿到 `node::Start` 函数指针。
1. `static char g_err[]` 承载最后一次 `dlerror` 文本；
2. `dlsymNodeStart()`：先 `dlopen("libnode.so", RTLD_NOW|RTLD_GLOBAL)`（任务语义优先显式句柄；
   失败不视为终态），再 `dlsym(h?h:RTLD_DEFAULT, "_ZN4node5StartEiPPc")`；地址非 0 返回 `(jlong)`，
   否则把 `dlerror()` 写入 `g_err` 并返回 0；
3. `getDlError()` 返回 `g_err`；
4. `JNIEXPORT` 保证 JNI 符号以 default visibility 导出（System.loadLibrary 按名解析 JNI 符号依赖它）；
   无 `JNI_OnLoad`、无 Android logging、无 libnode 链接（按名 dlsym，SDK 链接期零引擎符号依赖）。

### 4.3 探针表（FR 交叉引用见 §7）
`MainActivity.probes()` 声明式数组，每条带 `timeoutMs`；runner 单线程线程池 +
`Future.get(timeout)` 兜底（FR-101-PROBE-008），计时入报告。
- **PRB-01**（FR-101-PROBE-001）：`nativeLibraryDir/libnode.so` 与 `libc++_shared.so` 存在非空 +
  `SUPPORTED_ABIS` 仅 arm64。
- **PRB-02**（FR-101-PROBE-002）：`System.loadLibrary("node")`，失败捕获 `Throwable` 记 detail。
- **PRB-03**（FR-101-PROBE-003）：读 `META-INF/node-runtime.properties`（classloader → assets 兜底）；
  断言 `node.version==26.13.0`、`sha256==bc5d15f8…`（锁值常量）；计算实际 `libnode.so` 文件 sha256
  比对 properties.sha256（可算则断言，不可算 `PASS-FACT`）。
- **PRB-04**（FR-101-PROBE-004）：`ShimProbe.ensureLoaded()` → `dlsymNodeStart()!=0`；0 则
  `getDlError()` 入 detail。
- **PRB-05**（FR-101-PROBE-005）：BLOCKED（⬜ 后续批次，进程内 `node -e` 执行不在本批次实现，不硬造）。

### 4.4 报告与无头
`filesDir/probe-report.json`：`{runAt, device, expected{node,abi}, summary{pass,fail,blocked}, probes[]}`；
UI 逐条 `log`；`--ez autorun true` 启动即跑（FR-101-PROBE-007）。

## 5. 构建与部署命令（DEV 侧）

```bash
# 0) 前置：引擎 26.13.0 投放校验（本批次已重投，见 DEV_CHECKLIST §四-1）
node tools/provision.mjs --check          # 八项门禁全过

# 1) JNI 垫片交叉编译（NDK 28.2.13676358，Git Bash；命令全文见 docs/probe-shim-build.md）
NDK="C:/Users/Administrator/AppData/Local/Android/Sdk/ndk/28.2.13676358"
CC="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android24-clang.cmd"
"$CC" -O2 -fPIC -shared \
  -I"$NDK/toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/include" \
  probe-app/src/main/cpp/probe_shim.c \
  -o probe-app/src/main/jniLibs/arm64-v8a/libprobe_shim.so

# 2) 构建 APK（根工程，Gradle 9.4.1 发行版；需先确保 sdk.dir/ANDROID_HOME）
cd /d/node-bridge-workspace/aosp-node-runtime
GRADLE="/d/node-bridge-workspace/aosp-node-cli/.dist/gradle-dist/gradle-9.4.1/bin/gradle"
"$GRADLE" :probe-app:assembleDebug --console=plain
# 产物：probe-app/build/outputs/apk/debug/probe-app-debug.apk

# 3) 入库核对（.so 三件 + META-INF 资源）
unzip -l probe-app/build/outputs/apk/debug/probe-app-debug.apk | grep -E "libnode|libc\+\+|probe_shim|node-runtime"

# 4) 设备运行（由编排者统一排队；此处仅记录命令）
adb install -r probe-app/build/outputs/apk/debug/probe-app-debug.apk
adb shell am start -n org.fellow99.nodeprobe/.MainActivity --ez autorun true   # FR-101-PROBE-007
adb shell run-as org.fellow99.nodeprobe cat files/probe-report.json > probe-report.json
```

## 6. 用例设计纪律

- 探针 id = `PRB-xx`（本模块自有），一键对一条 FR；host 用例 `PRB-HOST-xx`。
- 判据设备语义优先：不许在 x86_64 模拟器执行 arm64 产物并记为通过（禁止判据）；加载失败/符号缺失
  如实 FAIL，不伪造 PASS。
- BLOCKED 不删用例：PRB-05（进程内执行）固定 BLOCKED + ⬜，待后续批次实现（spec §3.5 判据已写明）。

## 7. FR 交叉引用

| FR | 落点 |
|---|---|
| FR-101-PROBE-001 | `probe-app/build.gradle.kts`（abiFilters/依赖）+ `MainActivity.java` PRB-01 |
| FR-101-PROBE-002 | `MainActivity.java` PRB-02（`loadLibrary("node")`，异常入 detail） |
| FR-101-PROBE-003 | `MainActivity.java` PRB-03（属性双通道 + 锁值镜像 + 文件哈希） |
| FR-101-PROBE-004 | `probe_shim.c` + `ShimProbe.java` + `MainActivity.java` PRB-04 |
| FR-101-PROBE-005 | ⬜ 后续批次（PRB-05 = BLOCKED；判据速记 spec §3.5） |
| FR-101-PROBE-006 | `MainActivity.java` 报告 JSON（`filesDir/probe-report.json`） |
| FR-101-PROBE-007 | `MainActivity.java` onCreate `--ez autorun true` |
| FR-101-PROBE-008 | `MainActivity.java` runner（Future.get(timeout)） |

## 8. 不一致与风险登记

| # | 项 | 处置 |
|---|---|---|
| 1 | 本批次发现投放的 `libnode.so` 实为 24.22.1（sha `be1d1641…`，`provision.mjs --check` ⑥⑦ 失败） | 已按 `runtime.lock.json` + GitHub Release asset（sha `3251e7ac…` 归档，内含 26.13.0 `libnode.so`/`libc++_shared.so`）重新投放 ⇒ `--check` 八项全过；详见 DEV_CHECKLIST |
| 2 | PRB-03 文件 sha256 计算约 1–2s（87MB） | 独立 timeout 120s；不可算仅 `PASS-FACT` 记录 |
| 3 | `META-INF/node-runtime.properties` 作为 classpath resource 是否展开取决于 AGP | 双通道（classloader + assets），不赌单通道 |
| 4 | 设备执行未排（本批次仅 host 构建） | 报告/判据如实标注 ⬜；APK 由编排者统一排队安装 |

## 9. 文件清单（本批次）

- 新增：`specs/101-probe-app/{spec,plan,tasks,test-cases}.md`；`probe-app/{settings.gradle.kts,
  build.gradle.kts,gradle.properties,.gitignore,README.md,docs/probe-shim-build.md}`；
  `probe-app/src/main/{AndroidManifest.xml,cpp/probe_shim.c,
  java/org/fellow99/nodeprobe/{MainActivity,ShimProbe}.java}`；`logs/20261105-1/DEV_CHECKLIST.md`。
- 修改：根 `settings.gradle.kts`（+`include(":probe-app")`）；根 `gradle/libs.versions.toml`
  （+`android-application` 插件别名）。
- 产物（不入库）：`probe-app/src/main/jniLibs/arm64-v8a/libprobe_shim.so`；`probe-app/build/`。
