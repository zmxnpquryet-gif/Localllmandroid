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

# 16KB page-size devices (Android 15+) refuse to load libs whose LOAD segments are
# only 4KB-aligned; the Play/Samsung compatibility checker flags it. Aligning our own
# lib now is free, and it keeps libsdengine.so out of that warning list.
target_link_options(sdengine PRIVATE "-Wl,-z,max-page-size=16384")
