cmake_minimum_required(VERSION 3.22)
project(photomark LANGUAGES C CXX)
include(FetchContent)
FetchContent_Declare(libraw
    URL https://www.libraw.org/data/LibRaw-0.22.0.tar.gz
    URL_HASH SHA256=1071e6e8011593c366ffdadc3d3513f57c90202d526e133174945ec1dd53f2a1
)
FetchContent_GetProperties(libraw)
if(NOT libraw_POPULATED)
    FetchContent_Populate(libraw)
endif()

file(READ "${libraw_SOURCE_DIR}/Makefile.am" manifest)
string(REGEX MATCH "lib_libraw_a_SOURCES = [^=]+lib_libraw_r_a_CXXFLAGS" library_sources "${manifest}")
string(REGEX MATCHALL "src/[a-zA-Z0-9_/.]+\\.cpp" library_sources "${library_sources}")
list(TRANSFORM library_sources PREPEND "${libraw_SOURCE_DIR}/")
add_library(raw SHARED ${library_sources})
target_include_directories(raw PUBLIC "${libraw_SOURCE_DIR}")
target_compile_features(raw PRIVATE cxx_std_17)
target_compile_definitions(raw PRIVATE LIBRAW_BUILDLIB)
target_compile_options(raw PRIVATE -fexceptions)

add_library(rawbridge SHARED raw_bridge.cpp)
target_compile_features(rawbridge PRIVATE cxx_std_17)
target_link_libraries(rawbridge PRIVATE raw jnigraphics)

set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(ENABLE_SDL OFF CACHE BOOL "" FORCE)
set(ENABLE_DECODER OFF CACHE BOOL "" FORCE)
set(ENABLE_ENCODER OFF CACHE BOOL "" FORCE)
FetchContent_Declare(de265
    URL https://codeload.github.com/strukturag/libde265/tar.gz/refs/tags/v1.0.16
    URL_HASH SHA256=ed12c931759c1575848832f70db5071a001ac813db4e4f568ee08aef6e234d4e
)
FetchContent_MakeAvailable(de265)
set_target_properties(de265 PROPERTIES VERSION "" SOVERSION "")
set(LIBDE265_LIBRARY de265 CACHE STRING "" FORCE)
set(LIBDE265_INCLUDE_DIR "${de265_SOURCE_DIR}" CACHE PATH "" FORCE)
target_include_directories(de265 INTERFACE "$<BUILD_INTERFACE:${de265_SOURCE_DIR}>" "$<BUILD_INTERFACE:${de265_BINARY_DIR}>")

set(WITH_LIBDE265 ON)
set(WITH_X265 OFF)
set(WITH_AOM_DECODER OFF)
set(WITH_AOM_ENCODER OFF)
set(WITH_OpenH264_DECODER OFF)
set(WITH_EXAMPLES OFF)
set(WITH_GDK_PIXBUF OFF)
set(WITH_LIBSHARPYUV OFF)
set(ENABLE_PLUGIN_LOADING OFF)
set(BUILD_TESTING OFF)
FetchContent_Declare(heif
    URL https://codeload.github.com/strukturag/libheif/tar.gz/refs/tags/v1.20.2
    URL_HASH SHA256=b70340395d84184bb8dfc833dd51c95ae049435f7ff9abc7b505a08b5ee2bd2a
)
FetchContent_MakeAvailable(heif)
set_target_properties(heif PROPERTIES VERSION "" SOVERSION "")

set(tiff-tools OFF CACHE BOOL "" FORCE)
set(tiff-tests OFF CACHE BOOL "" FORCE)
set(tiff-contrib OFF CACHE BOOL "" FORCE)
set(tiff-docs OFF CACHE BOOL "" FORCE)
set(tiff-cxx OFF CACHE BOOL "" FORCE)
set(tiff-install OFF CACHE BOOL "" FORCE)
set(ld-version-script OFF CACHE BOOL "" FORCE)
FetchContent_Declare(tiff
    URL https://download.osgeo.org/libtiff/tiff-4.7.1.tar.xz
    URL_HASH SHA256=b92017489bdc1db3a4c97191aa4b75366673cb746de0dce5d7a749d5954681ba
)
FetchContent_MakeAvailable(tiff)
set_target_properties(tiff PROPERTIES VERSION "" SOVERSION "")

FetchContent_Declare(lcms
    URL https://codeload.github.com/mm2/Little-CMS/tar.gz/refs/tags/lcms2.17
    URL_HASH SHA256=6e6f6411db50e85ae8ff7777f01b2da0614aac13b7b9fcbea66dc56a1bc71418
)
FetchContent_GetProperties(lcms)
if(NOT lcms_POPULATED)
    FetchContent_Populate(lcms)
endif()
file(GLOB lcms_sources "${lcms_SOURCE_DIR}/src/cms*.c")
add_library(lcms STATIC ${lcms_sources})
target_include_directories(lcms PUBLIC "${lcms_SOURCE_DIR}/include")
set_target_properties(lcms PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(rasterbridge SHARED raster_bridge.cpp)
target_compile_features(rasterbridge PRIVATE cxx_std_17)
target_include_directories(rasterbridge PRIVATE "${heif_SOURCE_DIR}/libheif/api" "${heif_BINARY_DIR}")
foreach(codec IN ITEMS raw rawbridge de265 heif tiff lcms rasterbridge)
    target_compile_options(${codec} PRIVATE $<$<CONFIG:Debug>:-O2>)
endforeach()
target_link_libraries(rasterbridge PRIVATE heif tiff lcms jnigraphics log)
