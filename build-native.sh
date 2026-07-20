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

# 拷贝产物到 jniLibs(libllama-jni.so 依赖 libllama.so + libggml.so,三件套缺一不可)
mkdir -p "$JNILIBS_DIR"

# strip 工具(NDK 自带 llvm-strip,可去掉 debug_info 把体积减半)
# NDK prebuilt 目录命名:host-tag 形式 —— macOS=darwin-x86_64,Linux=linux-x86_64
HOST_TAG="$(uname -s | tr '[:upper:]' '[:lower:]')"
case "$HOST_TAG" in
    darwin) HOST_TAG="darwin-x86_64" ;;
    linux)  HOST_TAG="linux-x86_64" ;;
esac
STRIP="$NDK_ROOT/toolchains/llvm/prebuilt/$HOST_TAG/bin/llvm-strip"
if [ ! -x "$STRIP" ]; then
    # 兜底:有些 NDK 版本用 host-tag 而非 host-x86_64(arm64 mac 等)
    STRIP="$(find "$NDK_ROOT/toolchains/llvm/prebuilt" -name llvm-strip -type f 2>/dev/null | head -1)"
fi

# 拷贝并 strip 三个 .so
copy_and_strip() {
    local src="$1"
    local name="$(basename "$src")"
    cp "$src" "$JNILIBS_DIR/$name"
    if [ -x "$STRIP" ]; then
        "$STRIP" --strip-debug "$JNILIBS_DIR/$name" 2>/dev/null || true
    fi
    echo "  $name: $(du -h "$JNILIBS_DIR/$name" | cut -f1)"
}

echo "=== Installing native libs to $JNILIBS_DIR ==="
copy_and_strip "$BUILD_DIR/libllama-jni.so"
copy_and_strip "$BUILD_DIR/_deps/llama_cpp-build/src/libllama.so"
copy_and_strip "$BUILD_DIR/_deps/llama_cpp-build/ggml/src/libggml.so"

echo "=== Done: $JNILIBS_DIR ==="
ls -lh "$JNILIBS_DIR/"
