cmake_minimum_required(VERSION 3.22.1)
project(youmo-handwriting LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# unpacked by the module's unpackOnnxruntime task
if(NOT EXISTS "${ORT_DIR}/jni/${ANDROID_ABI}/libonnxruntime.so")
  message(FATAL_ERROR "no onnxruntime for ${ANDROID_ABI} in ${ORT_DIR}")
endif()

add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES
  IMPORTED_LOCATION "${ORT_DIR}/jni/${ANDROID_ABI}/libonnxruntime.so"
  INTERFACE_INCLUDE_DIRECTORIES "${ORT_DIR}/headers")

add_library(youmo-handwriting SHARED ort-jni.cpp)
target_link_libraries(youmo-handwriting PRIVATE onnxruntime android log)
target_compile_options(youmo-handwriting PRIVATE -Wall -Wextra -Werror)
# 16 KB pages (Android 15+)
target_link_options(youmo-handwriting PRIVATE "-Wl,-z,max-page-size=16384")
