cmake_minimum_required(VERSION 3.22.1)
project(latent CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# The film model is the validated spektrafilm port: spektra_engine.cpp,
# overscan, cam16 and filters are the files tools/parity measured against the
# Python reference. spektra_engine.cpp is compiled through lt_tables.cpp, which
# includes it unchanged in order to read its tables (see that file).
#
# RAW input: the in-tree TIFF/DNG reader first, LibRaw (static) for what that
# reader does not implement; the order is decided in engine_image.cpp.
add_subdirectory(third_party/libraw)

add_library(latent SHARED
        lt_tables.cpp
        lt_fast.cpp
        lt_jni.cpp
        overscan.cpp
        filters.cpp
        cam16.cpp
        engine_image.cpp
        raw_decode.cpp
        raw_libraw.cpp
        dng_opcodes.cpp
        basic_adjust.cpp)

target_include_directories(latent PRIVATE
        ${CMAKE_CURRENT_LIST_DIR}
        ${CMAKE_CURRENT_LIST_DIR}/../shared/shaders)

target_link_libraries(latent
        rawlib
        android
        jnigraphics
        log)

# match spektrafilm's numerics: no fast-math reassociation of the colour math
target_compile_options(latent PRIVATE -O2 -fno-fast-math -fvisibility=hidden)
