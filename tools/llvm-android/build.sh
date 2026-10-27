#!/usr/bin/env bash
# Cross-compile a small clang + lld for Android, inside an Ubuntu container, for building npm native addons
# on the phone:
#   bash build.sh <arm64|x86_64>
# One multicall executable (LLVM_TOOL_LLVM_DRIVER_BUILD) holds clang, lld and llvm-ar; it picks the tool from
# the name it is called by, so the app links clang, clang++, ld.lld and llvm-ar to it. Only the phone's own CPU
# is a code-generation target, and the C++ runtime is linked in statically: no shared libraries to find.
#
# The NDK and sources are cached in /work (a mounted volume). Output goes to /work/out/llvm-android-<arch>/:
#   libllvm.so          the multicall executable (named lib*.so so the APK installs it into nativeLibraryDir)
#   resource/           clang's own headers (stddef.h, arm_neon.h, ...) and the compiler-rt builtins
#   version.txt
set -euo pipefail
ARCH="$1"
LLVM_VER=21.1.8
NDK_VER=r29
API=26
W=/work
cd "$W"

case "$ARCH" in
  arm64)  TRIPLE=aarch64-linux-android; ABI=arm64-v8a; TARGET=AArch64 ;;
  x86_64) TRIPLE=x86_64-linux-android;  ABI=x86_64;    TARGET=X86 ;;
  *) echo "unknown arch $ARCH"; exit 1 ;;
esac

if ! command -v cmake >/dev/null || ! command -v ninja >/dev/null; then
  apt-get update -qq
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq build-essential python3 curl xz-utils unzip cmake ninja-build ca-certificates >/dev/null
fi

NDK="$W/android-ndk-$NDK_VER"
[ -d "$NDK" ] || { echo "NDK missing: run tools/node-android/build.sh first"; exit 1; }

# Downloads and unpacking go to temporary names first: an interrupted run leaves nothing half-done behind
SRC="$W/llvm-project-$LLVM_VER.src"
if [ ! -d "$SRC" ]; then
  [ -f "$SRC.tar.xz" ] || {
    curl -fsSL -o "$SRC.tar.xz.part" "https://github.com/llvm/llvm-project/releases/download/llvmorg-$LLVM_VER/llvm-project-$LLVM_VER.src.tar.xz"
    mv "$SRC.tar.xz.part" "$SRC.tar.xz"
  }
  rm -rf "$SRC.tmp" && mkdir "$SRC.tmp"
  tar -xJf "$SRC.tar.xz" -C "$SRC.tmp" --strip-components=1
  mv "$SRC.tmp" "$SRC"
fi

# Table generators run on the build machine, so they are built natively first (shared by both architectures)
HOST="$W/llvm-host-$LLVM_VER"
if [ ! -x "$HOST/bin/clang-tblgen" ]; then
  cmake -S "$SRC/llvm" -B "$HOST" -G Ninja -DCMAKE_BUILD_TYPE=Release \
    -DLLVM_ENABLE_PROJECTS="clang;lld" -DLLVM_TARGETS_TO_BUILD=X86 \
    -DLLVM_INCLUDE_TESTS=OFF -DLLVM_INCLUDE_BENCHMARKS=OFF -DLLVM_INCLUDE_EXAMPLES=OFF
  ninja -C "$HOST" llvm-tblgen clang-tblgen
  ninja -C "$HOST" llvm-min-tblgen 2>/dev/null || true
fi

B="$W/llvm-build-$ARCH"
cmake -S "$SRC/llvm" -B "$B" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="android-$API" -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=MinSizeRel \
  -DCMAKE_C_FLAGS="-ffunction-sections -fdata-sections" -DCMAKE_CXX_FLAGS="-ffunction-sections -fdata-sections" \
  -DCMAKE_EXE_LINKER_FLAGS="-Wl,--gc-sections -Wl,--icf=all -s" \
  -DLLVM_NATIVE_TOOL_DIR="$HOST/bin" \
  -DLLVM_HOST_TRIPLE="$TRIPLE" -DLLVM_DEFAULT_TARGET_TRIPLE="$TRIPLE$API" \
  -DLLVM_TARGETS_TO_BUILD="$TARGET" \
  -DLLVM_ENABLE_PROJECTS="clang;lld" \
  -DLLVM_TOOL_LLVM_DRIVER_BUILD=ON \
  -DLLVM_ENABLE_ASSERTIONS=OFF -DLLVM_ENABLE_TERMINFO=OFF -DLLVM_ENABLE_LIBXML2=OFF \
  -DLLVM_ENABLE_ZLIB=OFF -DLLVM_ENABLE_ZSTD=OFF -DLLVM_ENABLE_LIBEDIT=OFF -DLLVM_ENABLE_LIBPFM=OFF \
  -DLLVM_INCLUDE_TESTS=OFF -DLLVM_INCLUDE_BENCHMARKS=OFF -DLLVM_INCLUDE_EXAMPLES=OFF -DLLVM_INCLUDE_DOCS=OFF \
  -DLLVM_BUILD_UTILS=OFF -DLLVM_PARALLEL_LINK_JOBS=2 \
  -DCLANG_ENABLE_STATIC_ANALYZER=OFF -DCLANG_ENABLE_ARCMT=OFF -DCLANG_PLUGIN_SUPPORT=OFF \
  -DCLANG_INCLUDE_TESTS=OFF -DCLANG_INCLUDE_DOCS=OFF \
  -DCLANG_DEFAULT_LINKER=lld -DCLANG_DEFAULT_RTLIB=compiler-rt -DCLANG_DEFAULT_CXX_STDLIB=libc++ \
  -DCLANG_CONFIG_FILE_USER_DIR="~/.config/clang"
ninja -C "$B" llvm-driver clang-resource-headers

OUT="$W/out/llvm-android-$ARCH"
rm -rf "$OUT.tmp" && mkdir -p "$OUT.tmp/resource/lib/linux"
cp "$B/bin/llvm" "$OUT.tmp/libllvm.so"
MAJOR="${LLVM_VER%%.*}"
cp -r "$B/lib/clang/$MAJOR/include" "$OUT.tmp/resource/include"
# compiler-rt builtins and libunwind come from the NDK's own clang
NDK_RT=$(ls -d "$NDK"/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/*/lib/linux | head -1)
cp "$NDK_RT/libclang_rt.builtins-${TRIPLE%%-*}-android.a" "$OUT.tmp/resource/lib/linux/"
# clang looks for libunwind in a per-architecture directory
mkdir -p "$OUT.tmp/resource/lib/linux/${TRIPLE%%-*}"
cp "$NDK_RT/${TRIPLE%%-*}/libunwind.a" "$OUT.tmp/resource/lib/linux/${TRIPLE%%-*}/"
echo "$LLVM_VER" > "$OUT.tmp/version.txt"
rm -rf "$OUT" && mv "$OUT.tmp" "$OUT"
ls -la "$OUT"
