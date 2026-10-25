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

LIBNODE="$OUTPUT_DIR/bin/arm64-v8a/libnode.so"
RAW_SIZE=$(stat -c%s out/Release/libnode.so)
cp out/Release/libnode.so "$LIBNODE"

# Strip DWARF debug info (and local symbols) but KEEP the dynamic + global symbol
# tables: node::Start / napi_* live in .dynsym, which stripping never removes, so
# runtime linking and backtrace symbolization are unaffected. Saves ~25 MB and the
# released bytes are exactly what tools/provision.mjs deploys (hash in lock).
"$TOOLCHAIN/bin/llvm-strip" --strip-unneeded "$LIBNODE"
STRIPPED_SIZE=$(stat -c%s "$LIBNODE")
LIBNODE_SHA256=$(sha256sum "$LIBNODE" | awk '{print $1}')

cp common.gypi config.gypi "$HEADERS/" 2>/dev/null || true
cp src/*.h "$HEADERS/"
cp deps/v8/include/v8*.h "$HEADERS/" 2>/dev/null || true
cp deps/v8/include/libplatform/*.h "$HEADERS/libplatform/" 2>/dev/null || true
cp deps/v8/include/cppgc/*.h "$HEADERS/cppgc/" 2>/dev/null || true
cp -r deps/uv/include/* "$HEADERS/" 2>/dev/null || true
cp deps/zlib/zconf.h deps/zlib/zlib.h "$HEADERS/" 2>/dev/null || true
cp LICENSE "$OUTPUT_DIR/LICENSE.node" 2>/dev/null || true

# libc++_shared.so — libnode.so has DT_NEEDED on it. NDK r23+ ships it in the
# sysroot (the old lib/clang/<ver>/lib/linux glob no longer exists, which is why
# a silent miss here previously produced an AAR with no libc++_shared.so).
LIBCXX="$TOOLCHAIN/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
if [ ! -f "$LIBCXX" ]; then
    LIBCXX=$(find "$TOOLCHAIN" -name libc++_shared.so -path '*aarch64*' 2>/dev/null | head -1)
fi
if [ -z "$LIBCXX" ] || [ ! -f "$LIBCXX" ]; then
    echo "ERROR: libc++_shared.so (arm64) not found under NDK $NDK_DIR" >&2
    exit 1
fi
OUT_LIBCXX="$OUTPUT_DIR/bin/arm64-v8a/libc++_shared.so"
LIBCXX_RAW_SIZE=$(stat -c%s "$LIBCXX")
cp "$LIBCXX" "$OUT_LIBCXX"
# NDK r28 ships an UNSTRIPPED libc++_shared.so in the sysroot (~9 MB with DWARF).
# Strip it the same way as libnode.so; .dynsym/SONAME/DT_NEEDED survive (~1.2 MB).
"$TOOLCHAIN/bin/llvm-strip" --strip-unneeded "$OUT_LIBCXX"
LIBCXX_SHA256=$(sha256sum "$OUT_LIBCXX" | awk '{print $1}')
echo "Bundled libc++_shared.so <- $LIBCXX (${LIBCXX_RAW_SIZE} -> $(stat -c%s "$OUT_LIBCXX") bytes)"

VERSION="$(sed -nE 's/^#define NODE_(MAJOR|MINOR|PATCH)_VERSION ([0-9]+)$/\2/p' src/node_version.h | paste -sd . -)"
cat >"$OUTPUT_DIR/BUILD-METADATA.txt" <<EOF
node_version=$VERSION
android_abi=arm64-v8a
android_api=$ANDROID_API
ndk=$(basename "$NDK_DIR")
libnode_size=$STRIPPED_SIZE
libnode_size_unstripped=$RAW_SIZE
libnode_sha256=$LIBNODE_SHA256
libcxx_sha256=$LIBCXX_SHA256
stripped=llvm-strip --strip-unneeded
EOF

echo "=== Build complete ==="
file "$LIBNODE"
echo "Node version: $VERSION (libnode ${RAW_SIZE} -> ${STRIPPED_SIZE} bytes)"
ls -lh "$OUTPUT_DIR/bin/arm64-v8a/"
