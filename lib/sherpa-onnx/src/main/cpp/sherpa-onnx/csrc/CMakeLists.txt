include_directories(${PROJECT_SOURCE_DIR})


# youmo: the offline transducer, its beam search and hotwords, silero VAD, features
# and what they need; upstream's other model families are removed (README.md)
set(sources
  base64-decode.cc
  bbpe.cc
  circular-buffer.cc
  context-graph.cc
  features.cc
  file-utils.cc
  fst-utils.cc
  homophone-replacer.cc
  hypothesis.cc
  lodr-fst.cc
  math.cc
  offline-ctc-fst-decoder-config.cc
  offline-lm-config.cc
  offline-lm.cc
  offline-model-config.cc
  offline-recognizer-impl.cc
  offline-recognizer.cc
  offline-rnn-lm.cc
  offline-stream.cc
  offline-transducer-greedy-search-decoder.cc
  offline-transducer-model-config.cc
  offline-transducer-model.cc
  offline-transducer-modified-beam-search-decoder.cc
  online-lm-config.cc
  online-model-config.cc
  online-nemo-ctc-model-config.cc
  online-paraformer-model-config.cc
  online-t-one-ctc-model-config.cc
  online-transducer-model-config.cc
  online-wenet-ctc-model-config.cc
  online-zipformer2-ctc-model-config.cc
  onnx-utils.cc
  packed-sequence.cc
  pad-sequence.cc
  parse-options.cc
  phrase-matcher.cc
  provider-config.cc
  provider.cc
  resample.cc
  session.cc
  silero-vad-model-config.cc
  silero-vad-model.cc
  slice.cc
  symbol-table.cc
  text-utils.cc
  transpose.cc
  utils.cc
  vad-model-config.cc
  vad-model.cc
  version.cc
  voice-activity-detector.cc
  wave-reader.cc
  wave-writer.cc
  qnn-config.cc
)

if(SHERPA_ONNX_ENABLE_CHECK)
  list(APPEND sources log.cc)
endif()

# Always static build
add_library(sherpa-onnx-core STATIC ${sources})


if(WIN32 AND SHERPA_ONNX_LINK_D3D)
    target_link_libraries(sherpa-onnx-core dxguid.lib d3d12.lib dxgi.lib dxcore.lib)
endif()


if(TARGET directml)
    target_link_libraries(sherpa-onnx-core directml)
endif()

set_target_properties(
    sherpa-onnx-core
  PROPERTIES
    POSITION_INDEPENDENT_CODE ON
    C_VISIBILITY_PRESET hidden
    CXX_VISIBILITY_PRESET hidden
)

if(APPLE)
  target_compile_options(sherpa-onnx-core PRIVATE
    -Wno-deprecated-declarations
  )
endif()

if(ANDROID)
  target_link_libraries(sherpa-onnx-core android log)
endif()

target_link_libraries(sherpa-onnx-core
  kaldi-native-fbank-core
  kaldi-decoder-core
  ssentencepiece_core
)
if(DEFINED OHOS AND x${OHOS} STREQUAL xOHOS)
  target_link_libraries(sherpa-onnx-core
    hilog_ndk.z
    rawfile.z
  )
endif()

if(SHERPA_ONNX_ENABLE_SPACEMIT)
  if(TARGET spacemit_ep)
    target_link_libraries(sherpa-onnx-core spacemit_ep)
  else()
    target_link_libraries(sherpa-onnx-core ${spacemit_ep_lib_files})
  endif()
endif()

if(TARGET onnxruntime)
  target_link_libraries(sherpa-onnx-core onnxruntime)
else()
  target_link_libraries(sherpa-onnx-core ${onnxruntime_lib_files})
endif()

if(NOT WIN32)
  target_link_libraries(sherpa-onnx-core -lm)
endif()

if(NOT BUILD_SHARED_LIBS AND APPLE)
  target_link_libraries(sherpa-onnx-core "-framework Foundation")
endif()

target_link_libraries(sherpa-onnx-core fstfar fst)

if(SHERPA_ONNX_ENABLE_CHECK)
  target_compile_definitions(sherpa-onnx-core PUBLIC SHERPA_ONNX_ENABLE_CHECK=1)

  if(SHERPA_ONNX_HAVE_EXECINFO_H)
    target_compile_definitions(sherpa-onnx-core PRIVATE SHERPA_ONNX_HAVE_EXECINFO_H=1)
  endif()

  if(SHERPA_ONNX_HAVE_CXXABI_H)
    target_compile_definitions(sherpa-onnx-core PRIVATE SHERPA_ONNX_HAVE_CXXABI_H=1)
  endif()
endif()

if(NOT BUILD_SHARED_LIBS AND CMAKE_SYSTEM_NAME STREQUAL Linux)
  # This is for linux arm32 and arm64
  target_link_libraries(sherpa-onnx-core -ldl)
endif()

if(NOT WIN32 AND CMAKE_SYSTEM_NAME STREQUAL Linux)
  target_link_libraries(sherpa-onnx-core -pthread)
endif()

if(SHERPA_ONNX_ENABLE_BINARY)
  # youmo: only the offline recognizer, for lib/sherpa-onnx/host-eval.sh; the other programs'
  # sources were removed with the rest of what our builds never compile (see README.md)
  add_executable(sherpa-onnx-offline sherpa-onnx-offline.cc)

  set(main_exes
    sherpa-onnx-offline
  )

  foreach(exe IN LISTS main_exes)
    target_link_libraries(${exe} sherpa-onnx-core)
  endforeach()

  if(UNIX)
    foreach(exe IN LISTS main_exes)
      target_link_libraries(${exe} "-Wl,-rpath,${SHERPA_ONNX_RPATH_ORIGIN}/../lib")
      target_link_libraries(${exe} "-Wl,-rpath,${SHERPA_ONNX_RPATH_ORIGIN}/../../../sherpa_onnx/lib")

    endforeach()
  endif()
endif()

if(NOT BUILD_SHARED_LIBS)
  install(TARGETS sherpa-onnx-core DESTINATION lib)
endif()

if(SHERPA_ONNX_ENABLE_BINARY)
  install(
    TARGETS
      ${main_exes}
    DESTINATION
      bin
  )
endif()

set(srcs_to_check)
foreach(s IN LISTS sources)
  list(APPEND srcs_to_check ${CMAKE_CURRENT_LIST_DIR}/${s})
endforeach()

# For clang-tidy
add_custom_target(
  clang-tidy-check
  clang-tidy -p ${CMAKE_BINARY_DIR}/compile_commands.json --config-file ${PROJECT_SOURCE_DIR}/.clang-tidy ${srcs_to_check}
  DEPENDS ${sources})

add_custom_target(check DEPENDS clang-tidy-check)
