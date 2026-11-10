// sherpa-onnx/csrc/online-recognizer-impl.cc
//
// Copyright (c)  2023-2025  Xiaomi Corporation

#include "sherpa-onnx/csrc/online-recognizer-impl.h"

#include <memory>
#include <sstream>
#include <stdexcept>
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
#include "sherpa-onnx/csrc/online-recognizer-transducer-impl.h"
#include "sherpa-onnx/csrc/text-utils.h"

namespace sherpa_onnx {

// youmo: only the streaming zipformer transducer is built
// (lib/sherpa-onnx/README.md, "What we removed"); upstream picks among its
// other model families (NeMo transducers, paraformer, CTC) and NPUs in Create.
// An empty model_type is taken as a transducer without reading the metadata
static void CheckTransducerOnly(const OnlineRecognizerConfig &config) {
  const std::string &provider = config.model_config.provider_config.provider;
  if (provider == "rknn" || provider == "qnn") {
    SHERPA_ONNX_LOGE("%s is not built (youmo: lib/sherpa-onnx/README.md).",
                     provider.c_str());
    // youmo: thrown, not _Exit: the recognizer's making is under SafeJNI,
    // voice input then reports it
    throw std::invalid_argument("provider not built: " + provider);
  }
  if (config.model_config.transducer.encoder.empty() ||
      (!config.model_config.model_type.empty() &&
       config.model_config.model_type != "zipformer2")) {
    SHERPA_ONNX_LOGE(
        "Only streaming zipformer2 transducers are supported, model_type: '%s'",
        config.model_config.model_type.c_str());
    throw std::invalid_argument("only streaming zipformer2 transducers");
  }
}

std::unique_ptr<OnlineRecognizerImpl> OnlineRecognizerImpl::Create(
    const OnlineRecognizerConfig &config) {
  CheckTransducerOnly(config);
  return std::make_unique<OnlineRecognizerTransducerImpl>(config);
}

template <typename Manager>
std::unique_ptr<OnlineRecognizerImpl> OnlineRecognizerImpl::Create(
    Manager *mgr, const OnlineRecognizerConfig &config) {
  CheckTransducerOnly(config);
  return std::make_unique<OnlineRecognizerTransducerImpl>(mgr, config);
}

OnlineRecognizerImpl::OnlineRecognizerImpl(const OnlineRecognizerConfig &config)
    : config_(config) {
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
OnlineRecognizerImpl::OnlineRecognizerImpl(Manager *mgr,
                                           const OnlineRecognizerConfig &config)
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

std::string OnlineRecognizerImpl::ApplyInverseTextNormalization(
    std::string text) const {
  text = RemoveInvalidUtf8Sequences(text);

  if (!itn_list_.empty()) {
    for (const auto &tn : itn_list_) {
      text = tn->Normalize(text);
    }
  }

  return text;
}

std::string OnlineRecognizerImpl::ApplyHomophoneReplacer(
    std::string text) const {
  if (hr_) {
    text = hr_->Apply(text);
  }

  return text;
}

#if __ANDROID_API__ >= 9
template OnlineRecognizerImpl::OnlineRecognizerImpl(
    AAssetManager *mgr, const OnlineRecognizerConfig &config);

template std::unique_ptr<OnlineRecognizerImpl> OnlineRecognizerImpl::Create(
    AAssetManager *mgr, const OnlineRecognizerConfig &config);
#endif

#if __OHOS__
template OnlineRecognizerImpl::OnlineRecognizerImpl(
    NativeResourceManager *mgr, const OnlineRecognizerConfig &config);

template std::unique_ptr<OnlineRecognizerImpl> OnlineRecognizerImpl::Create(
    NativeResourceManager *mgr, const OnlineRecognizerConfig &config);
#endif

}  // namespace sherpa_onnx
