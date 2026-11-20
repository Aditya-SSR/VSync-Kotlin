#!/usr/bin/env bash
# Builds the Rust scraper bridge for Android and drops the .so files into
# app/src/main/jniLibs. Run from Git Bash:  ./build-rust.sh           (arm64 only)
#                                           ./build-rust.sh all       (every ABI)
# Needs: rustup targets for Android and an NDK under the Android SDK.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-${LOCALAPPDATA:-$HOME/AppData/Local}/Android/Sdk}"
NDK="${ANDROID_NDK_HOME:-$SDK/ndk/29.0.13113456}"
BIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin"
API=24

if [ ! -d "$BIN" ]; then
  echo "NDK toolchain not found at $BIN" >&2
  exit 1
fi

build() {
  local triple="$1" abi="$2" clang="$3"
  local upper
  upper="$(echo "$triple" | tr 'a-z-' 'A-Z_')"
  local under
  under="$(echo "$triple" | tr '-' '_')"
  export "CARGO_TARGET_${upper}_LINKER=$BIN/${clang}${API}-clang.cmd"
  export "CC_${under}=$BIN/${clang}${API}-clang.cmd"
  export "CXX_${under}=$BIN/${clang}${API}-clang++.cmd"
  export "AR_${under}=$BIN/llvm-ar.exe"
  (cd "$HERE/rust-bridge" && cargo build --release --target "$triple")
  mkdir -p "$HERE/app/src/main/jniLibs/$abi"
  cp "$HERE/rust-bridge/target/$triple/release/libvsync_bridge.so" \
     "$HERE/app/src/main/jniLibs/$abi/libvsync_bridge.so"
  echo "built $abi"
}

if [ "${1:-}" != "x86_64" ]; then build aarch64-linux-android arm64-v8a aarch64-linux-android; fi
if [ "${1:-}" = "x86_64" ]; then build x86_64-linux-android x86_64 x86_64-linux-android; fi
if [ "${1:-}" = "all" ]; then
  build armv7-linux-androideabi armeabi-v7a armv7a-linux-androideabi
  build x86_64-linux-android x86_64 x86_64-linux-android
fi
