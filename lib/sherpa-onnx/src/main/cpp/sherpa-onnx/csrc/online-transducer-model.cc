// sherpa-onnx/csrc/online-transducer-model.cc
//
// Copyright (c)  2023  Xiaomi Corporation
// Copyright (c)  2023  Pingfeng Luo
#include "sherpa-onnx/csrc/online-transducer-model.h"

#if __ANDROID_API__ >= 9
#include "android/asset_manager.h"
#include "android/asset_manager_jni.h"
#endif

#if __OHOS__
#include "rawfile/raw_file_manager.h"
#endif

#include <algorithm>
#include <array>
#include <memory>
#include <string>
#include <vector>

#include "sherpa-onnx/csrc/file-utils.h"
#include "sherpa-onnx/csrc/macros.h"
#include "sherpa-onnx/csrc/online-zipformer2-transducer-model.h"

namespace sherpa_onnx {

// youmo: only zipformer2 is built (lib/sherpa-onnx/README.md, "What we
// removed"); upstream also has conformer, ebranchformer, lstm and zipformer,
// and loads the encoder an extra time to read its model_type when none is given
static void CheckZipformer2(const OnlineModelConfig &config) {
  if (!config.model_type.empty() && config.model_type != "zipformer2") {
    SHERPA_ONNX_LOGE("Only zipformer2 is supported, model_type: '%s'",
                     config.model_type.c_str());
    SHERPA_ONNX_EXIT(-1);
  }
}

std::unique_ptr<OnlineTransducerModel> OnlineTransducerModel::Create(
    const OnlineModelConfig &config) {
  CheckZipformer2(config);
  return std::make_unique<OnlineZipformer2TransducerModel>(config);
}

Ort::Value OnlineTransducerModel::BuildDecoderInput(
    const std::vector<OnlineTransducerDecoderResult> &results) {
  int32_t batch_size = static_cast<int32_t>(results.size());
  int32_t context_size = ContextSize();
  std::array<int64_t, 2> shape{batch_size, context_size};
  Ort::Value decoder_input = Ort::Value::CreateTensor<int64_t>(
      Allocator(), shape.data(), shape.size());
  int64_t *p = decoder_input.GetTensorMutableData<int64_t>();

  for (const auto &r : results) {
    const int64_t *begin = r.tokens.data() + r.tokens.size() - context_size;
    const int64_t *end = r.tokens.data() + r.tokens.size();
    std::copy(begin, end, p);
    p += context_size;
  }
  return decoder_input;
}

Ort::Value OnlineTransducerModel::BuildDecoderInput(
    const std::vector<Hypothesis> &hyps) {
  int32_t batch_size = static_cast<int32_t>(hyps.size());
  int32_t context_size = ContextSize();
  std::array<int64_t, 2> shape{batch_size, context_size};
  Ort::Value decoder_input = Ort::Value::CreateTensor<int64_t>(
      Allocator(), shape.data(), shape.size());
  int64_t *p = decoder_input.GetTensorMutableData<int64_t>();

  for (const auto &h : hyps) {
    std::copy(h.ys.end() - context_size, h.ys.end(), p);
    p += context_size;
  }
  return decoder_input;
}

template <typename Manager>
std::unique_ptr<OnlineTransducerModel> OnlineTransducerModel::Create(
    Manager *mgr, const OnlineModelConfig &config) {
  CheckZipformer2(config);
  return std::make_unique<OnlineZipformer2TransducerModel>(mgr, config);
}

#if __ANDROID_API__ >= 9
template std::unique_ptr<OnlineTransducerModel> OnlineTransducerModel::Create(
    AAssetManager *mgr, const OnlineModelConfig &config);
#endif

#if __OHOS__
template std::unique_ptr<OnlineTransducerModel> OnlineTransducerModel::Create(
    NativeResourceManager *mgr, const OnlineModelConfig &config);
#endif

}  // namespace sherpa_onnx
