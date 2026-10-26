// sherpa-onnx/csrc/vad-model-config.cc
//
// Copyright (c)  2023  Xiaomi Corporation

#include "sherpa-onnx/csrc/vad-model-config.h"

#include <sstream>
#include <string>

#include "sherpa-onnx/csrc/macros.h"
#include "sherpa-onnx/csrc/text-utils.h"

namespace sherpa_onnx {

void VadModelConfig::Register(ParseOptions *po) {
  silero_vad.Register(po);

  po->Register("vad-sample-rate", &sample_rate,
               "Sample rate expected by the VAD model");

  po->Register("vad-num-threads", &num_threads,
               "Number of threads to run the VAD model");

  po->Register("vad-provider", &provider,
               "Specify a provider to run the VAD model. Supported values: "
               "cpu, cuda, coreml, openvino");

  po->Register("vad-debug", &debug,
               "true to display debug information when loading vad models");
}

bool VadModelConfig::Validate() const {
  // youmo: rknn is not built (lib/sherpa-onnx/README.md); refused here, where
  // the JNI can report it, rather than by VadModel::Create's exit
  if (provider == "rknn") {
    SHERPA_ONNX_LOGE("provider rknn is not built");
    return false;
  }

  if (!silero_vad.model.empty() && EndsWith(silero_vad.model, ".rknn")) {
    SHERPA_ONNX_LOGE("an rknn model '%s' needs provider rknn, which is not built",
                     silero_vad.model.c_str());
    return false;
  }

  if (!silero_vad.model.empty()) {
    return silero_vad.Validate();
  }

  SHERPA_ONNX_LOGE("Please provide one VAD model.");

  return false;
}

std::string VadModelConfig::ToString() const {
  std::ostringstream os;

  os << "VadModelConfig(";
  os << "silero_vad=" << silero_vad.ToString() << ", ";
  os << "sample_rate=" << sample_rate << ", ";
  os << "num_threads=" << num_threads << ", ";
  os << "provider=\"" << provider << "\", ";
  os << "debug=" << (debug ? "True" : "False") << ")";

  return os.str();
}

}  // namespace sherpa_onnx
