

cmake_minimum_required(VERSION 3.22.1)

project("kisoti_hear")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Whisper.cpp library
add_subdirectory(whisper.cpp)

# Kisoti-Hear native library
add_library(
    kisoti_hear
    SHARED
    native-lib.cpp
)

target_link_libraries(
    kisoti_hear
    whisper
    android
    log
)
