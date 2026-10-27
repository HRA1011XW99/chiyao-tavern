#!/usr/bin/env bash
# Cross-compile git (with https via static OpenSSL + curl) for Android, inside an Ubuntu container:
#   bash build.sh <arm64|x86_64>
# The NDK and sources are cached in /work (a mounted volume). Output goes to /work/out/git-android-<arch>/:
#   libgit.so              the git executable (named lib*.so so the APK installs it into nativeLibraryDir)
#   libgit-remote-http.so  the http(s) transport helper
#   cacert.pem             Mozilla CA bundle for GIT_SSL_CAINFO
set -euo pipefail
ARCH="$1"
NDK_VER=r29
API=26
OPENSSL_VER=3.5.0
CURL_VER=8.14.1
GIT_VER=2.50.0
W=/work
mkdir -p "$W"
cd "$W"

if ! command -v make >/dev/null || ! command -v perl >/dev/null || ! command -v unzip >/dev/null; then
  apt-get update -qq
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq build-essential perl curl xz-utils unzip ca-certificates autoconf >/dev/null
fi

fetch() { # url file
  [ -f "$2" ] && return
  curl -fsSL -o "$2.part" "$1"
  mv "$2.part" "$2"
}

if [ ! -d "android-ndk-$NDK_VER" ]; then
  fetch "https://dl.google.com/android/repository/android-ndk-$NDK_VER-linux.zip" ndk.zip
  rm -rf ndk.tmp && mkdir ndk.tmp && unzip -q ndk.zip -d ndk.tmp
  mv "ndk.tmp/android-ndk-$NDK_VER" . && rm -rf ndk.tmp ndk.zip
fi
NDK="$W/android-ndk-$NDK_VER"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"

case "$ARCH" in
  arm64)  TRIPLE=aarch64-linux-android; OSSL_TARGET=android-arm64 ;;
  x86_64) TRIPLE=x86_64-linux-android;  OSSL_TARGET=android-x86_64 ;;
  *) echo "arch must be arm64 or x86_64"; exit 1 ;;
esac

export ANDROID_NDK_ROOT="$NDK"
export PATH="$TC/bin:$PATH"
export CC="$TC/bin/${TRIPLE}${API}-clang"
export AR="$TC/bin/llvm-ar"
export RANLIB="$TC/bin/llvm-ranlib"
export STRIP="$TC/bin/llvm-strip"

B="$W/git-build-$ARCH"
P="$B/prefix"
mkdir -p "$B" "$P"

fetch "https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VER/openssl-$OPENSSL_VER.tar.gz" "openssl-$OPENSSL_VER.tar.gz"
fetch "https://curl.se/download/curl-$CURL_VER.tar.xz" "curl-$CURL_VER.tar.xz"
fetch "https://www.kernel.org/pub/software/scm/git/git-$GIT_VER.tar.xz" "git-$GIT_VER.tar.xz"
curl -fsSL -o "$B/cacert.pem" https://curl.se/ca/cacert.pem

if [ ! -f "$P/lib/libssl.a" ]; then
  rm -rf "$B/openssl" && mkdir "$B/openssl"
  tar -xzf "openssl-$OPENSSL_VER.tar.gz" -C "$B/openssl" --strip-components=1
  (cd "$B/openssl" && ./Configure "$OSSL_TARGET" no-shared no-tests no-apps --prefix="$P" --libdir=lib \
    && make -j"$(nproc)" >/dev/null && make install_sw >/dev/null)
fi

if [ ! -f "$P/lib/libcurl.a" ]; then
  rm -rf "$B/curl" && mkdir "$B/curl"
  tar -xJf "curl-$CURL_VER.tar.xz" -C "$B/curl" --strip-components=1
  (cd "$B/curl" && ./configure --host="$TRIPLE" --prefix="$P" --disable-shared --enable-static \
      --with-openssl="$P" --with-zlib --without-libpsl --without-brotli --without-zstd --without-nghttp2 \
      --without-libidn2 --disable-ldap --disable-manual --disable-docs --without-ca-bundle --without-ca-path >/dev/null \
    && make -j"$(nproc)" >/dev/null && make install >/dev/null)
fi

# git's Makefile runs recipes with SHELL_PATH, which we point at Android's shell
[ -e /system/bin/sh ] || { mkdir -p /system/bin && ln -s /bin/sh /system/bin/sh; }

rm -rf "$B/git" && mkdir "$B/git"
tar -xJf "git-$GIT_VER.tar.xz" -C "$B/git" --strip-components=1
cd "$B/git"
# bionic has no thread cancellation; git only uses it to hold cancellation off around exec
cat > android-compat.h <<'EOF'
#ifndef PTHREAD_CANCEL_DISABLE
#define PTHREAD_CANCEL_ENABLE 0
#define PTHREAD_CANCEL_DISABLE 1
#define pthread_setcancelstate(state, old) ((old) ? (*(int *)(old) = 0) : 0, 0)
#endif
EOF
cat > config.mak <<EOF
uname_S = Linux
uname_M = $ARCH
CC = $CC
AR = $AR
CFLAGS = -O2 -I$P/include -include $B/git/android-compat.h
LDFLAGS = -L$P/lib
CURL_CONFIG = $P/bin/curl-config
CURL_CFLAGS = -I$P/include
CURL_LDFLAGS = -L$P/lib -lcurl -lssl -lcrypto -lz
OPENSSLDIR = $P
SHELL_PATH = /system/bin/sh
PTHREAD_LIBS =
NO_GETTEXT = YesPlease
NO_TCLTK = YesPlease
NO_PERL = YesPlease
NO_PYTHON = YesPlease
NO_EXPAT = YesPlease
NO_ICONV = YesPlease
NO_REGEX = YesPlease
NO_INSTALL_HARDLINKS = YesPlease
HAVE_DEV_TTY = YesPlease
CSPRNG_METHOD = arc4random
RUNTIME_PREFIX = YesPlease
EOF
make -j"$(nproc)" git git-remote-http >/dev/null

O="$W/out/git-android-$ARCH"
rm -rf "$O" && mkdir -p "$O"
"$STRIP" -o "$O/libgit.so" git
"$STRIP" -o "$O/libgit-remote-http.so" git-remote-http
cp "$B/cacert.pem" "$O/cacert.pem"
echo "$GIT_VER" > "$O/version.txt"
ls -la "$O"
