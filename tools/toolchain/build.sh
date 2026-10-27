#!/usr/bin/env bash
# Assemble the on-phone build toolchain for npm native addons, inside an Ubuntu container:
#   bash build.sh <arm64|x86_64> <Node version, e.g. v24.14.1>
# with tools/tavern-sh mounted at /src. Needs tools/llvm-android/build.sh to have run for the same arch. Output in /work/out/toolchain-<arch>/:
#   jni/            executables and the libraries they need, for nativeLibraryDir:
#                     libllvm.so (clang, clang++, ld.lld, llvm-ar), libpython.so (python3) + libpython3.14.so
#                     + libandroid-support.so, libmake.so, libtavernsh.so (npm's script-shell, tools/tavern-sh)
#   toolchain.zip   files that are only read, unpacked by the app into files/toolchain/:
#                     sysroot/ (NDK headers and libraries for this arch), clang/ (clang's resource dir),
#                     python/lib/python3.14/ (trimmed standard library), node/include/node/ (headers for node-gyp)
# python and make are Termux's builds; the rest comes from the NDK and our own LLVM build.
set -euo pipefail
ARCH="$1"
NODE_VER="$2"
NDK_VER=r29
API=26
PY=3.14
W=/work
cd "$W"

case "$ARCH" in
  arm64)  TRIPLE=aarch64-linux-android; TERMUX=aarch64 ;;
  x86_64) TRIPLE=x86_64-linux-android;  TERMUX=x86_64 ;;
  *) echo "unknown arch $ARCH"; exit 1 ;;
esac

if ! command -v zip >/dev/null || ! command -v dpkg-deb >/dev/null || ! command -v curl >/dev/null; then
  apt-get update -qq
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq curl zip xz-utils ca-certificates dpkg >/dev/null
fi

NDK="$W/android-ndk-$NDK_VER"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
LLVM="$W/out/llvm-android-$ARCH"
[ -f "$LLVM/libllvm.so" ] || { echo "run tools/llvm-android/build.sh $ARCH first"; exit 1; }

# Termux packages: python and its one helper library, and make
T="$W/termux-$ARCH"
mkdir -p "$T"
REPO=https://packages-cf.termux.dev/apt/termux-main
[ -f "$T/Packages" ] || curl -fsSL -o "$T/Packages" "$REPO/dists/stable/main/binary-$TERMUX/Packages"
for pkg in python libandroid-support make; do
  file=$(awk -v p="$pkg" '$1=="Package:"{cur=$2} $1=="Filename:" && cur==p {print $2; exit}' "$T/Packages")
  [ -n "$file" ] || { echo "no $pkg in Termux repo"; exit 1; }
  deb="$T/$(basename "$file")"
  if [ ! -f "$deb" ]; then curl -fsSL -o "$deb.part" "$REPO/$file"; mv "$deb.part" "$deb"; fi
  rm -rf "$T/$pkg" && dpkg-deb -x "$deb" "$T/$pkg"
done
TP=data/data/com.termux/files/usr

OUT="$W/out/toolchain-$ARCH"
rm -rf "$OUT.tmp" && mkdir -p "$OUT.tmp/jni" "$OUT.tmp/files"
J="$OUT.tmp/jni"
F="$OUT.tmp/files"

cp "$LLVM/libllvm.so" "$J/libllvm.so"
cp "$T/python/$TP/bin/python$PY" "$J/libpython.so"
cp "$T/python/$TP/lib/libpython$PY.so" "$J/libpython$PY.so"
cp "$T/libandroid-support/$TP/lib/libandroid-support.so" "$J/libandroid-support.so"
cp "$T/make/$TP/bin/make" "$J/libmake.so"
"$TC/bin/${TRIPLE}${API}-clang" -O2 -s -o "$J/libtavernsh.so" /src/tavern-sh.c

# sysroot: headers (minus other architectures' asm directories) and this arch's libraries for our API level
S="$F/sysroot/usr"
mkdir -p "$S/lib"
cp -r "$TC/sysroot/usr/include" "$S/include"
for other in aarch64-linux-android arm-linux-androideabi i686-linux-android x86_64-linux-android riscv64-linux-android; do
  [ "$other" = "$TRIPLE" ] || rm -rf "$S/include/$other"
done
mkdir -p "$S/lib/$TRIPLE"
cp -r "$TC/sysroot/usr/lib/$TRIPLE/$API" "$S/lib/$TRIPLE/$API"
cp "$TC/sysroot/usr/lib/$TRIPLE/"*.a "$TC/sysroot/usr/lib/$TRIPLE/"*.so "$S/lib/$TRIPLE/" 2>/dev/null || true

cp -r "$LLVM/resource" "$F/clang"
# LLVM builds from before the fix in tools/llvm-android put libunwind one level too high for clang to find
if [ -f "$F/clang/lib/linux/libunwind.a" ]; then
  mkdir -p "$F/clang/lib/linux/${TRIPLE%%-*}"
  mv "$F/clang/lib/linux/libunwind.a" "$F/clang/lib/linux/${TRIPLE%%-*}/"
fi

# Python standard library without what node-gyp never touches
PL="$F/python/lib/python$PY"
mkdir -p "$(dirname "$PL")"
cp -r "$T/python/$TP/lib/python$PY" "$PL"
rm -rf "$PL"/{idlelib,tkinter,turtledemo,ensurepip,pydoc_data,lib2to3,test,unittest/test,config-*} "$PL/site-packages"/*
find "$PL" -name __pycache__ -prune -exec rm -rf {} +
# extension modules that need libraries we do not ship (ssl, sqlite, ffi, curses, ...), and test modules
( cd "$PL/lib-dynload" && rm -f _ssl.* _hashlib.* _sqlite3.* _ctypes* _curses* readline.* _dbm.* _gdbm.* _bz2.* _lzma.* \
    _zstd.* _test* xx* _xxtestfuzz.* _remote_debugging.* )

# Node headers for node-gyp (npm_config_nodedir), so it never downloads them
mkdir -p "$F/node"
[ -f "$W/node-$NODE_VER-headers.tar.gz" ] || curl -fsSL -o "$W/node-$NODE_VER-headers.tar.gz" \
  "https://nodejs.org/dist/$NODE_VER/node-$NODE_VER-headers.tar.gz"
tar -xzf "$W/node-$NODE_VER-headers.tar.gz" -C "$F/node" --strip-components=1

( cd "$F" && zip -qr -X "$OUT.tmp/toolchain.zip" . )
rm -rf "$F"
llvm_ver=$(cat "$LLVM/version.txt")
py_ver=$(awk -v p=python '$1=="Package:"{cur=$2} $1=="Version:" && cur==p {print $2; exit}' "$T/Packages")
echo "llvm $llvm_ver, python $py_ver, node headers $NODE_VER" > "$OUT.tmp/version.txt"
rm -rf "$OUT" && mv "$OUT.tmp" "$OUT"
ls -la "$OUT" "$OUT/jni"
