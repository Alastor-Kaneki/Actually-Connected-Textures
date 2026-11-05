#!/bin/sh
# Build libConnectedTextures.so for arm64-v8a with the Android NDK.
set -e
# NDK: Android NDK folder (r28c). PL: preloader-android's include folder
# (github.com/LiteLDev/preloader-android). CI checks it out into ../.deps.
NDK=${NDK:-${ANDROID_NDK_HOME:-}}
PL=${PL:-../.deps/preloader-android/include}
[ -d "$NDK/toolchains/llvm" ] || { echo "Set NDK to an Android NDK folder (r28c)" >&2; exit 1; }
[ -f "$PL/pl/memory/Hook.hpp" ] || { echo "Set PL to preloader-android's include folder" >&2; exit 1; }
BIN=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
OUT=${OUT:-build}
mkdir -p "$OUT"
CXX="$BIN/clang++ --target=aarch64-linux-android28"
$CXX -std=c++20 -fPIC -shared -O2 -I"$PL" -Wl,-soname,libpreloader.so -o "$OUT/libpreloader.so" stub/preloader_stub.cpp
$CXX -std=c++20 -fPIC -shared -O2 -Wall -Wextra -Wno-unused-parameter \
  -fvisibility=hidden -fvisibility-inlines-hidden -ffunction-sections -fdata-sections \
  -I"$PL" -Isrc src/Mod.cpp -o "$OUT/libConnectedTextures.so" \
  -L"$OUT" -lpreloader -llog -static-libstdc++ \
  -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384 -Wl,--no-undefined -Wl,-soname,libConnectedTextures.so
"$BIN/llvm-strip" --strip-unneeded "$OUT/libConnectedTextures.so"
ls -la "$OUT/libConnectedTextures.so"
