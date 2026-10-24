#!/usr/bin/env python3
"""Apply the minimal source changes needed to build Node.js as an Android
arm64-v8a *shared* library (libnode.so).

Supports Node.js 18.x – 24.x. All patches are idempotent (safe to re-run).

Patches
-------
1. android_configure.py
   - add ``--shared --with-intl=none`` to the Android configure args
   - export CC_host / CXX_host so gyp can build the *host* snapshot tools

2. deps/v8/src/trap-handler/trap-handler.h
   - force ``V8_TRAP_HANDLER_SUPPORTED`` to false GLOBALLY (host + target).
     A cross build compiles the host snapshot tool (mksnapshot) with the host
     compiler while the trap-handler implementation sources are only built for
     supported targets; leaving the macro platform-derived makes host objects
     reference TryHandleSignal/RegisterDefaultTrapHandler/ProbeMemory that are
     never linked ("undefined reference" while linking mksnapshot). Forcing it
     false keeps host and target consistent. (Same approach as
     AxleMax/nodejs-android-build.)

3. node.gyp (best-effort)
   - do not build/link the cctest executable for android shared builds.

4. cpu-features
   - copy the NDK's sources/android/cpufeatures into deps/android and compile
     it into node_base. Node's vendored zlib calls android_getCpuFeatures()
     on Android; common.gypi adds the NDK header path but never compiles the
     implementation, so the symbol would otherwise be undefined at the link.
"""

from __future__ import annotations

import argparse
import shutil
from pathlib import Path


class PatchError(RuntimeError):
    pass


def _edit_once(path: Path, old: str, new: str, marker: str, required: bool = True) -> bool:
    text = path.read_text(encoding="utf-8")
    if marker in text:
        return False  # already patched
    if old not in text:
        if required:
            raise PatchError(f"Expected marker not found in {path}:\n---\n{old[:200]}\n---")
        print(f"  skip (marker absent): {path.name}")
        return False
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    return True


def patch_configure(root: Path) -> None:
    path = root / "android_configure.py"
    if not path.exists():
        raise PatchError(f"{path} not found")
    text = path.read_text(encoding="utf-8")
    changed = False

    if "import shutil" not in text and "import os" in text:
        text = text.replace("import os", "import os\nimport shutil", 1)
        changed = True

    # Host compilers for the cross build (mksnapshot / mkcodecache run on host).
    if "os.environ['CC_host']" not in text:
        cxx_line = (
            "os.environ['CXX'] = toolchain_path + \"/bin/\" + TOOLCHAIN_PREFIX"
            " + android_sdk_version + \"-\" + \"clang++\""
        )
        if cxx_line in text:
            text = text.replace(
                cxx_line,
                cxx_line
                + "\nos.environ['CC_host'] = shutil.which('gcc') or 'gcc'"
                + "\nos.environ['CXX_host'] = shutil.which('g++') or 'g++'",
                1,
            )
            changed = True
        else:
            raise PatchError("Could not locate the CXX export line in android_configure.py")

    # Build a shared library without bundled ICU (smaller, avoids ICU cross build).
    if "--shared" not in text:
        if '"--openssl-no-asm --cross-compiling")' in text:
            text = text.replace(
                '"--openssl-no-asm --cross-compiling")',
                '"--openssl-no-asm --with-intl=none --cross-compiling --shared")',
                1,
            )
            changed = True
        elif "--openssl-no-asm --cross-compiling" in text:
            text = text.replace(
                "--openssl-no-asm --cross-compiling",
                "--openssl-no-asm --with-intl=none --cross-compiling --shared",
                1,
            )
            changed = True
        else:
            raise PatchError("Could not locate configure args in android_configure.py")

    if changed:
        path.write_text(text, encoding="utf-8")
        print(f"  patched: {path.name}")
    else:
        print(f"  already patched: {path.name}")


def patch_trap_handler(root: Path) -> None:
    header = root / "deps" / "v8" / "src" / "trap-handler" / "trap-handler.h"
    if not header.exists():
        raise PatchError(f"{header} not found")

    text = header.read_text(encoding="utf-8")
    marker = "// aosp-node-runtime: trap handler disabled"
    if marker in text:
        print("  already patched: trap-handler.h")
        return

    # Replace the whole platform-derived #define block with a single global
    # `false`, stopping just before the Android-specific #if below it.
    start_marker = "// X64 on Linux, Windows, MacOS, FreeBSD."
    end_marker = "\n#if V8_OS_ANDROID && V8_TRAP_HANDLER_SUPPORTED"
    start = text.find(start_marker)
    end = text.find(end_marker)
    if start < 0 or end < 0:
        raise PatchError("trap-handler.h platform markers not found")

    replacement = (
        f"{marker}: it has not had the required V8 security review and the\n"
        "// trap-handler implementation is not built for Android. Force it off\n"
        "// globally so the host snapshot tools and Android target stay consistent.\n"
        "#define V8_TRAP_HANDLER_SUPPORTED false\n"
    )
    header.write_text(text[:start] + replacement + text[end:], encoding="utf-8")
    print("  patched: trap-handler.h (V8_TRAP_HANDLER_SUPPORTED=false, host+target)")


def patch_node_gyp_cctest(root: Path) -> None:
    gyp = root / "node.gyp"
    if not gyp.exists():
        print("  skip (no node.gyp)")
        return
    text = gyp.read_text(encoding="utf-8")
    old = (
        "# Skip cctest while building shared lib node for Windows\n"
        "        [ 'OS==\"win\" and node_shared==\"true\"', {\n"
    )
    new = (
        "# Skip cctest while building shared lib node for Windows/Android\n"
        "        [ 'OS in (\"win\", \"android\") and node_shared==\"true\"', {\n"
    )
    if 'OS in ("win", "android")' in text:
        print("  already patched: node.gyp (skip cctest on android)")
        return
    count = text.count(old)
    if count == 0:
        print("  skip (cctest marker absent): node.gyp")
        return
    gyp.write_text(text.replace(old, new), encoding="utf-8")
    print(f"  patched: node.gyp (skip {count} cctest target(s) on android)")


def patch_cpu_features(root: Path, ndk: Path) -> None:
    """Link the legacy NDK cpu-features helper.

    Node's vendored zlib (deps/zlib/cpu_features.c) calls android_getCpuFeatures()
    under ARMV8_OS_ANDROID. common.gypi puts the NDK cpufeatures *include dir* on
    the command line (so the header resolves), but nothing compiles cpu-features.c,
    so the symbol is undefined at the final link (--no-allow-shlib-undefined).
    Copy the NDK source into deps/android and add it to the node_base target so it
    is compiled into libnode.so. Required for Node >= 18 (V8 may use getauxval,
    but zlib's ARM CRC32/PMULL detection still uses the legacy helper).
    """
    src = ndk / "sources" / "android" / "cpufeatures"
    if not (src / "cpu-features.c").is_file():
        raise PatchError(
            f"Missing NDK source: {src / 'cpu-features.c'} "
            "(use an NDK that still ships sources/android/cpufeatures, e.g. r28)"
        )
    dst = root / "deps" / "android"
    dst.mkdir(parents=True, exist_ok=True)
    for name in ("cpu-features.c", "cpu-features.h"):
        shutil.copy2(src / name, dst / name)
    print("  copied: NDK cpu-features -> deps/android")

    gyp = root / "node.gyp"
    text = gyp.read_text(encoding="utf-8")
    if "'deps/android/cpu-features.c'" in text or '"deps/android/cpu-features.c"' in text:
        print("  already patched: node.gyp (android cpu-features source)")
        return
    # Inject into the node_base target's conditions (unique anchor in Node 22/24).
    anchor = (
        "      'conditions': [\n"
        '        [ \'openssl_default_cipher_list!=""\', {\n'
    )
    condition = (
        "        [ 'OS==\"android\"', {\n"
        "          'sources': [ 'deps/android/cpu-features.c' ],\n"
        "          'include_dirs': [ 'deps/android' ],\n"
        "        }],\n"
    )
    if anchor not in text:
        raise PatchError("node.gyp node_base conditions anchor not found for cpu-features")
    gyp.write_text(text.replace(anchor, "      'conditions': [\n" + condition + '        [ \'openssl_default_cipher_list!=""\', {\n', 1), encoding="utf-8")
    print("  patched: node.gyp (compile cpu-features.c into node_base)")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path, help="Node.js source tree")
    parser.add_argument("--ndk", required=True, type=Path, help="Android NDK root")
    args = parser.parse_args()

    source = args.source.resolve()
    ndk = args.ndk.resolve()

    if not (source / "src" / "node_version.h").is_file():
        raise PatchError(f"Not a Node.js source tree: {source}")

    major = int(
        (source / "src/node_version.h")
        .read_text(encoding="utf-8")
        .split("#define NODE_MAJOR_VERSION ", 1)[1]
        .splitlines()[0]
        .strip()
    )
    print(f"Patching Node.js {major}.x source for Android shared-library build...")
    patch_configure(source)
    patch_trap_handler(source)
    patch_node_gyp_cctest(source)
    patch_cpu_features(source, ndk)
    print("Done.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
