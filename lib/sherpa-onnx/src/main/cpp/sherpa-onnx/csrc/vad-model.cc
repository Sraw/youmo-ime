// sherpa-onnx/csrc/vad-model.cc
//
// Copyright (c)  2023  Xiaomi Corporation

#include "sherpa-onnx/csrc/vad-model.h"

#include <memory>

#if __ANDROID_API__ >= 9
#include "android/asset_manager.h"
#include "android/asset_manager_jni.h"
#endif

#if __OHOS__
#include "rawfile/raw_file_manager.h"
#endif

#include "sherpa-onnx/csrc/macros.h"
#include "sherpa-onnx/csrc/silero-vad-model.h"

namespace sherpa_onnx {

std::unique_ptr<VadModel> VadModel::Create(const VadModelConfig &config) {
  if (config.provider == "rknn") {
    SHERPA_ONNX_LOGE(
        "rknn is not built (youmo: lib/sherpa-onnx/README.md).");
    SHERPA_ONNX_EXIT(-1);
    return nullptr;
  }

  if (!config.silero_vad.model.empty()) {
    return std::make_unique<SileroVadModel>(config);
  }

  SHERPA_ONNX_LOGE("Please provide a vad model");
  return nullptr;
}

template <typename Manager>
std::unique_ptr<VadModel> VadModel::Create(Manager *mgr,
                                           const VadModelConfig &config) {
  if (config.provider == "rknn") {
    SHERPA_ONNX_LOGE(
        "rknn is not built (youmo: lib/sherpa-onnx/README.md).");
    SHERPA_ONNX_EXIT(-1);
    return nullptr;
  }
  if (!config.silero_vad.model.empty()) {
    return std::make_unique<SileroVadModel>(mgr, config);
  }

  SHERPA_ONNX_LOGE("Please provide a vad model");
  return nullptr;
}

#if __ANDROID_API__ >= 9
template std::unique_ptr<VadModel> VadModel::Create(
    AAssetManager *mgr, const VadModelConfig &config);
#endif

#if __OHOS__
template std::unique_ptr<VadModel> VadModel::Create(
    NativeResourceManager *mgr, const VadModelConfig &config);
#endif
}  // namespace sherpa_onnx
