# probe-shim —— JNI 垫片交叉编译（libprobe_shim.so）

> 对照 [specs/101-probe-app/spec.md](../specs/101-probe-app/spec.md) FR-101-PROBE-004。
> 垫片**不链接 libnode**：引擎符号一律按名 `dlsym` 解析（任务强制，规避 NDK 链接期对引擎符号的依赖）。
> 二进制**不入库**（`probe-app/.gitignore` 的 `src/main/jniLibs/**/*.so`）；`.c` 源码入库，按本文重建。

## 1. 前置

- Android NDK **28.2.13676358**（`%ANDROID_HOME%\ndk\28.2.13676358`，本机 `C:/Users/Administrator/AppData/Local/Android/Sdk/ndk/28.2.13676358`）
- Git Bash（`cmd.exe` 需把 `clang.cmd` 换成对应可执行）

## 2. 编译命令（Git Bash，仓库根目录）

```bash
NDK="C:/Users/Administrator/AppData/Local/Android/Sdk/ndk/28.2.13676358"
CC="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android24-clang.cmd"
SYSROOT="$NDK/toolchains/llvm/prebuilt/windows-x86_64/sysroot"

mkdir -p probe-app/src/main/jniLibs/arm64-v8a
"$CC" -O2 -fPIC -shared \
  -I"$SYSROOT/usr/include" \
  probe-app/src/main/cpp/probe_shim.c \
  -o probe-app/src/main/jniLibs/arm64-v8a/libprobe_shim.so
```

要点：
- `-shared -fPIC`：ET_DYN 位置无关共享库（System.loadLibrary 按名解析 JNI 符号依赖导出 + 动态库形态）。
- `aarch64-linux-android24`：API 24（minSdk）。
- 无 `-l` 引擎依赖、无 `-llog`/`-ldl`（dlopen/dlsym 在 bionic libc，API 21+ 免 -ldl）——「小而稳、不链接 libnode」。
- JNI 符号经 `JNIEXPORT`（default visibility）导出：`Java_org_fellow99_nodeprobe_ShimProbe_{dlsymNodeStart,getDlError}`。

## 3. 验证（host 侧，PRB-HOST-04）

```bash
READELF="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe"
NM="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-nm.exe"

# ① AArch64 + ET_DYN（Machine: AArch64, Type: DYN）
"$READELF" -h probe-app/src/main/jniLibs/arm64-v8a/libprobe_shim.so

# ② JNI 符号导出
"$NM" -D --defined-only probe-app/src/main/jniLibs/arm64-v8a/libprobe_shim.so

# ③ 零 libnode 链接（不应出现 DT_NEEDED libnode.so）
"$READELF" -d probe-app/src/main/jniLibs/arm64-v8a/libprobe_shim.so | grep -i needed
```

期望：`libprobe_shim.so: (NEEDED) libc.so`（及 libdl 由 libc 合并），**无 libnode.so**；
`T Java_org_fellow99_nodeprobe_ShimProbe_dlsymNodeStart` 与
`T Java_org_fellow99_nodeprobe_ShimProbe_getDlError` 已导出。

## 4. 入包

垫片置于 `probe-app/src/main/jniLibs/arm64-v8a/`，随 APK（`useLegacyPackaging=true`）打包；
应用侧 `ShimProbe.ensureLoaded()` → `System.loadLibrary("probe_shim")` 加载。
