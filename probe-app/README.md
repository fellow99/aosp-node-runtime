# node-runtime probe-app —— 引擎载荷探针载体

对 `aosp-node-runtime`（Node 引擎 AAR）的引擎载荷 / 加载 / 版本契约 / 入口符号的设备侧探针（specs/101-probe-app）。

加载链与 aosp-node-bridge NativeBridge 同源：`System.loadLibrary("node")` → `dlsym("_ZN4node5StartEiPPc")`；
本模块多一层**极小 JNI 垫片**（`libprobe_shim.so`，不链接 libnode、按名 dlsym）把 dlerror 文本与
符号地址带回判据层。

## 探针

| id | 判据 | FR |
|---|---|---|
| PRB-01 | libnode.so/libc++_shared.so 在 nativeLibraryDir，arm64 唯一 ABI | FR-101-PROBE-001 |
| PRB-02 | `System.loadLibrary("node")` 成功；失败可见具体异常 | FR-101-PROBE-002 |
| PRB-03 | `node-runtime.properties` node.version==26.13.0、sha256==锁值、实际文件哈希一致 | FR-101-PROBE-003 |
| PRB-04 | `dlsym(_ZN4node5StartEiPPc)` 非 0（dlerror 可见） | FR-101-PROBE-004 |
| PRB-05 | 进程内执行 `node -e` 回程（⬜ 后续批次，本批 BLOCKED 不硬造） | FR-101-PROBE-005 |

## 构建（根工程，Gradle 9.4.1 发行版）

```bash
# 前置 1：引擎 26.13.0 投放校验（八项门禁；本仓 jniLibs 由 CI/provision 投放）
node tools/provision.mjs --check
# 前置 2：JNI 垫片交叉编译（一次性；命令见 docs/probe-shim-build.md）
#   ⇒ probe-app/src/main/jniLibs/arm64-v8a/libprobe_shim.so

# 构建（在 aosp-node-runtime 根目录）
GRADLE="/d/node-bridge-workspace/aosp-node-cli/.dist/gradle-dist/gradle-9.4.1/bin/gradle"
"$GRADLE" :probe-app:assembleDebug --console=plain
# 产物：probe-app/build/outputs/apk/debug/probe-app-debug.apk
```

## 使用（设备运行由编排者统一排队）

```bash
adb install -r probe-app/build/outputs/apk/debug/probe-app-debug.apk
# 无头跑批（FR-101-PROBE-007）
adb shell am start -n org.fellow99.nodeprobe/.MainActivity --ez autorun true
# 拉报告
adb shell run-as org.fellow99.nodeprobe cat files/probe-report.json > probe-report.json
```

注意：
- 仅 arm64-v8a（本仓唯一 ABI）；x86_64 模拟器禁止冒充。
- 零权限、零外部网络（App 域自检）。
- 垫片二进制不入库（`.gitignore`）；重建见 `docs/probe-shim-build.md`。
