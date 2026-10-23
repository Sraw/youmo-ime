if (CMAKE_VERSION VERSION_GREATER_EQUAL "4.0.0")
  set(CMAKE_POLICY_VERSION_MINIMUM 3.10)
endif()

cmake_minimum_required(VERSION 3.15 FATAL_ERROR)

# https://cmake.org/cmake/help/latest/prop_tgt/MSVC_RUNTIME_LIBRARY.html
cmake_policy(SET CMP0091 NEW)

if(POLICY CMP0169)
  cmake_policy(SET CMP0169 OLD)
  set(CMAKE_POLICY_DEFAULT_CMP0169 OLD)
endif()

if(POLICY CMP0177)
  cmake_policy(SET CMP0177 NEW)
endif()

message(STATUS "CMake version: ${CMAKE_VERSION}")

set(CMAKE_OSX_DEPLOYMENT_TARGET "10.14" CACHE STRING "Minimum OS X deployment version. Used only for macOS")

set(CMAKE_POLICY_DEFAULT_CMP0063 NEW)
set(CMAKE_POLICY_DEFAULT_CMP0069 NEW)

project(sherpa-onnx)

# Remember to update
# ./CHANGELOG.md
# ./new-release.sh
set(SHERPA_ONNX_VERSION "1.13.8")

# Disable warning about
#
# "The DOWNLOAD_EXTRACT_TIMESTAMP option was not given and policy CMP0135 is
#  not set.
if (CMAKE_VERSION VERSION_GREATER_EQUAL "3.24.0")
  cmake_policy(SET CMP0135 NEW)
endif()


if(CMAKE_SOURCE_DIR STREQUAL CMAKE_CURRENT_SOURCE_DIR)
  set(SUGGEST_BUILD_BINARIES ON)
else()
  set(SUGGEST_BUILD_BINARIES OFF)
endif()

option(SHERPA_ONNX_ENABLE_CHECK "Whether to build with assert" OFF)
option(BUILD_SHARED_LIBS "Whether to build shared libraries" OFF)
option(SHERPA_ONNX_ENABLE_JNI "Whether to build JNI internface" OFF)
option(SHERPA_ONNX_ENABLE_GPU "Enable ONNX Runtime GPU support" OFF)
option(SHERPA_ONNX_ENABLE_DIRECTML "Enable ONNX Runtime DirectML support" OFF)
option(SHERPA_ONNX_LINK_D3D "Whether static ONNX runtime lib with DML" OFF)

option(SHERPA_ONNX_ENABLE_BINARY "Whether to build binaries" ${SUGGEST_BUILD_BINARIES})
option(SHERPA_ONNX_LINK_LIBSTDCPP_STATICALLY "True to link libstdc++ statically. Used only when BUILD_SHARED_LIBS is OFF on Linux" ON)
option(SHERPA_ONNX_USE_PRE_INSTALLED_ONNXRUNTIME_IF_AVAILABLE "True to use pre-installed onnxruntime if available" ON)
option(SHERPA_ONNX_ENABLE_SANITIZER "Whether to enable ubsan and asan" OFF)
option(SHERPA_ONNX_ENABLE_SPACEMIT "Whether to build for SpacemiT CPUs " OFF)
set(SHERPA_ONNX_LINUX_ARM64_GPU_ONNXRUNTIME_VERSION "1.11.0" CACHE STRING "Used only for Linux ARM64 GPU. Set to 1.11.0 if you use CUDA 10.2 and cudnn8. Set it to 1.16.0 if you use CUDA 11.4 and cudnn8. Set it to 1.18.0 if you use CUDA 12.2 and cudnn8. Set it to 1.18.1 if you use CUDA 12.6 and cudnn9")

# SHERPA_ONNX_USE_STATIC_CRT controls whether we use:
#   - Static CRT:   /MT  (Release), /MTd (Debug)
#   - Dynamic CRT:  /MD  (Release), /MDd (Debug)
option(SHERPA_ONNX_USE_STATIC_CRT "For Windows only. ON to use static CRT (/MT /MTd); OFF to use dynamic (/MD /MDd)" ON)


# On Windows with MSVC, explicitly control which C runtime (CRT) to use.
#
# We rely on CMAKE_MSVC_RUNTIME_LIBRARY (CMake >= 3.15) instead of manually
# appending /MT, /MTd, /MD, or /MDd to compiler flags.
#
# Benefits:
#   - Correct behavior for multi-config generators (Visual Studio, Ninja Multi-Config)
#   - No reliance on CMAKE_BUILD_TYPE (which is empty for multi-config generators)
#   - Cleaner interaction with subprojects and FetchContent dependencies
#
# The generator expression automatically selects:
#   - Debug   -> /MTd or /MDd
#   - Release -> /MT  or /MD
#   - RelWithDebInfo / MinSizeRel -> /MT or /MD
if (MSVC AND NOT DEFINED CMAKE_MSVC_RUNTIME_LIBRARY)
  if(DEFINED CMAKE_BUILD_TYPE AND NOT CMAKE_BUILD_TYPE STREQUAL "")
    if (SHERPA_ONNX_USE_STATIC_CRT)
      # Use static CRT: /MT (Release) and /MTd (Debug)
      if(CMAKE_BUILD_TYPE MATCHES Debug)
        set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreadedDebug")
      else()
        set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded")
      endif()
    else()
      # Use dynamic CRT: /MD (Release) and /MDd (Debug)
      if(CMAKE_BUILD_TYPE MATCHES Debug)
        set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreadedDebugDLL")
      else()
        set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreadedDLL")
      endif()
    endif()
  else()
    if (SHERPA_ONNX_USE_STATIC_CRT)
      # Use static CRT: /MT (Release) and /MTd (Debug)
      set(CMAKE_MSVC_RUNTIME_LIBRARY
          "MultiThreaded$<$<CONFIG:Debug>:Debug>")
    else()
      # Use dynamic CRT: /MD (Release) and /MDd (Debug)
      set(CMAKE_MSVC_RUNTIME_LIBRARY
          "MultiThreadedDLL$<$<CONFIG:Debug>:Debug>")
    endif()
  endif()
endif()

set(CMAKE_ARCHIVE_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/lib")
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/lib")
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin")

if(UNIX)
  set(CMAKE_SKIP_BUILD_RPATH FALSE)
  set(BUILD_RPATH_USE_ORIGIN TRUE)
  set(CMAKE_INSTALL_RPATH_USE_LINK_PATH TRUE)
endif()

if(NOT APPLE)
  set(SHERPA_ONNX_RPATH_ORIGIN "$ORIGIN")
else()
  set(SHERPA_ONNX_RPATH_ORIGIN "@loader_path")
endif()

if(UNIX)
  set(CMAKE_INSTALL_RPATH ${SHERPA_ONNX_RPATH_ORIGIN})
  set(CMAKE_BUILD_RPATH ${SHERPA_ONNX_RPATH_ORIGIN})
endif()

if(NOT CMAKE_BUILD_TYPE)
  message(STATUS "No CMAKE_BUILD_TYPE given, default to Release")
  set(CMAKE_BUILD_TYPE Release)
endif()

set(CMAKE_EXPORT_COMPILE_COMMANDS ON CACHE BOOL "")

if(DEFINED ANDROID_ABI AND NOT SHERPA_ONNX_ENABLE_JNI)
  message(STATUS "Set SHERPA_ONNX_ENABLE_JNI to ON for Android")
  set(SHERPA_ONNX_ENABLE_JNI ON CACHE BOOL "" FORCE)
endif()

if(SHERPA_ONNX_ENABLE_GPU)
  message(WARNING "\
Compiling for NVIDIA GPU is enabled. Please make sure cudatoolkit
is installed on your system. Otherwise, you will get errors at runtime.
Hint: You don't need sudo permission to install CUDA toolkit. Please refer to
  https://k2-fsa.github.io/k2/installation/cuda-cudnn.html
to install CUDA toolkit if you have not installed it.")
  if(NOT BUILD_SHARED_LIBS)
    message(STATUS "Set BUILD_SHARED_LIBS to ON since SHERPA_ONNX_ENABLE_GPU is ON")
    set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
  endif()
endif()

if(SHERPA_ONNX_ENABLE_DIRECTML)
  message(WARNING "\
Compiling with DirectML enabled. Please make sure Windows 10 SDK
is installed on your system. Otherwise, you will get errors at runtime.
Please refer to
  https://onnxruntime.ai/docs/execution-providers/DirectML-ExecutionProvider.html#requirements
to install Windows 10 SDK if you have not installed it.")
  if(NOT BUILD_SHARED_LIBS)
    message(STATUS "Set BUILD_SHARED_LIBS to ON since SHERPA_ONNX_ENABLE_DIRECTML is ON")
    set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
  endif()
endif()

if(CMAKE_SYSTEM_NAME STREQUAL OHOS)
  set(CMAKE_CXX_FLAGS "-Wno-unused-command-line-argument ${CMAKE_CXX_FLAGS}")
  set(CMAKE_C_FLAGS "-Wno-unused-command-line-argument ${CMAKE_C_FLAGS}")
endif()

if(ANDROID)
  # see https://github.com/microsoft/onnxruntime/pull/22076
  # https://github.com/k2-fsa/sherpa-onnx/issues/2413
  set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
endif()

message(STATUS "CMAKE_BUILD_TYPE: ${CMAKE_BUILD_TYPE}")
message(STATUS "CMAKE_INSTALL_PREFIX: ${CMAKE_INSTALL_PREFIX}")
message(STATUS "BUILD_SHARED_LIBS ${BUILD_SHARED_LIBS}")
message(STATUS "SHERPA_ONNX_ENABLE_CHECK ${SHERPA_ONNX_ENABLE_CHECK}")
message(STATUS "SHERPA_ONNX_ENABLE_JNI ${SHERPA_ONNX_ENABLE_JNI}")
message(STATUS "SHERPA_ONNX_ENABLE_GPU ${SHERPA_ONNX_ENABLE_GPU}")
message(STATUS "SHERPA_ONNX_ENABLE_BINARY ${SHERPA_ONNX_ENABLE_BINARY}")
message(STATUS "SHERPA_ONNX_LINK_LIBSTDCPP_STATICALLY ${SHERPA_ONNX_LINK_LIBSTDCPP_STATICALLY}")
message(STATUS "SHERPA_ONNX_USE_PRE_INSTALLED_ONNXRUNTIME_IF_AVAILABLE ${SHERPA_ONNX_USE_PRE_INSTALLED_ONNXRUNTIME_IF_AVAILABLE}")
message(STATUS "SHERPA_ONNX_ENABLE_SANITIZER: ${SHERPA_ONNX_ENABLE_SANITIZER}")
message(STATUS "SHERPA_ONNX_ENABLE_SPACEMIT: ${SHERPA_ONNX_ENABLE_SPACEMIT}")
message(STATUS "SHERPA_ONNX_LINK_D3D: ${SHERPA_ONNX_LINK_D3D}")
message(STATUS "SHERPA_ONNX_USE_STATIC_CRT: ${SHERPA_ONNX_USE_STATIC_CRT}")
if(MSVC)
  message(STATUS "CMAKE_MSVC_RUNTIME_LIBRARY ${CMAKE_MSVC_RUNTIME_LIBRARY}")
endif()

if(BUILD_SHARED_LIBS OR SHERPA_ONNX_ENABLE_JNI)
  set(CMAKE_CXX_VISIBILITY_PRESET hidden)
  set(CMAKE_VISIBILITY_INLINES_HIDDEN 1)
endif()

# Rust on Linux links static sherpa-onnx archives into PIE executables by
# default, so static objects need PIC as well.
if(NOT WIN32)
  set(CMAKE_POSITION_INDEPENDENT_CODE ON)
endif()

if(BUILD_SHARED_LIBS AND NOT CMAKE_SYSTEM_NAME STREQUAL iOS AND CMAKE_BUILD_TYPE STREQUAL Release)
  # Don't use LTO for iOS since it causes the following error
  # error: unable to find any architecture information in the binary
  # at '/Users/fangjun/open-source/sherpa-onnx/build-ios/build/os64/sherpa-onnx.a':
  # Unknown header: 0xb17c0de
  # See also https://forums.developer.apple.com/forums/thread/714324

  include(CheckIPOSupported)
  check_ipo_supported(RESULT ipo)
  if(ipo)
    message(STATUS "IPO is enabled")
    set(CMAKE_INTERPROCEDURAL_OPTIMIZATION ON)
  else()
    message(STATUS "IPO is not available")
  endif()
endif()

if(SHERPA_ONNX_ENABLE_DIRECTML)
  message(STATUS "DirectML is enabled")
  add_definitions(-DSHERPA_ONNX_ENABLE_DIRECTML=1)
else()
  message(STATUS "DirectML is disabled")
  add_definitions(-DSHERPA_ONNX_ENABLE_DIRECTML=0)
endif()

if(NOT CMAKE_CXX_STANDARD)
  set(CMAKE_CXX_STANDARD 17 CACHE STRING "The C++ version to be used.")
endif()
set(CMAKE_CXX_EXTENSIONS OFF)
message(STATUS "C++ Standard version: ${CMAKE_CXX_STANDARD}")

include(CheckIncludeFileCXX)

if(SHERPA_ONNX_ENABLE_SPACEMIT)
  add_definitions(-DSHERPA_ONNX_ENABLE_SPACEMIT=1)
endif()

check_include_file_cxx(cxxabi.h SHERPA_ONNX_HAVE_CXXABI_H)
check_include_file_cxx(execinfo.h SHERPA_ONNX_HAVE_EXECINFO_H)

if(WIN32)
  add_definitions(-DNOMINMAX) # Otherwise, std::max() and std::min() won't work
endif()


if(WIN32 AND MSVC)
  # disable various warnings for MSVC
  # 4244: 'return': conversion from 'unsigned __int64' to 'int', possible loss of data
  # 4267: 'initializing': conversion from 'size_t' to 'int', possible loss of data
  # 4305: 'argument': truncation from 'double' to 'const float'
  # 4334: '<<': result of 32-bit shift implicitly converted to 64 bits
  # 4800: 'int': forcing value to bool 'true' or 'false'
  # 4996: 'fopen': This function or variable may be unsafe
  set(disabled_warnings
      /wd4244
      /wd4267
      /wd4305
      /wd4334
      /wd4800
      /wd4996
  )
  message(STATUS "Disabled warnings: ${disabled_warnings}")
  foreach(w IN LISTS disabled_warnings)
    string(APPEND CMAKE_CXX_FLAGS " ${w} ")
  endforeach()

  add_compile_options("$<$<C_COMPILER_ID:MSVC>:/utf-8>")
  add_compile_options("$<$<CXX_COMPILER_ID:MSVC>:/utf-8>")
endif()

list(APPEND CMAKE_MODULE_PATH ${CMAKE_CURRENT_SOURCE_DIR}/cmake/Modules)
list(APPEND CMAKE_MODULE_PATH ${CMAKE_CURRENT_SOURCE_DIR}/cmake)

include(show-info)

if(NOT BUILD_SHARED_LIBS AND CMAKE_SYSTEM_NAME STREQUAL Linux)
  if(SHERPA_ONNX_LINK_LIBSTDCPP_STATICALLY)
    message(STATUS "Link libstdc++ statically")
    set(CMAKE_CXX_FLAGS " ${CMAKE_CXX_FLAGS} -static-libstdc++ -static-libgcc ")
  else()
    message(STATUS "Link libstdc++ dynamically")
  endif()
endif()

include(kaldi-native-fbank)
include(kaldi-decoder)
include(onnxruntime)
include(simple-sentencepiece)
set(ONNXRUNTIME_DIR ${onnxruntime_SOURCE_DIR})
message(STATUS "ONNXRUNTIME_DIR: ${ONNXRUNTIME_DIR}")

include(json)

# if(NOT MSVC AND CMAKE_BUILD_TYPE STREQUAL Debug AND (CMAKE_CXX_COMPILER_ID STREQUAL "Clang" OR CMAKE_CXX_COMPILER_ID STREQUAL "AppleClang"))
if(SHERPA_ONNX_ENABLE_SANITIZER)
  message(WARNING "enable ubsan and asan")
  set(CMAKE_REQUIRED_LIBRARIES -lubsan -lasan)
  include(CheckCCompilerFlag)

  set(flags -fsanitize=undefined )
  string(APPEND flags " -fno-sanitize-recover=undefined ")
  string(APPEND flags " -fsanitize=integer ")
  string(APPEND flags " -fsanitize=nullability ")
  string(APPEND flags " -fsanitize=implicit-conversion ")
  string(APPEND flags " -fsanitize=bounds ")
  string(APPEND flags " -fsanitize=address ")

  if(OFF)
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} ${flags} -Wall -Wextra")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${flags} -Wall -Wextra")
  else()
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} ${flags}")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${flags}")
  endif()

  set(CMAKE_EXECUTABLE_LINKER_FLAGS "${CMAKE_EXECUTABLE_LINKER_FLAGS} ${flags}")

  add_compile_options(-fno-omit-frame-pointer)
endif()

add_subdirectory(sherpa-onnx)

message(STATUS "CMAKE_CXX_FLAGS: ${CMAKE_CXX_FLAGS}")

if(NOT BUILD_SHARED_LIBS)
  if(APPLE)
    set(SHERPA_ONNX_PKG_CONFIG_EXTRA_LIBS "-lc++ -framework Foundation")
  endif()

  if(UNIX AND NOT APPLE)
    set(SHERPA_ONNX_PKG_CONFIG_EXTRA_LIBS "-lstdc++ -lm -pthread -ldl")
  endif()
endif()

if(NOT BUILD_SHARED_LIBS)
# See https://people.freedesktop.org/~dbn/pkg-config-guide.html
  configure_file(cmake/sherpa-onnx-static-no-tts.pc.in ${PROJECT_BINARY_DIR}/sherpa-onnx.pc @ONLY)
else()
  configure_file(cmake/sherpa-onnx-shared.pc.in ${PROJECT_BINARY_DIR}/sherpa-onnx.pc @ONLY)
endif()

install(
  FILES
    ${PROJECT_BINARY_DIR}/sherpa-onnx.pc
  DESTINATION
    .
)
message(STATUS "CMAKE_CXX_FLAGS: ${CMAKE_CXX_FLAGS}")
