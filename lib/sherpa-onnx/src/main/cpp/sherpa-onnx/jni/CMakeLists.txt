include_directories(${PROJECT_SOURCE_DIR})

if(NOT DEFINED ANDROID_ABI)
  if(NOT DEFINED ENV{JAVA_HOME})
    message(FATAL_ERROR "Please set the environment variable JAVA_HOME")
  endif()
  include_directories($ENV{JAVA_HOME}/include)
  include_directories($ENV{JAVA_HOME}/include/linux)
  include_directories($ENV{JAVA_HOME}/include/darwin)
  include_directories($ENV{JAVA_HOME}/include/win32)
endif()

set(sources
  common.cc
  offline-recognizer.cc
  offline-stream.cc
  version.cc
  voice-activity-detector.cc
  wave-reader.cc
)

add_library(sherpa-onnx-jni SHARED ${sources})

target_compile_definitions(sherpa-onnx-jni PRIVATE SHERPA_ONNX_BUILD_SHARED_LIBS=1)
target_compile_definitions(sherpa-onnx-jni PRIVATE SHERPA_ONNX_BUILD_MAIN_LIB=1)

if(ANDROID OR (UNIX AND NOT APPLE))
  set_target_properties(sherpa-onnx-jni PROPERTIES
    LINK_FLAGS "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/sherpa-onnx-symbols.lds"
  )
elseif(APPLE)
  set_target_properties(sherpa-onnx-jni PROPERTIES
    LINK_FLAGS "-Wl,-exported_symbols_list,${CMAKE_CURRENT_SOURCE_DIR}/sherpa-onnx-symbols.exp"
  )
endif()

target_link_libraries(sherpa-onnx-jni sherpa-onnx-core)
install(TARGETS sherpa-onnx-jni DESTINATION lib)
