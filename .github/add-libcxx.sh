#!/usr/bin/env bash
# DJL's Android tokenizer (libdjl_tokenizer.so) links against the NDK's shared C++ runtime,
# which apps must ship themselves. Copy it from the runner's NDK into jniLibs.
set -euo pipefail
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-}}"
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then NDK="$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -1)"; fi
echo "Using NDK at $NDK"
LIB="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib"
for pair in "arm64-v8a:aarch64-linux-android" "x86_64:x86_64-linux-android"; do
  abi="${pair%%:*}"; triple="${pair##*:}"
  mkdir -p "app/src/main/jniLibs/$abi"
  cp "$LIB/$triple/libc++_shared.so" "app/src/main/jniLibs/$abi/"
  ls -la "app/src/main/jniLibs/$abi/libc++_shared.so"
done
