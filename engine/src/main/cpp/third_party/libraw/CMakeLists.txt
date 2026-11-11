# LibRaw 0.22.3 as a static library for the SpektraLab Android engine.
# See VENDORED.md for provenance, the vendored subset and the licence terms.

file(GLOB_RECURSE LIBRAW_SOURCES CONFIGURE_DEPENDS
        "${CMAKE_CURRENT_LIST_DIR}/src/*.cpp")

# LibRaw ships three "*_ph.cpp" placeholder files (preprocessing, postprocessing,
# write) for builds without the processing tools. They define the same symbols
# as the real implementations -- dcraw_process() there just returns
# LIBRAW_NOT_IMPLEMENTED -- so compiling both either fails to link or, worse,
# links the stubs and every RAW "develops" to an error. Keep them out.
list(FILTER LIBRAW_SOURCES EXCLUDE REGEX "_ph\\.cpp$")

add_library(rawlib STATIC ${LIBRAW_SOURCES})

target_include_directories(rawlib PUBLIC ${CMAKE_CURRENT_LIST_DIR})

# Pinned to C++17, the standard LibRaw is written for. The engine's own
# CMAKE_CXX_STANDARD (20) would otherwise apply to this target as well.
set_target_properties(rawlib PROPERTIES
        CXX_STANDARD 17
        CXX_STANDARD_REQUIRED ON
        POSITION_INDEPENDENT_CODE ON)

# LIBRAW_NODLL: never emit/expect __declspec on Windows host builds. PUBLIC,
# because the adapter that includes libraw.h must see the same declarations.
# Deliberately not defined: USE_JPEG, USE_LCMS, USE_LCMS2, USE_DNGSDK,
# USE_RAWSPEED, USE_RAWSPEED3 - none of those dependencies are shipped.
target_compile_definitions(rawlib PUBLIC LIBRAW_NODLL)

if (MSVC)
    target_compile_options(rawlib PRIVATE /O2 /EHsc /utf-8 /W0
            /D_CRT_SECURE_NO_WARNINGS /D_CRT_NONSTDC_NO_DEPRECATE)
else()
    # Same numeric policy as the film engine: no fast-math, no reassociation.
    target_compile_options(rawlib PRIVATE -O2 -fno-fast-math -fexceptions
            -fvisibility=hidden -w)
endif()

if (ANDROID)
    target_link_libraries(rawlib PUBLIC log)
endif()
