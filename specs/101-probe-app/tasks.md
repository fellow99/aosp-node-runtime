# 101-probe-app 任务拆解

> 对应：[spec.md](./spec.md)（FR-101-PROBE-001…008）· [plan.md](./plan.md)（实现特定）。
> 本仓此前无 probe-app：本批按 specs-based-devflow「先文档后实现」落地 specs 四件套 + probe-app 实现 +
> host 构建验证；设备执行 ⬜（编排者统一排队）。

## T0 规格先行（先文档，本批）

- [x] T0.1 `specs/101-probe-app/spec.md`：FR-101-PROBE-001…008（技术无关、RFC2119）+ 用户故事 +
  验收场景 + 实体表 + NFR + 依赖；FR-101-PROBE-005（进程内执行）按可选如实标 ⬜
- [x] T0.2 `specs/101-probe-app/plan.md`：实现特定、真实路径、FR 交叉引用、构建命令（Gradle 9.4.1 发行版 / NDK 垫片）
- [x] T0.3 `specs/101-probe-app/tasks.md`：本文件
- [x] T0.4 `specs/101-probe-app/test-cases.md`：PRB-xx 判据表 + host 用例 + 禁止判据
- [x] T0.5 仓内 specs/ 现无索引体系，按约束不建 SPECS_CHECKLIST/README（本批未新增其它 specs 文件）

## T1 引擎投放核对（前置，本批发现并修复）

- [x] T1.1 `node tools/provision.mjs --check`：发现已投放 `libnode.so` 实为 **24.22.1**
  （sha `be1d1641…` ≠ 锁值，⑥SHA/⑦大小 不合格）
- [x] T1.2 按 `runtime.lock.json` active 26.13.0 + GitHub Release asset（归档 sha `3251e7ac…`）
  重新投放 `libnode.so`/`libc++_shared.so` ⇒ `--check` **八项门禁全过**（sha `bc5d15f8…`）
- [x] T1.3 投放差异登记：DEV_CHECKLIST 阻塞项 1（不 git add，仅磁盘修正）

## T2 工程接入

- [x] T2.1 根 `settings.gradle.kts` `include(":probe-app")`
- [x] T2.2 根 `gradle/libs.versions.toml` 增加 `android-application` 插件别名（agp=9.2.1）
- [x] T2.3 `probe-app/build.gradle.kts`：application 模块、minSdk 24/targetSdk 35/compileSdk 36、
  `abiFilters arm64-v8a`、`useLegacyPackaging=true`、assets source dir 加 `node-runtime/src/main/resources`
- [x] T2.4 依赖：`implementation(project(":node-runtime"))`（继承 ABI/minSdk/compileSdk、投影引擎 .so 与 META-INF）

## T3 JNI 垫片（小而稳，不链接 libnode）

- [x] T3.1 `probe-app/src/main/cpp/probe_shim.c`：`dlsymNodeStart()`（`dlopen("libnode.so"|RTLD_DEFAULT)` →
  `dlsym("_ZN4node5StartEiPPc")`）+ `getDlError()`（dlerror 文本）
- [x] T3.2 `probe-app/src/main/java/org/fellow99/nodeprobe/ShimProbe.java`：`ensureLoaded()` 幂等加载 +
  native 面
- [x] T3.3 NDK 28.2.13676358 命令行编译 `libprobe_shim.so` → `probe-app/src/main/jniLibs/arm64-v8a/`
- [x] T3.4 `probe-app/docs/probe-shim-build.md`：编译命令 + readelf 验证
- [x] T3.5 垫片二进制不入库（`probe-app/.gitignore`）

## T4 探针与报告

- [x] T4.1 `MainActivity.java` PRB-01…05（判据见 test-cases.md）+ 单线程 + `Future.get(timeout)` 超时兜底
- [x] T4.2 报告 `filesDir/probe-report.json`（三态 + summary）+ UI 逐条 log
- [x] T4.3 无头 `--ez autorun true`（FR-101-PROBE-007）

## T5 host 构建验证

- [x] T5.1 `gradle :probe-app:assembleDebug --console=plain`（Gradle 9.4.1 发行版）BUILD SUCCESSFUL
- [x] T5.2 `unzip -l` 核对 `libnode.so`/`libc++_shared.so`/`libprobe_shim.so` 入包 + META-INF 资源
- [x] T5.3 `logs/20261105-1/DEV_CHECKLIST.md` 记录

## T6 遗留执行项（非文档缺口）

- [ ] T6.1 **设备跑批 ⬜**（编排者统一排队）：`adb install` → `--ez autorun true` → `run-as` 拉
  `probe-report.json`；结论回填 test-cases 事实区
- [ ] T6.2 **进程内执行 FR-101-PROBE-005 ⬜**：本批 BLOCKED 不硬造；后续批次实现后移除 spec 的 ⬜
  并落地 PRB-05
- [ ] T6.3 PRB-03 的 sha 锁值若引擎升版需同步 `MainActivity.java` 常量（镜像 runtime.lock.json，C2）
