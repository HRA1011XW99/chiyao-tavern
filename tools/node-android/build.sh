#!/usr/bin/env bash
# 用 Docker 交叉编译安卓本机版的 Node，由 tools/android/node.mjs 在 Ubuntu 容器里调用：
#   bash build.sh <arm64|x86_64> <Node 版本，如 v24.14.1>
# 源码和 NDK 缓存在 /work（挂载的宿主目录），产物写到 /work/out/node-android-<架构>/
set -euo pipefail
ARCH="$1"
VER="$2"
NDK_VER=r29
W=/work
mkdir -p "$W"
cd "$W"

if ! command -v g++ >/dev/null || ! command -v python3 >/dev/null; then
  apt-get update -qq
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq build-essential python3 curl xz-utils unzip ca-certificates >/dev/null
fi

# 下载和解压都先落到临时名字，做完再改名：中途被打断不会留下半截文件被下次当成好的
if [ ! -d "android-ndk-$NDK_VER" ]; then
  curl -fsSL -o ndk.zip.part "https://dl.google.com/android/repository/android-ndk-$NDK_VER-linux.zip"
  rm -rf ndk.tmp && mkdir ndk.tmp && unzip -q ndk.zip.part -d ndk.tmp
  mv "ndk.tmp/android-ndk-$NDK_VER" . && rm -rf ndk.tmp ndk.zip.part
fi
NDK="$W/android-ndk-$NDK_VER"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"

if [ ! -f "node-$VER.tar.xz" ]; then
  curl -fsSL -o "node-$VER.tar.xz.part" "https://nodejs.org/dist/$VER/node-$VER.tar.xz"
  mv "node-$VER.tar.xz.part" "node-$VER.tar.xz"
fi
# 每种架构一份源码目录：两种架构的 out/ 互不干扰
SRC="$W/node-$VER-$ARCH"
if [ ! -d "$SRC" ]; then
  rm -rf "$SRC.tmp" && mkdir -p "$SRC.tmp"
  tar -xJf "node-$VER.tar.xz" -C "$SRC.tmp" --strip-components=1
  mv "$SRC.tmp" "$SRC"
  cd "$SRC"
  ./android-configure patch
  sed -i '1i #if defined(ARMV8_OS_ANDROID)\n#undef ARMV8_OS_ANDROID\n#define ARMV8_OS_LINUX 1\n#endif' deps/zlib/cpu_features.c
  sed -i 's/#define V8_TRAP_HANDLER_SUPPORTED true/#define V8_TRAP_HANDLER_SUPPORTED false/' deps/v8/src/trap-handler/trap-handler.h
fi
cd "$SRC"

case "$ARCH" in
  arm64)  CPU=arm64; PREFIX=aarch64-linux-android; GARCH=arm64 ;;
  x86_64) CPU=x64;   PREFIX=x86_64-linux-android;  GARCH=x64 ;;
  *) echo "架构只能是 arm64 或 x86_64"; exit 1 ;;
esac
export PATH="$PATH:$TC/bin" CC="$TC/bin/${PREFIX}26-clang" CXX="$TC/bin/${PREFIX}26-clang++"
export CC_host=gcc CXX_host=g++ AR_host=ar LINK_host=g++
export GYP_DEFINES="target_arch=$GARCH v8_target_arch=$GARCH android_target_arch=$GARCH host_os=linux OS=android android_ndk_path=$NDK"
[ -f out/Makefile ] || ./configure --dest-cpu=$CPU --dest-os=android --openssl-no-asm --cross-compiling --without-node-snapshot

# 链接 V8 很吃内存：按每个任务 2GB 估并发
MEM_GB=$(( $(awk '/MemTotal/ {print $2}' /proc/meminfo) / 1024 / 1024 ))
JOBS=$(( MEM_GB / 2 )); [ "$JOBS" -lt 1 ] && JOBS=1; [ "$JOBS" -gt "$(nproc)" ] && JOBS=$(nproc)
echo "Node $VER $ARCH：$JOBS 个并发（内存 ${MEM_GB}GB）"
make -C out BUILDTYPE=Release -j"$JOBS" node

O="$W/out/node-android-$ARCH"
mkdir -p "$O"
"$TC/bin/llvm-strip" -o "$O/libnode.so" out/Release/node
echo "$VER" > "$O/version.txt"
ls -la "$O"
