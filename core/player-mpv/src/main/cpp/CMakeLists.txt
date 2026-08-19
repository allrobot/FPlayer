cmake_minimum_required(VERSION 3.22)
project(fplayer_mpv LANGUAGES CXX)

if(NOT FPLAYER_NATIVE_ROOT)
    message(FATAL_ERROR "FPLAYER_NATIVE_ROOT must point to the verified T02 output")
endif()

set(NATIVE_ROOT "${FPLAYER_NATIVE_ROOT}")
set(MPV_INCLUDE "${NATIVE_ROOT}/include")
set(MPV_LIBRARY "${NATIVE_ROOT}/lib/libmpv.so")
set(AVCODEC_LIBRARY "${NATIVE_ROOT}/lib/libavcodec.so")

if(NOT EXISTS "${MPV_LIBRARY}")
    message(FATAL_ERROR "Missing verified libmpv.so at ${MPV_LIBRARY}")
endif()

add_library(mpv SHARED IMPORTED)
set_target_properties(mpv PROPERTIES
    IMPORTED_LOCATION "${MPV_LIBRARY}"
    INTERFACE_INCLUDE_DIRECTORIES "${MPV_INCLUDE}"
)

add_library(avcodec SHARED IMPORTED)
set_target_properties(avcodec PROPERTIES IMPORTED_LOCATION "${AVCODEC_LIBRARY}")

add_library(fplayer_mpv SHARED mpv_jni.cpp)
target_compile_features(fplayer_mpv PRIVATE cxx_std_17)
target_include_directories(fplayer_mpv PRIVATE "${MPV_INCLUDE}")
target_compile_options(fplayer_mpv PRIVATE -fvisibility=hidden)
target_link_libraries(fplayer_mpv PRIVATE mpv avcodec log android)
