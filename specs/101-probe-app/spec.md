# 101-probe-app · 引擎载荷探针载体（probe-app）功能规格

> 模块：101-probe-app · 状态：🟡 规划落地中（specs-based-devflow，先文档后实现；本批次交付 spec + 实现 + host 构建验证，设备执行 ⬜）
> 所属工程：aosp-node-runtime（Node 引擎 AAR）· 事实源：`runtime.lock.json`（active node=26.13.0/abi=arm64-v8a）·
> `node-runtime/src/main/resources/META-INF/node-runtime.properties`（node.version/sha256）·
> 参照消费方：aosp-node-bridge NativeBridge 链（`System.loadLibrary("node")` → `dlsym(RTLD_DEFAULT,"_ZN4node5StartEiPPc")`）
> 前置事实：本仓**无嵌入 API 类**（只有 .so + 资源），探针需自带 JNI 垫片（libprobe_shim.so）按名解析引擎入口符号。

## 1. 目的

定义「引擎载荷探针载体」的行为契约：一个独立 Android app（`probe-app`，挂在根 Gradle 工程下）把
`node-runtime` 模块的**载荷 / 加载 / 版本契约 / 入口符号**逐个落到 arm64 设备上，给出可证伪的
PASS / FAIL / BLOCKED 判定并产出机器可读 JSON 报告（`filesDir/probe-report.json`）。本模块**不新增
产品能力**：引擎加载姿势完全对镜像-aosp-node-bridge 消费链（`loadLibrary("node")` 后 `dlsym node::Start`），
仅多一层极小 JNI 垫片用于把 `dlerror` 文本与符号地址带回 Java 判据层。

> 逆向/参照事实源：`probe-app/` 全部代码 + `runtime.lock.json` + `node-runtime.properties` +
> `node-runtime/src/main/jniLibs/arm64-v8a/{libnode.so,libc++_shared.so}`。

## 2. 范围

**在范围内（101-probe-app）：** 引擎载荷完整性自检（libnode.so/libc++_shared.so 就位、arm64 唯一 ABI）；
引擎加载（`System.loadLibrary("node")`，失败可见具体异常）；版本契约（`node-runtime.properties` 的
`node.version`/`sha256` vs `runtime.lock.json` active 26.13.0）；引擎入口符号（JNI 垫片 `dlsym node::Start`
非 0）；进程内节点执行（`node -e` 回程，**可选实现**）；三态 JSON 报告；无头自动化（`--ez autorun true`）；
探针超时。

**不在范围内（101-probe-app）：** 引擎二进制本身的构建与门禁（→ `tools/provision.mjs` / CI）；bridge /
Kotlin API 面（aosp-node-bridge 仓，本仓无）；npm/CLI 行为（aosp-node-cli 仓）；探针 App 的发布形态（不发布）。

## 3. 功能需求

> 术语：探针（Probe）= `MainActivity` 中一条带 `id`/`title`/`check` 的记录；判据 `check` 返回
> `null` = PASS，`"BLOCKED:…"` = BLOCKED，`"PASS-"` 前缀 = 事实形态 PASS，其余 = FAIL。

### 3.1 载荷域

- **FR-101-PROBE-001（MUST）**：载荷就位——APK 打包 arm64-v8a 的 `libnode.so` 与 `libc++_shared.so`
  （经根工程 `:node-runtime` 模块投影，`implementation(project(":node-runtime"))`）；设备侧二者存在于
  `nativeLibraryDir`；`SUPPORTED_ABIS` 为 arm64 唯一 ABI（无 x86 冒充）。判据：PRB-01。
  （as-built：`probe-app/build.gradle.kts` ndk.abiFilters + `MainActivity.java` PRB-01）

### 3.2 加载域

- **FR-101-PROBE-002（MUST）**：引擎加载——`System.loadLibrary("node")` 成功（链接失败可见：捕获
  `UnsatisfiedLinkError`/`Throwable` 并在 detail 记录具体异常文本，不断言上游文案）。判据：PRB-02。

### 3.3 版本契约域

- **FR-101-PROBE-003（MUST）**：版本契约——读取 `META-INF/node-runtime.properties` 的 `node.version`
  （classloader `getResourceAsStream` 优先，取不到则从 assets/META-INF 兜底——由 app 侧把
  `node-runtime/src/main/resources` 声明为 assets source dir）；断言 `node.version == 26.13.0`；`sha256`
  与 `runtime.lock.json` active 26.13.0 的锁值比对（锁不在 APK，期望 sha 以编译期常量镜像锁值，见
  `MainActivity.java` 顶部注释）；设备侧实际 `libnode.so` 文件 sha256 与 properties.sha256 比对
  （完整性事实，可计算则断言、不可计算则 `PASS-FACT` 记录）。判据：PRB-03。

### 3.4 入口符号域

- **FR-101-PROBE-004（MUST）**：引擎入口符号——JNI 垫片 `libprobe_shim.so`（`probe_shim.c`，NDK r28
  交叉编译，**不链接 libnode，按名 dlsym**）暴露 `dlsymNodeStart(): long`：先 `dlopen("libnode.so")`、
  失败则退回 `dlsym(RTLD_DEFAULT)`，解析 `"_ZN4node5StartEiPPc"`；返回地址非 0 即 PASS，0 = FAIL 且
  `getDlError()` 带回 dlerror 文本。判据：PRB-04。

### 3.5 进程内执行域（可选）

- **FR-101-PROBE-005（SHOULD，本批次 ⬜ 后续批次）**：进程内执行 `node -e "console.log(process.version)"`
  回程 `v26.13.0`。**判据（若实现时生效）**：基于垫片取得 `node::Start` 函数指针后以
  `argv=["node","-e",<script>]` 调用，捕获 stdout，20000ms 超时，断言回程 == `v26.13.0`。实现约束：
  `node::Start` 非重入、含信号/退出路径副作用，需 isolate 引导 + stdout 重定向 + 进程内安全隔离，
  **无法在本批次（无设备窗口）验证** ⇒ 本批次**不实现**（不硬造），FR 标 ⬜ 后续批次；探针 PRB-05 固定
  返回 BLOCKED 并写明缘由。实现/验证条件就绪后在 spec 本 FR 写明落点并移除 ⬜。

### 3.6 报告与运行域

- **FR-101-PROBE-006（MUST）**：报告——每条探针 `PASS/FAIL/BLOCKED` + `detail` 汇总为
  `filesDir/probe-report.json`（`runAt`/`device`/`expected`/`summary` 头部）；逐条 `log()` 到 UI。
- **FR-101-PROBE-007（MUST）**：无头自动化——`adb shell am start … --ez autorun true` 启动即自动跑批
  （免点击，便于 CI/脚本 `run-as` 拉报告）。
- **FR-101-PROBE-008（MUST）**：探针超时——每条探针独立 timeout（判据回调经单线程 + `Future.get(timeout)`），
  超时记为 FAIL（detail 含 `超时(>Nms)`），不中断整体跑批。

## 4. 用户故事

- 作为 **测试/QA**，我需要在 arm64 设备上验证「APK 里确有引擎载荷、能加载、版本钉 26.13.0、入口符号在」，
  得到可证伪的三态结果与机器可读 JSON，从而给 G3 判据。
- 作为 **发布/流水线实现者**，我需要 CI 首产后自动部署引擎 AAR 到探针 App，验证载荷/加载/版本/符号
  四链无回归，避免把「host 能编 AAR」当成「设备能加载引擎」。
- 作为 **消费方（aosp-node-bridge 链）开发**，我需要一个最小载体观察 `loadLibrary("node")` + `dlsym
  node::Start` 在真实 APK 上的行为（含 dlerror 可见性），以决定 bridge 的加载容错策略。

## 5. 验收场景（Given/When/Then）

1. **载荷就位**：Given `:node-runtime` 已投放 26.13.0 引擎（`tools/provision.mjs --check` 全过）；
   When 构建 APK 并设备跑批； Then PRB-01 PASS 且 `unzip -l` 可见
   `lib/arm64-v8a/{libnode.so,libc++_shared.so,libprobe_shim.so}`。
2. **引擎加载**：Given 载荷就位； When PRB-02 `System.loadLibrary("node")`； Then 无异常抛出 PASS；
   若失败 detail 携带具体异常文本。
3. **版本契约**：Given `node-runtime.properties`（node.version=26.13.0, sha256=bc5d15f8…）；
   When PRB-03 读属性； Then 版本断言 == 26.13.0、sha256 == 锁值 26.13.0、实际文件哈希与 properties 一致。
4. **入口符号**：Given PRB-02 PASS； When PRB-04 `dlsymNodeStart()`； Then 返回地址非 0（PASS）；
   若 0，detail 携带 `getDlError()` 的 dlerror 文本。
5. **进程内执行（⬜）**：Given 后续批次实现 FR-101-PROBE-005； When 以 `node -e` 回程； Then stdout ==
   `v26.13.0`。本批次 PRB-05 = BLOCKED。
6. **无头报告**：Given `am start --ez autorun true`； When 自动跑批结束； Then
   `filesDir/probe-report.json` 存在且可 `run-as` 拉回；summary 计数 = 探针总数。

## 6. 实体表

| 实体 | 字段 | 说明 | as-built |
|---|---|---|---|
| `Probe` | `id`/`title`/`timeoutMs`/`Check` | 一条探针的完整定义；判据回调 | `MainActivity.java` (probes()) |
| `Check` | `String check()` | 判据函数：null=PASS；`BLOCKED:`/`PASS-` 前缀特殊判定 | `MainActivity.java` |
| `ShimProbe` | `ensureLoaded()`/`dlsymNodeStart():long`/`getDlError():String` | libprobe_shim.so 的 JNI 面；`0`=解析失败 | `probe_shim.c` + `ShimProbe.java` |
| `node-runtime.properties` | `node.version`/`abi`/`size`/`sha256`/`source` | 引擎版本常量（本仓 AAR 资源，classloader 路径优先/assets 兜底） | `node-runtime/src/main/resources/META-INF/node-runtime.properties` |
| `runtime.lock.json` | `active.node`/`releases[].sha256` | 版本/SHA 唯一事实源（期望值镜像，锁不入 APK） | `runtime.lock.json` |
| 报告 JSON | `runAt`/`device`/`expected`/`summary`/`probes[]` | `filesDir/probe-report.json`，`probes[]` 含逐条 verdict | `MainActivity.java` |

## 7. NFR

- **架构**：arm64-v8a 唯一 ABI（真机或 arm64 模拟器；x86_64 模拟器禁止冒充）。
- **域/网络**：App 域内自检；**零外部网络**（无 INTERNET 等权限）；报告纯文本 JSON 落 `filesDir`。
- **性能**：单轮跑批 < 60s（PRB-03 文件哈希约 1–2s / 87MB）；各探针独立 timeout。
- **可靠性**：任一探针异常/超时不中断整体（try/catch + Future timeout）；加载失败可见 dlerror/异常文本。
- **可维护性**：探针为声明式数据（`new Probe(...)` 数组），新增用例只加一条记录 + 判据 lambda；垫片
  小而稳（仅 2 个 JNI 方法，不链接 libnode）。
- **安全/卫生**：探针 App 不发布、零权限、可卸载；垫片源码 + 编译命令入 docs（二进制不入库）。

## 8. 依赖

| 依赖 | 锚点 | 说明 |
|---|---|---|
| `:node-runtime` 模块 | `node-runtime/`（含 `jniLibs/arm64-v8a/*.so` + `META-INF`） | `implementation(project(":node-runtime"))` 投影引擎载荷与版本资源；继承其 minSdk 24 / compileSdk 36 / ABI |
| `runtime.lock.json` | `../../runtime.lock.json` | active 26.13.0；PRB-03 期望 sha 镜像锁值（C2 唯一事实源） |
| NDK 28.2.13676358 | Android SDK `ndk/28.2.13676358` | 交叉编译 JNI 垫片 `libprobe_shim.so`（命令见 [plan](./plan.md) §5 / `docs/probe-shim-build.md`） |
| Gradle 9.4.1 + AGP 9.2.1 | `.dist/gradle-dist/gradle-9.4.1`（aosp-node-cli）· 根 `gradle/libs.versions.toml` | 构建工具链（与 sibling probe-app 同款） |
| 引擎加载链参照 | aosp-node-bridge NativeBridge | `loadLibrary("node")` → `dlsym("_ZN4node5StartEiPPc")`（垫片按名解析，同链） |

## 9. 状态与遗留

- 本批次：spec/plan/tasks/test-cases 四件套 + `probe-app/` 实现 + host 构建验证（`logs/20261105-1/DEV_CHECKLIST.md`）。
- **设备执行 ⬜**（由编排者统一排队）；进程内执行 FR-101-PROBE-005 ⬜（本批次 BLOCKED，不硬造）。
- **投放修正事实**：本批次 host 侧发现并修复 `node-runtime/src/main/jniLibs/arm64-v8a/libnode.so`
  被误投放为 24.22.1（sha `be1d1641…` ≠ 锁值）——已按 `runtime.lock.json` + GitHub Release asset
  重新投放 26.13.0（sha `bc5d15f8…`，`provision.mjs --check` 八项全过），见 DEV_CHECKLIST 阻塞项 1。
