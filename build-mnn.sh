#!/usr/bin/env bash
# build-mnn.sh —— 编译 MNN JNI 桥接,产出 libmnn-jni.so(+ 依赖的 libMNN*.so)
#
# MNN 是阿里巴巴开源的轻量级推理引擎,移动端优化好,支持 LLM + Whisper。
# 仓库:https://github.com/alibaba/MNN
#
# 前置:
#   - Android NDK r25+
#   - CMake 3.22+
#   - Android SDK(设置 ANDROID_HOME 环境变量)
#   - ninja
#
# 用法:
#   ./build-mnn.sh            # 编译 arm64-v8a(默认,带 Vulkan)
#   ./build-mnn.sh x86_64     # 编译 x86_64(模拟器,无 Vulkan)
#   ./build-mnn.sh arm64-v8a novulkan  # 禁用 Vulkan(老设备兜底)
#
# 产出:
#   app/src/main/jniLibs/<abi>/libMNN.so
#   app/src/main/jniLibs/<abi>/libMNN_Express.so
#   app/src/main/jniLibs/<abi>/libMNN_Vulkan.so  (若启用 Vulkan)
#   app/src/main/jniLibs/<abi>/libmnn-jni.so
#
# 注意:首次构建会 git clone MNN 上游(~150MB),耗时 5-10 分钟。
#       后续增量构建 1-2 分钟。MNN 默认带 OpenCL,移动端 GPU 加速效果显著。

set -euo pipefail

ABI="${1:-arm64-v8a}"
ENABLE_VULKAN="${2:-vulkan}"
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

# 判断是否启用 Vulkan
VULKAN_FLAG="ON"
if [ "$ENABLE_VULKAN" = "novulkan" ] || [ "$ABI" = "x86_64" ]; then
    VULKAN_FLAG="OFF"
    echo "Vulkan disabled (x86_64 emulator or explicit novulkan)"
fi

echo "=== Building MNN-jni for $ABI (Vulkan=$VULKAN_FLAG) ==="
echo "NDK: $NDK_ROOT"
echo "CPP: $CPP_DIR"

BUILD_DIR="$SCRIPT_DIR/app/.cxx/mnn-${ABI}-release"
mkdir -p "$BUILD_DIR"

cmake \
    -S "$CPP_DIR" \
    -B "$BUILD_DIR" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK_ROOT/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM=android-26 \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_MAKE_PROGRAM="$(which ninja)" \
    -DBUILD_MNN_JNI=ON \
    -DMNN_VULKAN="$VULKAN_FLAG" \
    -DMNN_OPENCL=ON \
    -DMNN_ARM82=ON \
    -DMNN_KLEIDIAI=ON \
    -DMNN_BUILD_LLM=ON \
    -DMNN_BUILD_WHISPER=ON \
    -DMNN_SUPPORT_TRANSFORMERS_FFI=OFF \
    -G Ninja

cmake --build "$BUILD_DIR" --config Release --parallel

# 拷贝产物到 jniLibs
mkdir -p "$JNILIBS_DIR"

# strip 工具
HOST_TAG="$(uname -s | tr '[:upper:]' '[:lower:]')"
case "$HOST_TAG" in
    darwin) HOST_TAG="darwin-x86_64" ;;
    linux)  HOST_TAG="linux-x86_64" ;;
esac
STRIP="$NDK_ROOT/toolchains/llvm/prebuilt/$HOST_TAG/bin/llvm-strip"
if [ ! -x "$STRIP" ]; then
    STRIP="$(find "$NDK_ROOT/toolchains/llvm/prebuilt" -name llvm-strip -type f 2>/dev/null | head -1)"
fi

copy_and_strip() {
    local src="$1"
    local name="$(basename "$src")"
    if [ ! -f "$src" ]; then
        echo "  WARN: $name not found at $src (skipped)"
        return
    fi
    cp "$src" "$JNILIBS_DIR/$name"
    if [ -x "$STRIP" ]; then
        "$STRIP" --strip-debug "$JNILIBS_DIR/$name" 2>/dev/null || true
    fi
    echo "  $name: $(du -h "$JNILIBS_DIR/$name" | cut -f1)"
}

echo "=== Installing native libs to $JNILIBS_DIR ==="
# MNN 核心库
copy_and_strip "$BUILD_DIR/libMNN.so"
copy_and_strip "$BUILD_DIR/libMNN_Express.so"
# Vulkan 后端(可选)
if [ "$VULKAN_FLAG" = "ON" ]; then
    copy_and_strip "$BUILD_DIR/libMNN_Vulkan.so"
fi
# OpenCL 后端(移动端 GPU 主力)
copy_and_strip "$BUILD_DIR/libMNN_CL.so"
# JNI 桥接库
copy_and_strip "$BUILD_DIR/libmnn-jni.so"

# c++_shared.so(因为用 c++_shared STL,MNN 与 jni 共享同一份)
SHARED_STL="$(find "$NDK_ROOT/toolchains/llvm/prebuilt/$HOST_TAG/sysroot/usr/lib" -name libc++_shared.so -path "*$ABI*" | head -1)"
if [ -n "$SHARED_STL" ]; then
    cp "$SHARED_STL" "$JNILIBS_DIR/libc++_shared.so"
    if [ -x "$STRIP" ]; then
        "$STRIP" --strip-debug "$JNILIBS_DIR/libc++_shared.so" 2>/dev/null || true
    fi
    echo "  libc++_shared.so: $(du -h "$JNILIBS_DIR/libc++_shared.so" | cut -f1)"
fi

echo "=== Done: $JNILIBS_DIR ==="
ls -lh "$JNILIBS_DIR/"

echo ""
echo "MNN native 构建完成。"
echo "下一步:重新构建 APK(./gradlew :app:assembleDebug),Kotlin 层会通过 MnnJni.ensureLoaded() 自动加载这些 .so。"
echo ""
echo "若需下载 MNN 预置模型(端侧 LLM/Whisper),访问:"
echo "  - LLM:    https://github.com/alibaba/MNN/blob/master/llm/android/README.md"
echo "  - Whisper:https://github.com/alibaba/MNN/tree/main/whisper"
