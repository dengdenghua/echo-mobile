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
