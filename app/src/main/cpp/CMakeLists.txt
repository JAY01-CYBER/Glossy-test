cmake_minimum_required(VERSION 3.22.1)
project("glossy_audio_engine")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(oboe REQUIRED CONFIG)

# FFmpeg ki prebuilt .so libraries ka path
set(FFMPEG_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

# FFmpeg Libraries ko import karna
add_library(avcodec SHARED IMPORTED)
set_target_properties(avcodec PROPERTIES IMPORTED_LOCATION ${FFMPEG_DIR}/libavcodec.so)

add_library(avformat SHARED IMPORTED)
set_target_properties(avformat PROPERTIES IMPORTED_LOCATION ${FFMPEG_DIR}/libavformat.so)

add_library(avutil SHARED IMPORTED)
set_target_properties(avutil PROPERTIES IMPORTED_LOCATION ${FFMPEG_DIR}/libavutil.so)

add_library(swresample SHARED IMPORTED)
set_target_properties(swresample PROPERTIES IMPORTED_LOCATION ${FFMPEG_DIR}/libswresample.so)

#  main native engine
add_library(glossy_engine SHARED
    glossy_engine.cpp
    GlossyNativePlayer.cpp
)

target_include_directories(glossy_engine PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

# FFmpeg aur system libraries 
target_link_libraries(glossy_engine
    oboe::oboe
    mediandk
    log
    android
    avcodec
    avformat
    avutil
    swresample
)

target_link_options(glossy_engine PRIVATE "-Wl,-z,max-page-size=16384")
