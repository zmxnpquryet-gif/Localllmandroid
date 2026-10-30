# JNI bridge to the upstream llama.cpp runtime.
#
# llama.cpp is vendored as a git submodule at ./llama.cpp (pinned to an upstream
# commit that understands the LFM3 architecture family). It is built as a static
# library and linked into libllama_jni.so, so the app ships exactly one native
# inference library per ABI.
cmake_minimum_required(VERSION 3.22.1)

project("llama_jni" C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Static build: no extra .so files to package or load.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# We only need the inference core plus libmtmd (vision/audio projector support).
# CLI tools, tests, server and the unified binary are not part of the Android app.
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD     ON  CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL        OFF CACHE BOOL "" FORCE)

set(GGML_NATIVE          OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE       OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL      OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI    OFF CACHE BOOL "" FORCE)
set(MTMD_VIDEO           OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp build-llama)

add_library(llama_jni SHARED llama_jni.cpp)

target_include_directories(llama_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/include
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/tools/mtmd
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/ggml/include
)

target_link_libraries(llama_jni
    llama
    mtmd
    android
    log
)

target_compile_features(llama_jni PRIVATE cxx_std_17)

# Android 15+ devices with 16 KB pages refuse to load libraries whose LOAD
# segments are only 4 KB aligned.
target_link_options(llama_jni PRIVATE "-Wl,-z,max-page-size=16384")
