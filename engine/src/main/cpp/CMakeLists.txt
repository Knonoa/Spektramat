cmake_minimum_required(VERSION 3.22.1)
project(latent C CXX)

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
add_subdirectory(third_party/lcms2)

# TIFF scanline/tile decoding preserves integer and IEEE-float sample precision.
# Do not use the 8-bit RGBA convenience API. Only dependency-free codecs and
# platform zlib are enabled; unsupported codecs fail instead of quantizing.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
foreach(feature tiff-tools tiff-tests tiff-contrib tiff-docs tiff-install cxx
        jpeg old-jpeg jbig lerc lzma zstd webp libdeflate pixarlog)
    set(${feature} OFF CACHE BOOL "" FORCE)
endforeach()
find_package(ZLIB REQUIRED)
set(zlib ON CACHE BOOL "" FORCE)
add_subdirectory(third_party/libtiff)
set_target_properties(tiff PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(latent SHARED
        lt_tables.cpp
        lt_fast.cpp
        lt_jni.cpp
        overscan.cpp
        filters.cpp
        cam16.cpp
        engine_image.cpp
        raster_precision.cpp
        raw_decode.cpp
        raw_libraw.cpp
        dng_opcodes.cpp
        basic_adjust.cpp)

target_include_directories(latent PRIVATE
        ${CMAKE_CURRENT_LIST_DIR}
        ${CMAKE_CURRENT_LIST_DIR}/../shared/shaders)

target_link_libraries(latent
        rawlib
        spektra_lcms
        TIFF::tiff
        ZLIB::ZLIB
        android
        jnigraphics
        log)

# match spektrafilm's numerics: no fast-math reassociation of the colour math
target_compile_options(latent PRIVATE -O2 -fno-fast-math -fvisibility=hidden)
