
cmake_minimum_required(VERSION 3.22.1)

project(kisoti_hear LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Disable unnecessary features for Android
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

# Include Whisper.cpp
add_subdirectory(whisper.cpp)

# Android logging library
find_library(log-lib log)

# Kisoti-Hear native library
add_library(
    kisoti_hear
    SHARED
    native-lib.cpp
)

target_link_libraries(
    kisoti_hear
    PRIVATE
    whisper
    android
    ${log-lib}
)
