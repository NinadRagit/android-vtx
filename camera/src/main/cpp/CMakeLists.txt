cmake_minimum_required(VERSION 3.22.1)
project("camera")

# Use C++ 17
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED TRUE)

# Configure Opus to only build the static library
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
add_subdirectory(opus)

# Find Oboe from Prefab
find_package(oboe REQUIRED CONFIG)

# Add the camera native library
add_library(camera_native SHARED
        jni/camera_native_jni.cpp
        video/CameraStreamerNative.cpp
        audio/AudioStreamerNative.cpp)

# Find necessary Android libraries
find_library(log-lib log)
find_library(android-lib android)

# Specific to NDK AMediaCodec and ACamera2
find_library(camera2ndk-lib camera2ndk)
find_library(mediandk-lib mediandk)

# Link everything together
target_link_libraries(camera_native
        ${log-lib}
        ${android-lib}
        ${camera2ndk-lib}
        ${mediandk-lib}
        oboe::oboe
        opus
)
