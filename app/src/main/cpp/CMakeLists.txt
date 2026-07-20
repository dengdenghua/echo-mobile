# llama.cpp JNI 桥接 —— 让 Octopus Mobile 在设备端跑 GGUF 量化模型。
#
# 对应 Kotlin 声明: LlamaJni.kt
# 构建方式见 build-native.sh

cmake_minimum_required(VERSION 3.22.1)
project(llama-jni LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ── 拉取 llama.cpp 上游源码 ──
# 方式 1:git submodule(推荐)
#   git submodule add https://github.com/ggerganov/llama.cpp.git app/src/main/cpp/llama.cpp
# 方式 2:FetchContent(首次构建自动拉取)
include(FetchContent)
FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
    GIT_TAG        b3600
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(llama_cpp)

# ── JNI 桥接源码 ──
add_library(llama-jni SHARED
    llama-jni.cpp
)

# ── 链接 llama.cpp + JNI + log ──
find_library(log-lib log)

target_link_libraries(llama-jni
    llama
    ${log-lib}
)

# ── JNI 头文件 ──
find_package(Java REQUIRED)
find_package(JNI REQUIRED)
target_include_directories(llama-jni PRIVATE
    ${JNI_INCLUDE_DIRS}
    ${llama_cpp_SOURCE_DIR}/include
    ${llama_cpp_SOURCE_DIR}/ggml/include
)

# ════════════════════════════════════════════════════════════════════
# MNN JNI 桥接(可选,由 BUILD_MNN_JNI 开关控制)
# ════════════════════════════════════════════════════════════════════
# 对应 Kotlin 声明: MnnJni.kt
# 构建方式见 build-mnn.sh(传 -DBUILD_MNN_JNI=ON)
#
# 默认 OFF —— 这样 build-native.sh(llama.cpp 专用)不受影响。
# 只有 build-mnn.sh 会开启,拉取 MNN 上游 + 编译 mnn-jni.cpp。
option(BUILD_MNN_JNI "Build MNN JNI bridge (LLM + Whisper)" OFF)

if(BUILD_MNN_JNI)

# ── 拉取 MNN 上游源码 ──
include(FetchContent)
FetchContent_Declare(
    mnn
    GIT_REPOSITORY https://github.com/alibaba/MNN.git
    GIT_TAG        3.1.1
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(mnn)

# MNN 构建选项(由 build-mnn.sh 通过 -D 传入)
option(MNN_VULKAN      "Enable Vulkan backend"     ON)
option(MNN_OPENCL      "Enable OpenCL backend"     ON)
option(MNN_ARM82       "Enable ARM8.2 FP16"        ON)
option(MNN_KLEIDIAI    "Enable KleidiAI"           ON)
option(MNN_BUILD_LLM   "Build MNN LLM"             ON)
option(MNN_BUILD_WHISPER "Build MNN Whisper"       ON)

# ── mnn-jni 桥接库 ──
add_library(mnn-jni SHARED
    mnn-jni.cpp
)

find_library(log-lib log)

target_link_libraries(mnn-jni
    MNN
    MNN_Express
    ${log-lib}
)

# MNN LLM + Whisper 子模块(若启用)
if(MNN_BUILD_LLM)
    target_link_libraries(mnn-jni llm)
    target_include_directories(mnn-jni PRIVATE
        ${mnn_SOURCE_DIR}/llm/head
        ${mnn_SOURCE_DIR}/llm/src
    )
endif()

if(MNN_BUILD_WHISPER)
    target_link_libraries(mnn-jni whisper)
    target_include_directories(mnn-jni PRIVATE
        ${mnn_SOURCE_DIR}/whisper
    )
endif()

# Vulkan 后端(可选)
if(MNN_VULKAN)
    target_link_libraries(mnn-jni MNN_Vulkan)
endif()

# OpenCL 后端(可选)
if(MNN_OPENCL)
    target_link_libraries(mnn-jni MNN_CL)
endif()

target_include_directories(mnn-jni PRIVATE
    ${JNI_INCLUDE_DIRS}
    ${mnn_SOURCE_DIR}/include
)

endif()  # BUILD_MNN_JNI
