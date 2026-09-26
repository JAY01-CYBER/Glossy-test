cmake_minimum_required(VERSION 3.22.1)
project("glossy_audio_engine")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

find_package(oboe REQUIRED CONFIG)

add_library(glossy_engine SHARED
    glossy_engine.cpp
)

target_compile_options(glossy_engine PRIVATE
    -Wall -Wextra -Wpedantic
)

target_link_libraries(glossy_engine
    oboe::oboe
    mediandk
    log
    android
)

target_link_options(glossy_engine PRIVATE "-Wl,-z,max-page-size=16384")
