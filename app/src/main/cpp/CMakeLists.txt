cmake_minimum_required(VERSION 3.26)

project(solin_zvec_bridge LANGUAGES CXX)

set(ZVEC_PREBUILT_DIR "" CACHE PATH "Directory containing zvec Android prebuilt libraries")
set(ZVEC_INCLUDE_DIR "" CACHE PATH "Directory containing zvec C API headers")
set(ZVEC_PREBUILT_LIBRARY "${ZVEC_PREBUILT_DIR}/${ANDROID_ABI}/libzvec_c_api.so")

if(NOT EXISTS "${ZVEC_PREBUILT_LIBRARY}")
    message(FATAL_ERROR "Missing zvec prebuilt library: ${ZVEC_PREBUILT_LIBRARY}")
endif()

add_library(zvec SHARED IMPORTED GLOBAL)
set_target_properties(zvec PROPERTIES IMPORTED_LOCATION "${ZVEC_PREBUILT_LIBRARY}")

add_library(solin_zvec_bridge SHARED zvec_native_store.cpp)

target_compile_features(solin_zvec_bridge PRIVATE cxx_std_17)
target_compile_options(solin_zvec_bridge PRIVATE -Wall -Werror=return-type)
target_include_directories(solin_zvec_bridge PRIVATE "${ZVEC_INCLUDE_DIR}")
target_link_libraries(solin_zvec_bridge PRIVATE zvec log)
