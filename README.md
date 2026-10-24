# aosp-node-runtime

[**English**](README.md) · [中文](README_zh.md)

The **Node.js engine for Android, packaged as an AAR** — a cross-compiled `libnode.so` shared library (arm64-v8a) that [aosp-node-bridge](https://github.com/fellow99/aosp-node-bridge) loads in-process with `dlopen` + `node::Start`.

This repository contains **no embedding API** — only the engine binary, the headers used to build it, and the reproducible cross-compilation pipeline. The bridge/Kotlin API lives in [aosp-node-bridge](https://github.com/fellow99/aosp-node-bridge).

## Maven coordinate

```
io.github.fellow99:node-runtime:<node-version>
```

The artifact version **equals the Node.js version**. Current release: `24.2.0`.

## Usage

Add the engine alongside the bridge. It is a `runtimeOnly` dependency — your code never links against it directly:

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.fellow99:node-bridge:0.2.0")
    runtimeOnly("io.github.fellow99:node-runtime:24.2.0")
}
```

The bridge locates `libnode.so` in the APK's native library directory at runtime; swapping Node versions is just changing this dependency (the bridge does not need to be recompiled).

## What is in the AAR

```
jni/arm64-v8a/libnode.so          # Node.js engine (shared library, ET_DYN, no PT_INTERP)
jni/arm64-v8a/libc++_shared.so    # C++ runtime (this package is the sole provider)
```

Requirements: `minSdk 24` · ABI `arm64-v8a`.

## How the engine is built

The binary is **not committed** and is never built by hand. [`build-node-android.yml`](.github/workflows/build-node-android.yml) runs on GitHub Actions:

1. Download and checksum-verify the official `node-v<version>.tar.xz` from nodejs.org.
2. Apply [`scripts/patch-node-source.py`](scripts/patch-node-source.py):
   - add `--shared --with-intl=none` to the Android configure args,
   - set host compilers for the cross build,
   - disable V8's trap handler on Android.
3. Cross-compile with Android NDK r28 via [`scripts/build-node-android.sh`](scripts/build-node-android.sh) (`./android-configure … && make`).
4. Assert the result is `ET_DYN`, has no `PT_INTERP`, and exports `node::Start`.
5. Publish `libnode.so` + headers as a GitHub Release artifact.

Trigger a build manually with the Node version as input:

```bash
gh workflow run build-node-android.yml -f node_version=24.2.0 -f android_api=24
```

## Repository layout

```
aosp-node-runtime/
├── node-runtime/                 # Android library module (the AAR container)
│   ├── include/node/             # NAPI + V8 headers (committed)
│   └── src/main/jniLibs/         # libnode.so deployed here (git-ignored; CI/provision)
├── scripts/
│   ├── build-node-android.sh     # NDK cross-compile
│   └── patch-node-source.py      # idempotent Node source patches
├── tools/provision.mjs           # deploy binary + 8-item ELF gate
├── runtime.lock.json            # version + SHA-256 + gates + provenance (source of truth)
└── .github/workflows/build-node-android.yml
```

## Locking and verification

[`runtime.lock.json`](runtime.lock.json) is the single source of truth for the active Node version, binary SHA-256, ELF gates, and provenance. After deploying a build, run:

```bash
node tools/provision.mjs --check          # verify the deployed libnode.so against the lock
node tools/provision.mjs --inspect <so>   # print the ELF gate report for a binary
```

## License

[MIT](LICENSE). Node.js itself is MIT; see `node-runtime/THIRD-PARTY-NOTICES.md` for bundled third-party components.
