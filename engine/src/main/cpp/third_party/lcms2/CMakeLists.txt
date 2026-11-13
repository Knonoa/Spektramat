# Little CMS 2.17, MIT. Source files are unmodified; see VENDORED.md.
file(GLOB LCMS_SOURCES CONFIGURE_DEPENDS "${CMAKE_CURRENT_LIST_DIR}/src/*.c")
add_library(spektra_lcms STATIC ${LCMS_SOURCES})
target_include_directories(spektra_lcms PUBLIC ${CMAKE_CURRENT_LIST_DIR}/include)
set_target_properties(spektra_lcms PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_compile_definitions(spektra_lcms PRIVATE CMS_NO_REGISTER_KEYWORD)
if(NOT MSVC)
    target_compile_options(spektra_lcms PRIVATE -O2 -fno-fast-math -fvisibility=hidden)
endif()
