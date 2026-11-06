// sherpa-onnx/csrc/sherpa-onnx.cc
//
// Copyright (c)  2022-2023  Xiaomi Corporation

#include <stdio.h>

#include <chrono>
#include <cmath>
#include <iomanip>
#include <iostream>
#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "sherpa-onnx/csrc/macros.h"
#include "sherpa-onnx/csrc/online-recognizer.h"
#include "sherpa-onnx/csrc/online-stream.h"
#include "sherpa-onnx/csrc/parse-options.h"
#include "sherpa-onnx/csrc/symbol-table.h"
#include "sherpa-onnx/csrc/wave-reader.h"

typedef struct {
  std::unique_ptr<sherpa_onnx::OnlineStream> online_stream;
  float duration;
  float elapsed_seconds;
} Stream;

int main(int32_t argc, char *argv[]) {
  const char *kUsageMessage = R"usage(
Usage:

(1) Streaming transducer

  ./bin/sherpa-onnx \
    --tokens=/path/to/tokens.txt \
    --encoder=/path/to/encoder.onnx \
    --decoder=/path/to/decoder.onnx \
    --joiner=/path/to/joiner.onnx \
    --provider=cpu \
    --num-threads=2 \
    --decoding-method=greedy_search \
    /path/to/foo.wav [bar.wav foobar.wav ...]

(youmo: only the streaming zipformer transducer is built here; for
lib/sherpa-onnx/host-eval.sh. --blocked="姥 姥/厨 艺": phrases, "/"-separated,
no result contains, spelled as hotwords are (a space between characters);
--hotwords-file for the words to boost. --endpoints with --enable-endpoint:
each file decoded alone, a result printed at each endpoint, as the app does)

Note: It supports decoding multiple files in batches

Default value for num_threads is 2.
Valid values for decoding_method: greedy_search (default), modified_beam_search.
Valid values for provider: cpu (default), cuda, coreml.
foo.wav should be of single channel, 16-bit PCM encoded wave file; its
sampling rate can be arbitrary and does not need to be 16kHz.

Please refer to
https://k2-fsa.github.io/sherpa/onnx/pretrained_models/index.html
for a list of pre-trained models to download.
)usage";

  sherpa_onnx::ParseOptions po(kUsageMessage);
  sherpa_onnx::OnlineRecognizerConfig config;
  std::string language;
  std::string blocked;  // youmo
  bool endpoints = false;  // youmo

  float left_padding_second = 0.5;
  float right_padding_second = 0.8;

  config.Register(&po);
  po.Register("language", &language,
              "Per-stream language hint for prompt-conditioned multilingual "
              "models, e.g., en, fr, ja, or auto. Empty means auto.");

  po.Register("blocked", &blocked,
              "youmo: \"/\"-separated phrases no result contains "
              "(modified_beam_search only)");

  po.Register("endpoints", &endpoints,
              "youmo: each file alone, a result at each endpoint (Reset), "
              "with --enable-endpoint");

  po.Register("left-padding", &left_padding_second,
              "Number of seconds for left padding");

  po.Register("right-padding", &right_padding_second,
              "Number of seconds for right padding");

  po.Read(argc, argv);
  if (po.NumArgs() < 1) {
    po.PrintUsage();
    fprintf(stderr, "Error! Please provide at lease 1 wav file\n");
    SHERPA_ONNX_EXIT(EXIT_FAILURE);
  }

  if (!std::isfinite(left_padding_second) ||
      !std::isfinite(right_padding_second) || left_padding_second < 0 ||
      right_padding_second < 0) {
    fprintf(stderr, "Padding must be finite and non-negative\n");
    return -1;
  }

  fprintf(stderr, "%s\n", config.ToString().c_str());

  if (!config.Validate()) {
    fprintf(stderr, "Errors in config!\n");
    return -1;
  }

  printf("Start to create recognizer\n");
  // youmo: timer.h is not kept (README.md)
  const auto created = std::chrono::steady_clock::now();
  sherpa_onnx::OnlineRecognizer recognizer(config);
  printf("Recognizer created in %.5f s\n",
         std::chrono::duration<float>(std::chrono::steady_clock::now() -
                                      created)
             .count());

  std::vector<Stream> ss;

  const auto begin = std::chrono::steady_clock::now();
  std::vector<float> durations;

  for (int32_t i = 1; i <= po.NumArgs(); ++i) {
    const std::string wav_filename = po.GetArg(i);
    int32_t sampling_rate = -1;

    bool is_ok = false;
    std::vector<float> samples =
        sherpa_onnx::ReadWave(wav_filename, &sampling_rate, &is_ok);

    if (!is_ok) {
      fprintf(stderr, "Failed to read '%s'\n", wav_filename.c_str());
      return -1;
    }

    const float duration = samples.size() / static_cast<float>(sampling_rate);

    auto s = recognizer.CreateStream("", blocked);
    if (!language.empty()) {
      s->SetOption("language", language);
    }

    std::vector<float> left_paddings(
        static_cast<int>(left_padding_second * sampling_rate), 0);
    s->AcceptWaveform(sampling_rate, left_paddings.data(),
                      left_paddings.size());

    s->AcceptWaveform(sampling_rate, samples.data(), samples.size());

    std::vector<float> tail_paddings(
        static_cast<int>(right_padding_second * sampling_rate), 0);
    // Note: We can call AcceptWaveform() multiple times.
    s->AcceptWaveform(sampling_rate, tail_paddings.data(),
                      tail_paddings.size());

    // Call InputFinished() to indicate that no audio samples are available
    s->InputFinished();
    ss.push_back({std::move(s), duration, 0});
  }

  // youmo: as the app decodes, a stream at a time, each segment printed at its endpoint
  for (int32_t i = 0; endpoints && i != static_cast<int32_t>(ss.size()); ++i) {
    auto *s = ss[i].online_stream.get();
    int32_t segment = 0;
    for (;;) {
      bool ready = recognizer.IsReady(s);
      if (ready) recognizer.DecodeStream(s);
      if (recognizer.IsEndpoint(s) || !ready) {
        const auto r = recognizer.GetResult(s);
        if (!r.text.empty()) {
          std::cerr << po.GetArg(i + 1) << " segment " << segment++ << "\n"
                    << r.AsJsonString() << "\n";
        }
        if (!ready) break;
        recognizer.Reset(s);
      }
    }
  }
  if (endpoints) return 0;

  std::vector<sherpa_onnx::OnlineStream *> ready_streams;
  for (;;) {
    ready_streams.clear();
    for (auto &s : ss) {
      const auto p_ss = s.online_stream.get();
      if (recognizer.IsReady(p_ss)) {
        ready_streams.push_back(p_ss);
      } else if (s.elapsed_seconds == 0) {
        const auto end = std::chrono::steady_clock::now();
        const float elapsed_seconds =
            std::chrono::duration_cast<std::chrono::milliseconds>(end - begin)
                .count() /
            1000.;
        s.elapsed_seconds = elapsed_seconds;
      }
    }

    if (ready_streams.empty()) {
      break;
    }

    recognizer.DecodeStreams(ready_streams.data(), ready_streams.size());
  }

  std::ostringstream os;
  for (int32_t i = 1; i <= po.NumArgs(); ++i) {
    const auto &s = ss[i - 1];
    const float rtf = s.elapsed_seconds / s.duration;

    os << po.GetArg(i) << "\n";
    os << "Number of threads: " << config.model_config.num_threads << ", "
       << std::setprecision(2) << "Elapsed seconds: " << s.elapsed_seconds
       << ", Audio duration (s): " << s.duration
       << ", Real time factor (RTF) = " << s.elapsed_seconds << "/"
       << s.duration << " = " << rtf << "\n";
    const auto r = recognizer.GetResult(s.online_stream.get());
    os << r.text << "\n";
    os << r.AsJsonString() << "\n\n";
  }

  std::cerr << os.str();

  return 0;
}
