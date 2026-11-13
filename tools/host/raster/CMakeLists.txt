cmake_minimum_required(VERSION 3.22)
project(raster_precision_contract C CXX)
set(CMAKE_CXX_STANDARD 20)
get_filename_component(SPEKTRA_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../.." ABSOLUTE)
set(CPP "${SPEKTRA_ROOT}/engine/src/main/cpp")

# A host zlib makes this test reproducible on Windows without a package manager.
# This dependency stays under ignored build/; Android uses the platform zlib.
include(FetchContent)
FetchContent_Declare(host_zlib
    URL https://codeload.github.com/madler/zlib/tar.gz/refs/tags/v1.3.1
    URL_HASH SHA256=17e88863f3600672ab49182f217281b6fc4d3c762bde361935e436a95214d05c)
FetchContent_MakeAvailable(host_zlib)
set(ZLIB_INCLUDE_DIR "${host_zlib_SOURCE_DIR};${host_zlib_BINARY_DIR}" CACHE STRING "" FORCE)
set(ZLIB_LIBRARY "${host_zlib_BINARY_DIR}/Release/zlibstatic.lib" CACHE STRING "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
foreach(feature tiff-tools tiff-tests tiff-contrib tiff-docs tiff-install cxx
    jpeg old-jpeg jbig lerc lzma zstd webp libdeflate pixarlog)
    set(${feature} OFF CACHE BOOL "" FORCE)
endforeach()
set(zlib ON CACHE BOOL "" FORCE)
add_subdirectory(${CPP}/third_party/libtiff tiff)
add_subdirectory(${CPP}/third_party/lcms2 lcms)
add_subdirectory(${CPP}/third_party/libraw raw)
add_executable(raster-test
    ${CPP}/raster_precision.cpp ${CPP}/engine_image.cpp ${CPP}/raw_decode.cpp
    ${CPP}/raw_libraw.cpp ${CPP}/dng_opcodes.cpp
    ${CMAKE_CURRENT_LIST_DIR}/../raster_precision_contract.cpp)
target_include_directories(raster-test PRIVATE ${CPP} ${ZLIB_INCLUDE_DIR})
target_link_libraries(raster-test PRIVATE rawlib spektra_lcms tiff zlibstatic)
target_compile_definitions(raster-test PRIVATE _CRT_SECURE_NO_WARNINGS)
if(MSVC)
    target_compile_options(raster-test PRIVATE /utf-8)
endif()

add_executable(raw-precision-test
    ${CPP}/raster_precision.cpp ${CPP}/engine_image.cpp ${CPP}/raw_decode.cpp
    ${CPP}/raw_libraw.cpp ${CPP}/dng_opcodes.cpp
    ${CMAKE_CURRENT_LIST_DIR}/../raw_precision_contract.cpp)
target_include_directories(raw-precision-test PRIVATE ${CPP} ${ZLIB_INCLUDE_DIR})
target_link_libraries(raw-precision-test PRIVATE rawlib spektra_lcms tiff zlibstatic)
target_compile_definitions(raw-precision-test PRIVATE _CRT_SECURE_NO_WARNINGS)
if(MSVC)
    target_compile_options(raw-precision-test PRIVATE /utf-8)
endif()
