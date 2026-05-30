cmake_minimum_required(VERSION 3.22.1)
project("juicejni" LANGUAGES C CXX)

set(CMAKE_C_STANDARD   11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Build libjuice as a sub-project (static, no nettle on Android).
set(BUILD_SHARED_LIBS OFF       CACHE BOOL "" FORCE)
set(USE_NETTLE        OFF       CACHE BOOL "" FORCE)
set(NO_SERVER         ON        CACHE BOOL "" FORCE)
set(NO_TESTS          ON        CACHE BOOL "" FORCE)
set(DISABLE_CONSENT_FRESHNESS ON CACHE BOOL "" FORCE)
add_subdirectory(libjuice)

# Our JNI wrapper, linked statically against libjuice.
add_library(juicejni SHARED juice_jni.cpp)
target_include_directories(juicejni PRIVATE libjuice/include)
target_link_libraries(juicejni juice-static log android)
