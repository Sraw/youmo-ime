// sherpa-onnx/csrc/offline-model-config.h
//
// Copyright (c)  2023  Xiaomi Corporation
#ifndef SHERPA_ONNX_CSRC_OFFLINE_MODEL_CONFIG_H_
#define SHERPA_ONNX_CSRC_OFFLINE_MODEL_CONFIG_H_

#include <string>

#include "sherpa-onnx/csrc/offline-transducer-model-config.h"

namespace sherpa_onnx {

struct OfflineModelConfig {
  OfflineTransducerModelConfig transducer;

  std::string tokens;
  int32_t num_threads = 2;
  bool debug = false;
  std::string provider = "cpu";

  // With the help of this field, we only need to load the model once
  // instead of twice; and therefore it reduces initialization time.
  //
  // youmo: "transducer" or empty, the only model family built (README.md)
  std::string model_type;

  std::string modeling_unit = "cjkchar";
  std::string bpe_vocab;

  OfflineModelConfig() = default;
  OfflineModelConfig(const OfflineTransducerModelConfig &transducer,
                     const std::string &tokens, int32_t num_threads, bool debug,
                     const std::string &provider, const std::string &model_type,
                     const std::string &modeling_unit,
                     const std::string &bpe_vocab)
      : transducer(transducer),
        tokens(tokens),
        num_threads(num_threads),
        debug(debug),
        provider(provider),
        model_type(model_type),
        modeling_unit(modeling_unit),
        bpe_vocab(bpe_vocab) {}

  void Register(ParseOptions *po);
  bool Validate() const;

  std::string ToString() const;
};

}  // namespace sherpa_onnx

#endif  // SHERPA_ONNX_CSRC_OFFLINE_MODEL_CONFIG_H_
