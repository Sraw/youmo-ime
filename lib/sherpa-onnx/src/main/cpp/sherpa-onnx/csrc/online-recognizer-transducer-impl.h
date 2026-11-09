// sherpa-onnx/csrc/online-recognizer-transducer-impl.h
//
// Copyright (c)  2022-2023  Xiaomi Corporation

#ifndef SHERPA_ONNX_CSRC_ONLINE_RECOGNIZER_TRANSDUCER_IMPL_H_
#define SHERPA_ONNX_CSRC_ONLINE_RECOGNIZER_TRANSDUCER_IMPL_H_

#include <algorithm>
#include <ios>
#include <limits>
#include <memory>
#include <mutex>  // NOLINT
#include <sstream>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#include "sherpa-onnx/csrc/blocked-phrases.h"
#include "sherpa-onnx/csrc/file-utils.h"
#include "sherpa-onnx/csrc/macros.h"
#include "sherpa-onnx/csrc/online-lm.h"
#include "sherpa-onnx/csrc/online-recognizer-impl.h"
#include "sherpa-onnx/csrc/online-recognizer.h"
#include "sherpa-onnx/csrc/online-transducer-decoder.h"
#include "sherpa-onnx/csrc/online-transducer-greedy-search-decoder.h"
#include "sherpa-onnx/csrc/online-transducer-model.h"
#include "sherpa-onnx/csrc/online-transducer-modified-beam-search-decoder.h"
#include "sherpa-onnx/csrc/onnx-utils.h"
#include "sherpa-onnx/csrc/symbol-table.h"
#include "sherpa-onnx/csrc/text-utils.h"
#include "sherpa-onnx/csrc/utils.h"
#include "ssentencepiece/csrc/ssentencepiece.h"

namespace sherpa_onnx {

// youmo: a hypothesis' text, as Convert() writes the best one's
static std::string NbestText(const std::vector<int64_t> &tokens,
                             const SymbolTable &sym_table) {
  std::string text;
  for (auto i : tokens) {
    const auto &sym = sym_table[i];
    if (sym != "<unk>") text.append(sym);
  }
  if (sym_table.IsByteBpe()) text = sym_table.DecodeByteBpe(text);
  return RemoveSpaceBetweenCjk(text);
}

OnlineRecognizerResult Convert(const OnlineTransducerDecoderResult &src,
                               const SymbolTable &sym_table,
                               float frame_shift_ms, int32_t subsampling_factor,
                               int32_t segment, int32_t frames_since_start) {
  OnlineRecognizerResult r;
  r.tokens.reserve(src.tokens.size());
  r.timestamps.reserve(src.tokens.size());

  std::string text;
  for (auto i : src.tokens) {
    auto sym = sym_table[i];
    if (sym == "<unk>") {
      continue;
    }

    text.append(sym);

    if (sym.size() == 1 && (sym[0] < 0x20 || sym[0] > 0x7e)) {
      // for bpe models with byte_fallback
      // (but don't rewrite printable characters 0x20..0x7e,
      //  which collide with standard BPE units)
      std::ostringstream os;
      os << "<0x" << std::hex << std::uppercase
         << (static_cast<int32_t>(sym[0]) & 0xff) << ">";
      sym = os.str();
    }

    r.tokens.push_back(std::move(sym));
  }

  if (sym_table.IsByteBpe()) {
    text = sym_table.DecodeByteBpe(text);
  }

  text = RemoveSpaceBetweenCjk(text);

  r.text = std::move(text);

  float frame_shift_s = frame_shift_ms / 1000. * subsampling_factor;
  for (auto t : src.timestamps) {
    float time = frame_shift_s * t;
    r.timestamps.push_back(time);
  }

  r.ys_probs = std::move(src.ys_probs);
  r.lm_probs = std::move(src.lm_probs);
  r.context_scores = std::move(src.context_scores);
  r.num_trailing_blanks = src.num_trailing_blanks;

  for (const auto &tokens : src.nbest_tokens) {
    r.nbest.push_back(NbestText(tokens, sym_table));
  }
  r.nbest_scores = src.nbest_scores;

  r.segment = segment;
  r.start_time = frames_since_start * frame_shift_ms / 1000.;

  return r;
}

class OnlineRecognizerTransducerImpl : public OnlineRecognizerImpl {
 public:
  explicit OnlineRecognizerTransducerImpl(const OnlineRecognizerConfig &config)
      : OnlineRecognizerImpl(config),
        config_(config),
        model_(OnlineTransducerModel::Create(config.model_config)),
        endpoint_(config_.endpoint_config) {
    if (!config.model_config.tokens_buf.empty()) {
      sym_ = SymbolTable(config.model_config.tokens_buf, false);
    } else {
      /// assuming tokens_buf and tokens are guaranteed not being both empty
      sym_ = SymbolTable(config.model_config.tokens, true);
    }

    if (sym_.Contains("<unk>")) {
      unk_id_ = sym_["<unk>"];
    }

    model_->SetFeatureDim(config.feat_config.feature_dim);

    if (config.decoding_method == "modified_beam_search") {
      if (!config_.model_config.bpe_vocab.empty()) {
        bpe_encoder_ = std::make_unique<ssentencepiece::Ssentencepiece>(
            config_.model_config.bpe_vocab);
      }

      if (!config_.hotwords_buf.empty()) {
        InitHotwordsFromBufStr();
      } else if (!config_.hotwords_file.empty()) {
        InitHotwords();
      }

      CheckNoLm();

      decoder_ = std::make_unique<OnlineTransducerModifiedBeamSearchDecoder>(
          model_.get(), lm_.get(), config_.max_active_paths,
          config_.lm_config.scale, config_.lm_config.shallow_fusion, unk_id_,
          config_.blank_penalty, config_.temperature_scale);

    } else if (config.decoding_method == "greedy_search") {
      decoder_ = std::make_unique<OnlineTransducerGreedySearchDecoder>(
          model_.get(), unk_id_, config_.blank_penalty,
          config_.temperature_scale);

    } else {
      SHERPA_ONNX_LOGE("Unsupported decoding method: %s",
                       config.decoding_method.c_str());
      SHERPA_ONNX_EXIT(-1);
    }

    CheckNoWhisperFeature();
  }

  template <typename Manager>
  explicit OnlineRecognizerTransducerImpl(Manager *mgr,
                                          const OnlineRecognizerConfig &config)
      : OnlineRecognizerImpl(mgr, config),
        config_(config),
        model_(OnlineTransducerModel::Create(mgr, config.model_config)),
        sym_(mgr, config.model_config.tokens),
        endpoint_(config_.endpoint_config) {
    if (sym_.Contains("<unk>")) {
      unk_id_ = sym_["<unk>"];
    }

    model_->SetFeatureDim(config.feat_config.feature_dim);

    if (config.decoding_method == "modified_beam_search") {
      CheckNoLm();

      if (!config_.model_config.bpe_vocab.empty()) {
        auto buf = ReadFile(mgr, config_.model_config.bpe_vocab);
        std::istringstream iss(std::string(buf.begin(), buf.end()));
        bpe_encoder_ = std::make_unique<ssentencepiece::Ssentencepiece>(iss);
      }

      if (!config_.hotwords_buf.empty()) {
        InitHotwordsFromBufStr();
      } else if (!config_.hotwords_file.empty()) {
        InitHotwords(mgr);
      }

      decoder_ = std::make_unique<OnlineTransducerModifiedBeamSearchDecoder>(
          model_.get(), lm_.get(), config_.max_active_paths,
          config_.lm_config.scale, config_.lm_config.shallow_fusion, unk_id_,
          config_.blank_penalty, config_.temperature_scale);

    } else if (config.decoding_method == "greedy_search") {
      decoder_ = std::make_unique<OnlineTransducerGreedySearchDecoder>(
          model_.get(), unk_id_, config_.blank_penalty,
          config_.temperature_scale);

    } else {
      SHERPA_ONNX_LOGE("Unsupported decoding method: %s",
                       config.decoding_method.c_str());
      SHERPA_ONNX_EXIT(-1);
    }

    CheckNoWhisperFeature();
  }

  std::unique_ptr<OnlineStream> CreateStream() const override {
    auto stream =
        std::make_unique<OnlineStream>(config_.feat_config, hotwords_graph_);
    InitOnlineStream(stream.get());
    return stream;
  }

  std::unique_ptr<OnlineStream> CreateStream(
      const std::string &hotwords) const override {
    // youmo: as with none of the user's words where they would not count:
    // greedy search reads no context graph
    if (config_.decoding_method != "modified_beam_search") {
      return CreateStream();
    }
    // youmo: the same words' graph again (last_hotwords_graph_)
    std::lock_guard<std::mutex> lock(last_hotwords_mutex_);
    if (last_hotwords_graph_ != nullptr && hotwords == last_hotwords_) {
      auto stream = std::make_unique<OnlineStream>(config_.feat_config,
                                                   last_hotwords_graph_);
      InitOnlineStream(stream.get());
      return stream;
    }
    std::string hws = hotwords;
    std::replace(hws.begin(), hws.end(), '/', '\n');
    // youmo: EncodeHotwords would dereference a missing BPE encoder
    if (bpe_encoder_ == nullptr) {
      std::string kept;
      int32_t left_out =
          WithoutBpeHotwords(hws, config_.model_config.modeling_unit, &kept);
      // the user's words, not for the log: how many only
      if (left_out > 0) {
        SHERPA_ONNX_LOGE("%d hotwords left out, no BPE encoder for them",
                         left_out);
      }
      hws = std::move(kept);
    }
    std::istringstream is(hws);
    std::vector<std::vector<int32_t>> current;
    std::vector<float> current_scores;
    if (!EncodeHotwords(is, config_.model_config.modeling_unit, sym_,
                        bpe_encoder_.get(), &current, &current_scores)) {
      // youmo: the user's words, not for the log
      SHERPA_ONNX_LOGE("Encode hotwords failed, skipping some");
    }

    int32_t num_default_hws = hotwords_.size();
    int32_t num_hws = current.size();

    current.insert(current.end(), hotwords_.begin(), hotwords_.end());

    if (!current_scores.empty() && !boost_scores_.empty()) {
      current_scores.insert(current_scores.end(), boost_scores_.begin(),
                            boost_scores_.end());
    } else if (!current_scores.empty() && boost_scores_.empty()) {
      current_scores.insert(current_scores.end(), num_default_hws,
                            config_.hotwords_score);
    } else if (current_scores.empty() && !boost_scores_.empty()) {
      current_scores.insert(current_scores.end(), num_hws,
                            config_.hotwords_score);
      current_scores.insert(current_scores.end(), boost_scores_.begin(),
                            boost_scores_.end());
    } else {
      // Do nothing.
    }

    auto context_graph = std::make_shared<ContextGraph>(
        current, config_.hotwords_score, current_scores);
    last_hotwords_ = hotwords;
    last_hotwords_graph_ = context_graph;
    auto stream =
        std::make_unique<OnlineStream>(config_.feat_config, context_graph);
    InitOnlineStream(stream.get());
    return stream;
  }

  std::unique_ptr<OnlineStream> CreateStream(
      const std::string &hotwords, const std::string &blocked) const override {
    auto s = hotwords.empty() ? CreateStream() : CreateStream(hotwords);
    // greedy search reads no block graph, and has no BPE encoder to build one
    if (blocked.empty() || config_.decoding_method != "modified_beam_search") {
      return s;
    }

    std::vector<std::vector<int32_t>> phrases;
    int32_t left_out =
        EncodeBlockedPhrases(blocked, config_.model_config.modeling_unit, sym_,
                             bpe_encoder_.get(), &phrases);
    // the user's words, not for the log: how many only
    if (left_out > 0) {
      SHERPA_ONNX_LOGE("%d blocked phrases left out, not encodable", left_out);
    }
    if (!phrases.empty()) {
      // matched, never scored: the decoder only asks where a phrase ends
      s->SetBlockGraph(std::make_shared<ContextGraph>(phrases, 0.0f));
      // its hyps' block states at the root (the stream is new: nothing else
      // to start again)
      RootGraphStates(s.get(), &s->GetResult().hyps);
    }
    return s;
  }

  bool IsReady(OnlineStream *s) const override {
    return s->GetNumProcessedFrames() + model_->ChunkSize() <
           s->NumFramesReady();
  }

  // Warmping up engine with wp: warm_up count and max-batch-size
  void WarmpUpRecognizer(int32_t warmup, int32_t mbs) const override {
    auto max_batch_size = mbs;
    if (warmup <= 0 || warmup > 100) {
      return;
    }
    int32_t chunk_size = model_->ChunkSize();
    int32_t feature_dim = config_.feat_config.feature_dim;
    std::vector<OnlineTransducerDecoderResult> results(max_batch_size);
    std::vector<float> features_vec(max_batch_size * chunk_size * feature_dim);
    std::vector<std::vector<Ort::Value>> states_vec(max_batch_size);

    auto memory_info =
        Ort::MemoryInfo::CreateCpu(OrtDeviceAllocator, OrtMemTypeDefault);

    std::array<int64_t, 3> x_shape{max_batch_size, chunk_size, feature_dim};

    for (int32_t i = 0; i != max_batch_size; ++i) {
      states_vec[i] = model_->GetEncoderInitStates();
      results[i] = decoder_->GetEmptyResult();
    }

    for (int32_t i = 0; i != warmup; ++i) {
      auto states = model_->StackStates(states_vec);
      Ort::Value x = Ort::Value::CreateTensor(memory_info, features_vec.data(),
                                              features_vec.size(),
                                              x_shape.data(), x_shape.size());
      auto x_copy = Clone(model_->Allocator(), &x);
      auto pair = model_->RunEncoder(std::move(x), std::move(states),
                                     std::move(x_copy));
      decoder_->Decode(std::move(pair.first), &results);
    }
  }

  void DecodeStreams(OnlineStream **ss, int32_t n) const override {
    int32_t chunk_size = model_->ChunkSize();
    int32_t chunk_shift = model_->ChunkShift();

    int32_t feature_dim = ss[0]->FeatureDim();

    std::vector<OnlineTransducerDecoderResult> results(n);
    std::vector<float> features_vec(n * chunk_size * feature_dim);
    std::vector<std::vector<Ort::Value>> states_vec(n);
    std::vector<int64_t> all_processed_frames(n);
    bool has_context_graph = false;

    for (int32_t i = 0; i != n; ++i) {
      // youmo: or a block graph, read by the same Decode()
      if (!has_context_graph &&
          (ss[i]->GetContextGraph() || ss[i]->GetBlockGraph())) {
        has_context_graph = true;
      }

      const auto num_processed_frames = ss[i]->GetNumProcessedFrames();
      std::vector<float> features =
          ss[i]->GetFrames(num_processed_frames, chunk_size);

      // Question: should num_processed_frames include chunk_shift?
      ss[i]->GetNumProcessedFrames() += chunk_shift;

      std::copy(features.begin(), features.end(),
                features_vec.data() + i * chunk_size * feature_dim);

      results[i] = std::move(ss[i]->GetResult());
      states_vec[i] = std::move(ss[i]->GetStates());
      all_processed_frames[i] = num_processed_frames;
    }

    auto memory_info =
        Ort::MemoryInfo::CreateCpu(OrtDeviceAllocator, OrtMemTypeDefault);

    std::array<int64_t, 3> x_shape{n, chunk_size, feature_dim};

    Ort::Value x = Ort::Value::CreateTensor(memory_info, features_vec.data(),
                                            features_vec.size(), x_shape.data(),
                                            x_shape.size());

    std::array<int64_t, 1> processed_frames_shape{
        static_cast<int64_t>(all_processed_frames.size())};

    Ort::Value processed_frames = Ort::Value::CreateTensor(
        memory_info, all_processed_frames.data(), all_processed_frames.size(),
        processed_frames_shape.data(), processed_frames_shape.size());

    auto states = model_->StackStates(states_vec);

    auto pair = model_->RunEncoder(std::move(x), std::move(states),
                                   std::move(processed_frames));

    if (has_context_graph) {
      decoder_->Decode(std::move(pair.first), ss, &results);
    } else {
      decoder_->Decode(std::move(pair.first), &results);
    }

    std::vector<std::vector<Ort::Value>> next_states =
        model_->UnStackStates(pair.second);

    for (int32_t i = 0; i != n; ++i) {
      ss[i]->SetResult(results[i]);
      ss[i]->SetStates(std::move(next_states[i]));
    }
  }

  OnlineRecognizerResult GetResult(OnlineStream *s) const override {
    OnlineTransducerDecoderResult decoder_result = s->GetResult();
    decoder_->StripLeadingBlanks(&decoder_result);

    // TODO(fangjun): Remember to change these constants if needed
    int32_t frame_shift_ms = 10;
    int32_t subsampling_factor = 4;
    auto r = Convert(decoder_result, sym_, frame_shift_ms, subsampling_factor,
                     s->GetCurrentSegment(), s->GetNumFramesSinceStart());
    r.text = ApplyInverseTextNormalization(std::move(r.text));
    r.text = ApplyHomophoneReplacer(std::move(r.text));
    for (auto &text : r.nbest) {  // youmo
      text = ApplyHomophoneReplacer(
          ApplyInverseTextNormalization(std::move(text)));
    }
    return r;
  }

  bool IsEndpoint(OnlineStream *s) const override {
    if (!config_.enable_endpoint) {
      return false;
    }

    int32_t num_processed_frames = s->GetNumProcessedFrames();

    // frame shift is 10 milliseconds
    float frame_shift_in_seconds = 0.01;

    // subsampling factor is 4
    int32_t trailing_silence_frames = s->GetResult().num_trailing_blanks * 4;

    return endpoint_.IsEndpoint(num_processed_frames, trailing_silence_frames,
                                frame_shift_in_seconds);
  }

  void Reset(OnlineStream *s) const override {
    int32_t context_size = model_->ContextSize();

    {
      // segment is incremented only when the last
      // result is not empty, contains non-blanks and longer than context_size)
      const auto &r = s->GetResult();
      if (!r.tokens.empty() && r.tokens.back() != 0 &&
          r.tokens.size() > context_size) {
        s->GetCurrentSegment() += 1;
      }
    }

    auto r = decoder_->GetEmptyResult();
    auto last_result = s->GetResult();

    if (static_cast<int32_t>(last_result.tokens.size()) > context_size) {
      // if last result is not empty, then
      // truncate all last hyps and save as the 'ys' context for next result
      // (the encoder state buffers are kept)
      // youmo: each carried less the best's log prob, so the next segment's
      // n-best scores (divided by its length) are near its own, not ruled by
      // the last one's sum: upstream dividing the last segment's sum by a short
      // length favoured long ones. Not as upstream for the beam: r starts with
      // the blank hyp at 0 (GetEmptyResult), now level with the best carried
      // one instead of far ahead of them all, and a carried hyp whose context
      // is blanks log-adds into it
      double best = -std::numeric_limits<double>::infinity();
      for (const auto &it : last_result.hyps) {
        best = std::max(best, it.second.log_prob);
      }
      for (const auto &it : last_result.hyps) {
        auto h = it.second;
        r.hyps.Add({std::vector<int64_t>(h.ys.end() - context_size, h.ys.end()),
                    h.log_prob - best});
      }

      r.tokens = std::vector<int64_t>(last_result.tokens.end() - context_size,
                                      last_result.tokens.end());
    } else {
      if (config_.reset_encoder) {
        // reset encoder states, use blanks as 'ys' context
        s->SetStates(model_->GetEncoderInitStates());
      }
    }

    // but reset all contextual biasing graph states to root
    RootGraphStates(s, &r.hyps);

    s->SetResult(r);

    // Note: We only update counters. The underlying audio samples
    // are not discarded.
    s->Reset();
  }

 private:
  void InitHotwords() {
    // each line in hotwords_file contains space-separated words

    auto is = OpenInputFile(config_.hotwords_file);
    if (!is) {
      SHERPA_ONNX_LOGE("Open hotwords file failed: %s",
                       config_.hotwords_file.c_str());
      SHERPA_ONNX_EXIT(-1);
    }

    if (!EncodeHotwords(is, config_.model_config.modeling_unit, sym_,
                        bpe_encoder_.get(), &hotwords_, &boost_scores_)) {
      SHERPA_ONNX_LOGE(
          "Failed to encode some hotwords, skip them already, see logs above "
          "for details.");
    }
    hotwords_graph_ = std::make_shared<ContextGraph>(
        hotwords_, config_.hotwords_score, boost_scores_);
  }

  template <typename Manager>
  void InitHotwords(Manager *mgr) {
    // each line in hotwords_file contains space-separated words

    auto buf = ReadFile(mgr, config_.hotwords_file);

    std::istringstream is(std::string(buf.begin(), buf.end()));

    if (!is) {
      SHERPA_ONNX_LOGE("Open hotwords file failed: %s",
                       config_.hotwords_file.c_str());
      SHERPA_ONNX_EXIT(-1);
    }

    if (!EncodeHotwords(is, config_.model_config.modeling_unit, sym_,
                        bpe_encoder_.get(), &hotwords_, &boost_scores_)) {
      SHERPA_ONNX_LOGE(
          "Failed to encode some hotwords, skip them already, see logs above "
          "for details.");
    }
    hotwords_graph_ = std::make_shared<ContextGraph>(
        hotwords_, config_.hotwords_score, boost_scores_);
  }

  void InitHotwordsFromBufStr() {
    // each line in hotwords_file contains space-separated words

    std::istringstream iss(config_.hotwords_buf);
    if (!EncodeHotwords(iss, config_.model_config.modeling_unit, sym_,
                        bpe_encoder_.get(), &hotwords_, &boost_scores_)) {
      SHERPA_ONNX_LOGE(
          "Failed to encode some hotwords, skip them already, see logs above "
          "for details.");
    }
    hotwords_graph_ = std::make_shared<ContextGraph>(
        hotwords_, config_.hotwords_score, boost_scores_);
  }

  void InitOnlineStream(OnlineStream *stream) const {
    auto r = decoder_->GetEmptyResult();
    RootGraphStates(stream, &r.hyps);

    stream->SetResult(r);
    stream->SetStates(model_->GetEncoderInitStates());
  }

 private:
  // the hyps' states in the stream's hotwords graph and (youmo) block graph at
  // their roots: a new stream's, or the next segment's after an endpoint, so a
  // blocked phrase is matched within a segment only
  void RootGraphStates(OnlineStream *s, Hypotheses *hyps) const {
    if (config_.decoding_method != "modified_beam_search") return;
    for (auto it = hyps->begin(); it != hyps->end(); ++it) {
      if (s->GetContextGraph()) {
        it->second.context_state = s->GetContextGraph()->Root();
      }
      if (s->GetBlockGraph()) {
        it->second.block_state = s->GetBlockGraph()->Root();
      }
    }
  }

  // youmo: an LM (online-lm.cc, online-rnn-lm.cc) and whisper features
  // (offline-whisper-model.cc) are not built (lib/sherpa-onnx/README.md)
  void CheckNoLm() const {
    if (!config_.lm_config.model.empty()) {
      SHERPA_ONNX_LOGE("An online LM is not built (youmo).");
      // thrown, not _Exit: the recognizer's making is under SafeJNI
      throw std::invalid_argument("an online LM is not built");
    }
  }

  void CheckNoWhisperFeature() const {
    if (model_->UseWhisperFeature()) {
      SHERPA_ONNX_LOGE("Whisper features are not built (youmo).");
      // a voice pack's model with them: thrown, voice input reports it
      throw std::invalid_argument("whisper features are not built");
    }
  }

  OnlineRecognizerConfig config_;
  std::vector<std::vector<int32_t>> hotwords_;
  std::vector<float> boost_scores_;
  ContextGraphPtr hotwords_graph_;
  std::unique_ptr<ssentencepiece::Ssentencepiece> bpe_encoder_;
  // youmo: the graph CreateStream(hotwords) built last, for the same words
  // again (a stream per utterance, the same words each time)
  mutable std::mutex last_hotwords_mutex_;
  mutable std::string last_hotwords_;
  mutable ContextGraphPtr last_hotwords_graph_;
  std::unique_ptr<OnlineTransducerModel> model_;
  std::unique_ptr<OnlineLM> lm_;  // youmo: always null (CheckNoLm)
  std::unique_ptr<OnlineTransducerDecoder> decoder_;
  SymbolTable sym_;
  Endpoint endpoint_;
  int32_t unk_id_ = -1;
};

}  // namespace sherpa_onnx

#endif  // SHERPA_ONNX_CSRC_ONLINE_RECOGNIZER_TRANSDUCER_IMPL_H_
