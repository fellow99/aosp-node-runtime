# 101-probe-app 测试用例（probe-app 实际实现的探针正典）

> 对应：[spec.md](./spec.md)（FR-101-PROBE-001…008）· [plan.md](./plan.md）。
> **本表 = `probe-app/src/main/java/org/fellow99/nodeprobe/MainActivity.java` `probes()` 实际实现清单**。
> **环境/状态口径**：`device` = arm64 真机/arm64 模拟器（x86_64 模拟器禁止冒充）；`host` = 构建机。
> **期望值引用**：node 26.13.0（`runtime.lock.json` active）+ sha256 `bc5d15f825a74ad089087cb4f5eaddb1c178d6152de7171270483e31651094d5`
> （lock releases#26.13.0 = `node-runtime.properties.sha256`）。
> **判据纪律**：`null`=PASS；`BLOCKED:`=BLOCKED；`PASS-` 前缀=PASS（事实记录）；其余 FAIL；每探针独立超时。

## 一、探针全表（device）

| # | 用例 | 前置 | 判据（可证伪） | FR | 状态 |
|---|---|---|---|---|---|
| PRB-01 | 载荷就位（libnode.so/libc++_shared.so 在 nativeLibraryDir 且 arm64 唯一 ABI） | APK 安装 | `libnode.so` 与 `libc++_shared.so` 存在且长度>0；`SUPPORTED_ABIS` 全为 arm64 —— 否则 FAIL（detail 列缺失项/ABI） | FR-101-PROBE-001 | ⬜ 设备跑批 |
| PRB-02 | 引擎加载（`System.loadLibrary("node")`） | PRB-01 PASS | 不抛异常 = PASS；捕获 `Throwable` 记具体异常文本 = FAIL | FR-101-PROBE-002 | ⬜ 设备跑批 |
| PRB-03 | 版本契约（node.version==26.13.0；sha256==锁值；实际文件哈希==properties.sha256） | 属性双通道 | `META-INF/node-runtime.properties` 不可得 = FAIL；`node.version` ≠ `26.13.0` = FAIL；`sha256` ≠ 锁值常量 = FAIL；实际 `libnode.so` 文件 sha256 ≠ properties.sha256 = FAIL（不可计算则 `PASS-FACT` 记录） | FR-101-PROBE-003 | ⬜ 设备跑批 |
| PRB-04 | 引擎入口符号（`dlsym("_ZN4node5StartEiPPc")` 非 0） | PRB-02 PASS | `ShimProbe.ensureLoaded()` 失败 = FAIL（带异常文本）；`dlsymNodeStart()==0` = FAIL（detail 带 `getDlError()` dlerror）；非 0 = PASS | FR-101-PROBE-004 | ⬜ 设备跑批 |
| PRB-05 | 进程内执行 `node -e "console.log(process.version)"` 回程 == `v26.13.0` | — | **BLOCKED**：需 isolate 引导 + stdout 捕获 + 进程内安全隔离，本批次不实现（FR-101-PROBE-005 ⬜ 后续批次，不硬造） | FR-101-PROBE-005 | ⛔ 设计内 |

> 判据速记（待实现批次替换 PRB-05）：`node::Start` 函数指针（垫片已取）以 `argv=["node","-e",code]` 调用，
> stdout 捕获 + 20000ms 超时，断言回程 == `v26.13.0`。

## 二、host 侧用例（构建机——载体 App 本身）

| # | 用例 | 环境 | 步骤 | 期望 | 状态 |
|---|---|---|---|---|---|
| PRB-HOST-01 | 引擎投放核对 | host | `node tools/provision.mjs --check` | 八项门禁全过（sha `bc5d15f8…`，size 87757048） | ✅ 2026-11-06（重投后） |
| PRB-HOST-02 | probe-app 编译产出 | host | `gradle :probe-app:assembleDebug --console=plain`（Gradle 9.4.1 发行版，根工程） | BUILD SUCCESSFUL；`probe-app/build/outputs/apk/debug/probe-app-debug.apk` 产出 | ✅ 2026-11-06 |
| PRB-HOST-03 | 载荷入包核对 | host | `unzip -l <apk>` | `lib/arm64-v8a/{libnode.so,libc++_shared.so,libprobe_shim.so}` 均在包；`assets/META-INF/node-runtime.properties` 在包 | ✅ 2026-11-06 |
| PRB-HOST-04 | 垫片 ELF 门禁 | host | NDK `llvm-readelf`/`llvm-nm` 检查 `libprobe_shim.so` | AArch64 ET_DYN；导出 `Java_org_fellow99_nodeprobe_ShimProbe_{dlsymNodeStart,getDlError}`；不出现 `libnode.so` DT_NEEDED | ✅ 2026-11-06 |
| PRB-HOST-05 | 探针实现与正典映射核对 | host | Grep `new Probe("PRB-…")` | id 全集 = 本表一（PRB-01…05） | ✅ 2026-11-06 |
| PRB-HOST-06 | 框架无关性/零额外权限 | host | 检查 manifest | 无 INTERNET 等权限；`useAndroidX=false` 下编译通过 | ✅ 2026-11-06 |

## 三、事实记录区（回填处，C6）

| 项 | 值 | 记录时间 |
|---|---|---|
| 投放差异事实 | 初查 `libnode.so` sha=be1d1641…（24.22.1，非 26.13.0）⇒ 已按 lock+Release asset 重投 sha=bc5d15f8…（26.13.0） | 2026-11-06 |
| PRB-01…05 设备回程 | ⬜ 待设备窗口（编排者统一排队） | — |
| PRB-03 文件哈希耗时 | 约 1–2s（87MB，计入 timeout=120s） | 估算（host 侧 sha256sum < 3s） |

## 四、禁止的判据

1. 不得在 x86_64 模拟器上执行 arm64 产物探针并记录为通过（半弹药翻译环境仅记录，正式判据需原生 arm64）。
2. 不得把 PRB-05（进程内执行）记 PASS/FAIL —— 本批次设计内 **BLOCKED**（不硬造）；实现后按 §一速记判据落地。
3. 加载失败/符号缺失只能如实 FAIL（记录具体异常/dlerror 文本），不得伪造成 PASS。
4. `libprobe_shim.so` 不得链接 libnode（符号必须按名 dlsym 解析，避免 NDK 链接期引擎符号依赖）。
5. 引擎二进制不 git add/commit（仓内二进制纪律；本地投放仅供构建）。

## 附、统一队列跑批记录（2026-11-06）

- 载体：`emulator-5554`（sdk_gphone64_x86_64 API 37 × ndk_translation；App 域 ABI=arm64）；由编排者统一排队串行执行（安装→`--ez autorun true`→`run-as cat` 回读报告）。
- 报告：`logs/20261105-1/report-node-runtime.json`（工程内留档，不入库）。
- 汇总：5 探针：PASS 3 / FAIL 1 / BLOCKED 1（PRB-01 FAIL=非 arm64 唯一 ABI 环境限制；PRB-05 进程内执行 BLOCKED；PRB-04 PASS-FACT=dlsym 命中）。
- 判读纪律：翻译模拟器结果**仅记录**，正式 G3 需原生 arm64 复测；FAIL/BLOCKED 均按本节/Spec 既有分类如实记录，未伪造 PASS。
