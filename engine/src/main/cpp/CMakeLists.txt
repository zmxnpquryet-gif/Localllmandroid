# SDengine native core (TEST BUILD).
# Portable C++ first (this file); ARM NEON kernels land next to it as
# arch-specific sources guarded by ANDROID_ABI. No hand-written ASM yet.
cmake_minimum_required(VERSION 3.22.1)

project("sdengine")

add_library(
    sdengine
    SHARED
    native-lib.cpp
)

target_compile_features(sdengine PRIVATE cxx_std_17)

find_library(log-lib log)

target_link_libraries(
    sdengine
    ${log-lib}
)
