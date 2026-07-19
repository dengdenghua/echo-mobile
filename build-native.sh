#!/usr/bin/env bash
# build-native.sh —— 编译 llama.cpp JNI 桥接,产出 libllama-jni.so
#
# 前置:
#   - Android NDK r25+
#   - CMake 3.22+
#   - Android SDK(设置 ANDROID_HOME 环境变量)
#
# 用法:
#   ./build-native.sh            # 编译 arm64-v8a(默认)
#   ./build-native.sh x86_64     # 编译 x86_64(模拟器)
#
# 产出:
#   app/src/main/jniLibs/<abi>/libllama-jni.so
#
# 注意:首次构建会 git clone llama.cpp 上游(~200MB),耗时 5-15 分钟。
#       后续增量构建 1-2 分钟。

set -euo pipefail

ABI="${1:-arm64-v8a}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CPP_DIR="$SCRIPT_DIR/app/src/main/cpp"
JNILIBS_DIR="$SCRIPT_DIR/app/src/main/jniLibs/$ABI"
NDK_VERSION="${NDK_VERSION:-27.0.12077973}"
NDK_ROOT="${NDK_ROOT:-$ANDROID_HOME/ndk/$NDK_VERSION}"

if [ ! -d "$NDK_ROOT" ]; then
    echo "ERROR: NDK not found at $NDK_ROOT"
    echo "Set NDK_ROOT or ANDROID_HOME environment variable."
    echo "Install NDK via: sdkmanager 'ndk;27.0.12077973'"
    exit 1
fi

echo "=== Building llama-jni for $ABI ==="
echo "NDK: $NDK_ROOT"
echo "CPP: $CPP_DIR"

BUILD_DIR="$SCRIPT_DIR/app/.cxx/${ABI}-release"
mkdir -p "$BUILD_DIR"

cmake \
    -S "$CPP_DIR" \
    -B "$BUILD_DIR" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK_ROOT/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM=android-26 \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_MAKE_PROGRAM="$(which ninja)" \
    -G Ninja

cmake --build "$BUILD_DIR" --config Release --parallel

# 拷贝产物到 jniLibs
mkdir -p "$JNILIBS_DIR"
cp "$BUILD_DIR/libllama-jni.so" "$JNILIBS_DIR/"

echo "=== Done: $JNILIBS_DIR/libllama-jni.so ==="
ls -lh "$JNILIBS_DIR/libllama-jni.so"
