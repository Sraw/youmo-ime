// sherpa-onnx/csrc/offline-recognizer-impl.h
//
// Copyright (c)  2023  Xiaomi Corporation

#ifndef SHERPA_ONNX_CSRC_OFFLINE_RECOGNIZER_IMPL_H_
#define SHERPA_ONNX_CSRC_OFFLINE_RECOGNIZER_IMPL_H_

#include <memory>
#include <string>
#include <vector>

#include "kaldifst/csrc/text-normalizer.h"
#include "sherpa-onnx/csrc/homophone-replacer.h"
#include "sherpa-onnx/csrc/macros.h"
#include "sherpa-onnx/csrc/offline-recognizer.h"
#include "sherpa-onnx/csrc/offline-stream.h"

namespace sherpa_onnx {

class OfflineRecognizerImpl {
 public:
  explicit OfflineRecognizerImpl(const OfflineRecognizerConfig &config);

  static std::unique_ptr<OfflineRecognizerImpl> Create(
      const OfflineRecognizerConfig &config);

  template <typename Manager>
  OfflineRecognizerImpl(Manager *mgr, const OfflineRecognizerConfig &config);

  template <typename Manager>
  static std::unique_ptr<OfflineRecognizerImpl> Create(
      Manager *mgr, const OfflineRecognizerConfig &config);

  virtual ~OfflineRecognizerImpl() = default;

  virtual std::unique_ptr<OfflineStream> CreateStream(
      const std::string &hotwords) const {
    SHERPA_ONNX_LOGE("Only transducer models support contextual biasing.");
    SHERPA_ONNX_EXIT(-1);
  }

  virtual std::unique_ptr<OfflineStream> CreateStream() const = 0;

  // youmo: with phrases its result must not contain, "/"-separated as the
  // hotwords; only a transducer's modified beam search heeds them
  virtual std::unique_ptr<OfflineStream> CreateStream(
      const std::string &hotwords, const std::string &blocked) const {
    return hotwords.empty() ? CreateStream() : CreateStream(hotwords);
  }

  virtual void DecodeStreams(OfflineStream **ss, int32_t n) const = 0;

  virtual void SetConfig(const OfflineRecognizerConfig &config);

  virtual OfflineRecognizerConfig GetConfig() const = 0;

  std::string ApplyInverseTextNormalization(std::string text) const;

  std::string ApplyHomophoneReplacer(std::string text) const;

 protected:
  OfflineRecognizerConfig config_;
  // for inverse text normalization. Used only if
  // config.rule_fsts is not empty or
  // config.rule_fars is not empty
  std::vector<std::unique_ptr<kaldifst::TextNormalizer>> itn_list_;
  std::unique_ptr<HomophoneReplacer> hr_;
};

}  // namespace sherpa_onnx

#endif  // SHERPA_ONNX_CSRC_OFFLINE_RECOGNIZER_IMPL_H_
