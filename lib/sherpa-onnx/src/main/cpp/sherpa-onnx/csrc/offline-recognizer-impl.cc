// sherpa-onnx/csrc/offline-recognizer-impl.cc
//
// Copyright (c)  2023  Xiaomi Corporation

#include "sherpa-onnx/csrc/offline-recognizer-impl.h"

#include <memory>
#include <sstream>
#include <string>
#include <utility>
#include <vector>


#if __ANDROID_API__ >= 9

#include "android/asset_manager.h"
#include "android/asset_manager_jni.h"
#endif

#if __OHOS__
#include "rawfile/raw_file_manager.h"
#endif

#include "fst/extensions/far/far.h"
#include "kaldifst/csrc/kaldi-fst-io.h"
#include "sherpa-onnx/csrc/file-utils.h"
#include "sherpa-onnx/csrc/fst-utils.h"
#include "sherpa-onnx/csrc/macros.h"
#include "sherpa-onnx/csrc/offline-recognizer-transducer-impl.h"
#include "sherpa-onnx/csrc/text-utils.h"

namespace sherpa_onnx {

// youmo: only transducers are built (lib/sherpa-onnx/README.md, "What we
// removed"); upstream picks among its other model families in Create. An empty
// model_type is taken as a transducer without reading the encoder's metadata
static void CheckTransducerOnly(const OfflineRecognizerConfig &config) {
  const std::string &provider = config.model_config.provider;
  if (provider == "rknn" || provider == "axera" || provider == "axcl" ||
      provider == "ascend" || provider == "qnn") {
    SHERPA_ONNX_LOGE("%s is not built (youmo: lib/sherpa-onnx/README.md).",
                     provider.c_str());
    SHERPA_ONNX_EXIT(-1);
  }
  if (config.model_config.transducer.encoder_filename.empty() ||
      (!config.model_config.model_type.empty() &&
       config.model_config.model_type != "transducer")) {
    SHERPA_ONNX_LOGE("Only transducer models are supported, model_type: '%s'",
                     config.model_config.model_type.c_str());
    SHERPA_ONNX_EXIT(-1);
  }
}

std::unique_ptr<OfflineRecognizerImpl> OfflineRecognizerImpl::Create(
    const OfflineRecognizerConfig &config) {
  CheckTransducerOnly(config);
  return std::make_unique<OfflineRecognizerTransducerImpl>(config);
}

template <typename Manager>
std::unique_ptr<OfflineRecognizerImpl> OfflineRecognizerImpl::Create(
    Manager *mgr, const OfflineRecognizerConfig &config) {
  CheckTransducerOnly(config);
  return std::make_unique<OfflineRecognizerTransducerImpl>(mgr, config);
}

OfflineRecognizerImpl::OfflineRecognizerImpl(
    const OfflineRecognizerConfig &config)
    : config_(config) {
  // TODO(fangjun): Refactor this function

  if (!config.rule_fsts.empty()) {
    std::vector<std::string> files;
    SplitStringToVector(config.rule_fsts, ",", false, &files);
    itn_list_.reserve(files.size());
    for (const auto &f : files) {
      if (config.model_config.debug) {
        SHERPA_ONNX_LOGE("rule fst: %s", f.c_str());
      }
      itn_list_.push_back(std::make_unique<kaldifst::TextNormalizer>(f));
    }
  }

  if (!config.rule_fars.empty()) {
    if (config.model_config.debug) {
      SHERPA_ONNX_LOGE("Loading FST archives");
    }
    std::vector<std::string> files;
    SplitStringToVector(config.rule_fars, ",", false, &files);

    itn_list_.reserve(files.size() + itn_list_.size());

    for (const auto &f : files) {
      if (config.model_config.debug) {
        SHERPA_ONNX_LOGE("rule far: %s", f.c_str());
      }
      std::unique_ptr<fst::FarReader<fst::StdArc>> reader(
          fst::FarReader<fst::StdArc>::Open(f));
      for (; !reader->Done(); reader->Next()) {
        std::unique_ptr<fst::StdConstFst> r(
            fst::CastOrConvertToConstFst(reader->GetFst()->Copy()));

        itn_list_.push_back(
            std::make_unique<kaldifst::TextNormalizer>(std::move(r)));
      }
    }

    if (config.model_config.debug) {
      SHERPA_ONNX_LOGE("FST archives loaded!");
    }
  }

  if (!config.hr.lexicon.empty() && !config.hr.rule_fsts.empty()) {
    auto hr_config = config.hr;
    hr_config.debug = config.model_config.debug;
    hr_ = std::make_unique<HomophoneReplacer>(hr_config);
  }
}

template <typename Manager>
OfflineRecognizerImpl::OfflineRecognizerImpl(
    Manager *mgr, const OfflineRecognizerConfig &config)
    : config_(config) {
  if (!config.rule_fsts.empty()) {
    std::vector<std::string> files;
    SplitStringToVector(config.rule_fsts, ",", false, &files);
    itn_list_.reserve(files.size());
    for (const auto &f : files) {
      if (config.model_config.debug) {
        SHERPA_ONNX_LOGE("rule fst: %s", f.c_str());
      }
      auto buf = ReadFile(mgr, f);
      std::istringstream is(std::string(buf.data(), buf.size()));
      itn_list_.push_back(std::make_unique<kaldifst::TextNormalizer>(is));
    }
  }

  if (!config.rule_fars.empty()) {
    std::vector<std::string> files;
    SplitStringToVector(config.rule_fars, ",", false, &files);
    itn_list_.reserve(files.size() + itn_list_.size());

    for (const auto &f : files) {
      if (config.model_config.debug) {
        SHERPA_ONNX_LOGE("rule far: %s", f.c_str());
      }

      auto buf = ReadFile(mgr, f);

      auto fsts = ReadFstsFromFar(buf);
      for (auto &r : fsts) {
        itn_list_.push_back(
            std::make_unique<kaldifst::TextNormalizer>(std::move(r)));
      }
    }  // for (const auto &f : files)
  }  // if (!config.rule_fars.empty())

  if (!config.hr.lexicon.empty() && !config.hr.rule_fsts.empty()) {
    auto hr_config = config.hr;
    hr_config.debug = config.model_config.debug;
    hr_ = std::make_unique<HomophoneReplacer>(mgr, hr_config);
  }
}

std::string OfflineRecognizerImpl::ApplyInverseTextNormalization(
    std::string text) const {
  text = RemoveInvalidUtf8Sequences(text);

  if (!itn_list_.empty()) {
    for (const auto &tn : itn_list_) {
      text = tn->Normalize(text);
    }
  }

  return text;
}

std::string OfflineRecognizerImpl::ApplyHomophoneReplacer(
    std::string text) const {
  if (hr_) {
    text = hr_->Apply(text);
  }

  return text;
}

void OfflineRecognizerImpl::SetConfig(const OfflineRecognizerConfig &config) {
  config_ = config;
}

#if __ANDROID_API__ >= 9
template OfflineRecognizerImpl::OfflineRecognizerImpl(
    AAssetManager *mgr, const OfflineRecognizerConfig &config);

template std::unique_ptr<OfflineRecognizerImpl> OfflineRecognizerImpl::Create(
    AAssetManager *mgr, const OfflineRecognizerConfig &config);
#endif

#if __OHOS__
template OfflineRecognizerImpl::OfflineRecognizerImpl(
    NativeResourceManager *mgr, const OfflineRecognizerConfig &config);
template std::unique_ptr<OfflineRecognizerImpl> OfflineRecognizerImpl::Create(
    NativeResourceManager *mgr, const OfflineRecognizerConfig &config);
#endif

}  // namespace sherpa_onnx
