cmake_minimum_required(VERSION 3.22.1)
project("glossy_audio_engine")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(oboe REQUIRED CONFIG)

add_library(glossy_engine SHARED
    glossy_engine.cpp
    GlossyNativePlayer.cpp
)

target_include_directories(glossy_engine PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_link_libraries(glossy_engine
    oboe::oboe
    mediandk
    log
    android
)

target_link_options(glossy_engine PRIVATE "-Wl,-z,max-page-size=16384")
