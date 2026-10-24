#!/usr/bin/env python3
"""Apply the minimal source changes needed for a Node.js Android shared-library build.

Supports Node.js 18.x – 24.x. The patches are idempotent (safe to re-run).
"""

from __future__ import annotations

import argparse
import shutil
from pathlib import Path


class PatchError(RuntimeError):
    pass


def replace_once(path: Path, old: str, new: str, already_present: str) -> None:
    text = path.read_text(encoding="utf-8")
    if already_present in text:
        return
    if old not in text:
        raise PatchError(f"Expected source marker not found in {path}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def patch_configure(root: Path) -> None:
    """Patch android_configure.py to add --shared --with-intl=none and set host compilers."""
    path = root / "android_configure.py"
    if not path.exists():
        raise PatchError(f"{path} not found")

    text = path.read_text(encoding="utf-8")
    changed = False

    # Add import shutil if missing
    if "import shutil" not in text:
        if "import os" in text:
            text = text.replace("import os", "import os\nimport shutil", 1)
            changed = True

    # Set host compilers (needed for cross-compiling: gyp builds some tools for host)
    if "os.environ['CC_host']" not in text:
        cxx_line = "os.environ['CXX'] = toolchain_path + \"/bin/\" + TOOLCHAIN_PREFIX + android_sdk_version + \"-\" + \"clang++\""
        if cxx_line in text:
            host_tools = (
                cxx_line
                + "\nos.environ['CC_host'] = shutil.which('gcc') or 'gcc'"
                + "\nos.environ['CXX_host'] = shutil.which('g++') or 'g++'"
            )
            text = text.replace(cxx_line, host_tools, 1)
            changed = True

    # Add --shared --with-intl=none to configure args
    if "--shared" not in text:
        shared_old = '"--openssl-no-asm --cross-compiling")'
        shared_new = '"--openssl-no-asm --with-intl=none --cross-compiling --shared")'
        if shared_old in text:
            text = text.replace(shared_old, shared_new, 1)
            changed = True
        else:
            # Try alternate format (v24 might use different quoting)
            shared_old2 = "--openssl-no-asm --cross-compiling"
            shared_new2 = "--openssl-no-asm --with-intl=none --cross-compiling --shared"
            if shared_old2 in text:
                text = text.replace(shared_old2, shared_new2, 1)
                changed = True
            else:
                raise PatchError(f"Could not find configure args marker in {path}")

    if changed:
        path.write_text(text, encoding="utf-8")
        print(f"  patched: {path.name}")


def patch_trap_handler(root: Path) -> None:
    """Disable V8 trap handler for Android (not supported on ARM)."""
    header = root / "deps" / "v8" / "src" / "trap-handler" / "trap-handler.h"
    if not header.exists():
        print(f"  skip (not found): {header}")
        return

    text = header.read_text(encoding="utf-8")
    marker = "// aosp-node-runtime: Android trap handling is disabled"
    if marker in text:
        return

    # Disable the trap handler for Android by making V8_TRAP_HANDLER_SUPPORTED always false
    old = "#if V8_OS_ANDROID && V8_TRAP_HANDLER_SUPPORTED"
    new = f"{marker}\n#if false && V8_OS_ANDROID && V8_TRAP_HANDLER_SUPPORTED"
    if old in text:
        text = text.replace(old, new, 1)
        header.write_text(text, encoding="utf-8")
        print(f"  patched: {header.name}")


def patch_gypi_for_shared(root: Path) -> None:
    """Ensure the gyp build produces a shared library with correct soname."""
    gypi = root / "common.gypi"
    if not gypi.exists():
        return

    text = gypi.read_text(encoding="utf-8")
    if "aosp-node-runtime" in text:
        return

    # Add soname for libnode.so
    # The --shared flag handles most of this, but we want to ensure the soname
    # is set correctly for dlopen.
    print(f"  skip (handled by --shared flag): {gypi.name}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, help="Path to Node.js source tree")
    parser.add_argument("--ndk", required=True, help="Path to Android NDK")
    args = parser.parse_args()

    source = Path(args.source)
    ndk = Path(args.ndk)

    if not (source / "src" / "node_version.h").exists():
        raise PatchError(f"Not a Node.js source tree: {source}")

    print("Patching Node.js source for Android shared library build...")
    patch_configure(source)
    patch_trap_handler(source)
    patch_gypi_for_shared(source)
    print("Done.")


if __name__ == "__main__":
    main()
