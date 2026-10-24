#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: build-node-android.sh <node-source-dir> <android-ndk> <output-directory> [android-api]

Cross-compiles Node.js into an Android arm64-v8a shared library.
The source tree is patched in place. Default Android API level is 24.
EOF
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then usage; exit 0; fi
if [[ $# -lt 3 || $# -gt 4 ]]; then usage >&2; exit 2; fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_DIR="$(realpath "$1")"
NDK_DIR="$(realpath "$2")"
OUTPUT_DIR="$(realpath -m "$3")"
ANDROID_API="${4:-24}"
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64"

[[ "$ANDROID_API" =~ ^[0-9]+$ ]] || { echo "Android API must be numeric" >&2; exit 2; }
test -f "$SOURCE_DIR/src/node_version.h"
test -x "$TOOLCHAIN/bin/aarch64-linux-android${ANDROID_API}-clang"
command -v python3 >/dev/null
command -v make >/dev/null

# Patch source for Android shared library build
python3 "$SCRIPT_DIR/patch-node-source.py" --source "$SOURCE_DIR" --ndk "$NDK_DIR"

cd "$SOURCE_DIR"
./android-configure "$NDK_DIR" "$ANDROID_API" arm64
make -j"$(getconf _NPROCESSORS_ONLN)"

# Verify output
test -f out/Release/libnode.so

# Package
rm -rf "$OUTPUT_DIR"
HEADERS="$OUTPUT_DIR/include/node"
mkdir -p "$OUTPUT_DIR/bin/arm64-v8a" "$HEADERS/libplatform" "$HEADERS/cppgc"
cp out/Release/libnode.so "$OUTPUT_DIR/bin/arm64-v8a/libnode.so"
cp common.gypi config.gypi "$HEADERS/" 2>/dev/null || true
cp src/*.h "$HEADERS/"
cp deps/v8/include/v8*.h "$HEADERS/" 2>/dev/null || true
cp deps/v8/include/libplatform/*.h "$HEADERS/libplatform/" 2>/dev/null || true
cp deps/v8/include/cppgc/*.h "$HEADERS/cppgc/" 2>/dev/null || true
cp -r deps/uv/include/* "$HEADERS/" 2>/dev/null || true
cp deps/zlib/zconf.h deps/zlib/zlib.h "$HEADERS/" 2>/dev/null || true
cp LICENSE "$OUTPUT_DIR/LICENSE.node" 2>/dev/null || true

# libc++_shared.so from NDK
LLVM_LIB="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/*/lib/linux/aarch64/libc++_shared.so"
if ls $LLVM_LIB 2>/dev/null | head -1 | grep -q .; then
    cp $(ls $LLVM_LIB | head -1) "$OUTPUT_DIR/bin/arm64-v8a/libc++_shared.so"
fi

VERSION="$(sed -nE 's/^#define NODE_(MAJOR|MINOR|PATCH)_VERSION ([0-9]+)$/\2/p' src/node_version.h | paste -sd . -)"
cat >"$OUTPUT_DIR/BUILD-METADATA.txt" <<EOF
node_version=$VERSION
android_abi=arm64-v8a
android_api=$ANDROID_API
ndk=$(basename "$NDK_DIR")
EOF

echo "=== Build complete ==="
file "$OUTPUT_DIR/bin/arm64-v8a/libnode.so"
echo "Node version: $VERSION"
ls -lh "$OUTPUT_DIR/bin/arm64-v8a/"
