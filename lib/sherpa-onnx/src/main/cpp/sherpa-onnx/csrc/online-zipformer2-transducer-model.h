// sherpa-onnx/csrc/online-zipformer2-transducer-model.h
//
// Copyright (c)  2023  Xiaomi Corporation
#ifndef SHERPA_ONNX_CSRC_ONLINE_ZIPFORMER2_TRANSDUCER_MODEL_H_
#define SHERPA_ONNX_CSRC_ONLINE_ZIPFORMER2_TRANSDUCER_MODEL_H_

#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "onnxruntime_cxx_api.h"  // NOLINT
#include "sherpa-onnx/csrc/online-model-config.h"
#include "sherpa-onnx/csrc/online-transducer-model.h"

#if __ANDROID_API__ >= 9
struct AAsset;
struct AAssetManager;
#endif

namespace sherpa_onnx {

class OnlineZipformer2TransducerModel : public OnlineTransducerModel {
 public:
  explicit OnlineZipformer2TransducerModel(const OnlineModelConfig &config);

  template <typename Manager>
  OnlineZipformer2TransducerModel(Manager *mgr,
                                  const OnlineModelConfig &config);

  std::vector<Ort::Value> StackStates(
      const std::vector<std::vector<Ort::Value>> &states) const override;

  std::vector<std::vector<Ort::Value>> UnStackStates(
      const std::vector<Ort::Value> &states) const override;

  std::vector<Ort::Value> GetEncoderInitStates() override;

  void SetFeatureDim(int32_t feature_dim) override {
    feature_dim_ = feature_dim;
  }

  std::pair<Ort::Value, std::vector<Ort::Value>> RunEncoder(
      Ort::Value features, std::vector<Ort::Value> states,
      Ort::Value processed_frames) override;

  Ort::Value RunDecoder(Ort::Value decoder_input) override;

  Ort::Value RunJoiner(Ort::Value encoder_out, Ort::Value decoder_out) override;

  int32_t ContextSize() const override { return context_size_; }

  int32_t ChunkSize() const override { return T_; }

  int32_t ChunkShift() const override { return decode_chunk_len_; }

  int32_t VocabSize() const override { return vocab_size_; }
  OrtAllocator *Allocator() override { return allocator_; }

  bool UseWhisperFeature() const override { return use_whisper_feature_; }

 private:
  // youmo: the session options passed in, for the mapped models' own (Load)
  void InitEncoder(void *model_data, size_t model_data_length,
                   const Ort::SessionOptions &opts);
  void InitDecoder(void *model_data, size_t model_data_length,
                   const Ort::SessionOptions &opts);
  void InitJoiner(void *model_data, size_t model_data_length,
                  const Ort::SessionOptions &opts);

  using Init = void (OnlineZipformer2TransducerModel::*)(
      void *, size_t, const Ort::SessionOptions &);

  template <typename Manager>
  void Load(Manager *mgr, const std::string &filename, Init init,
            const Ort::SessionOptions &opts);

#if __ANDROID_API__ >= 9
  void Load(AAssetManager *mgr, const std::string &filename, Init init,
            const Ort::SessionOptions &opts);
#endif

  // youmo: a model file by its path; an ORT-format one mapped and used in place
  void Load(const std::string &filename, Init init,
            const Ort::SessionOptions &opts);

 private:
  // youmo: a model file mapped (MAP_PRIVATE, read only), unmapped when freed
  struct MappedFile {
    void *data = nullptr;
    size_t size = 0;
    MappedFile() = default;
    MappedFile(const MappedFile &) = delete;
    MappedFile &operator=(const MappedFile &) = delete;
    ~MappedFile();
  };

  Ort::Env env_;
  Ort::SessionOptions encoder_sess_opts_;
  Ort::SessionOptions decoder_sess_opts_;
  Ort::SessionOptions joiner_sess_opts_;

  Ort::AllocatorWithDefaultOptions allocator_;

#if __ANDROID_API__ >= 9
  // youmo: the mapped models, declared before the sessions that read them so
  // that they are closed after them
  using Asset = std::unique_ptr<AAsset, void (*)(AAsset *)>;
  std::vector<Asset> assets_;
#endif
  // youmo: the model files mapped by their paths (a resource pack's), before
  // the sessions for the same reason
  std::vector<std::unique_ptr<MappedFile>> mapped_;

  std::unique_ptr<Ort::Session> encoder_sess_;
  std::unique_ptr<Ort::Session> decoder_sess_;
  std::unique_ptr<Ort::Session> joiner_sess_;

  std::vector<std::string> encoder_input_names_;
  std::vector<const char *> encoder_input_names_ptr_;

  std::vector<std::string> encoder_output_names_;
  std::vector<const char *> encoder_output_names_ptr_;

  std::vector<std::string> decoder_input_names_;
  std::vector<const char *> decoder_input_names_ptr_;

  std::vector<std::string> decoder_output_names_;
  std::vector<const char *> decoder_output_names_ptr_;

  std::vector<std::string> joiner_input_names_;
  std::vector<const char *> joiner_input_names_ptr_;

  std::vector<std::string> joiner_output_names_;
  std::vector<const char *> joiner_output_names_ptr_;

  OnlineModelConfig config_;

  std::vector<int32_t> encoder_dims_;
  std::vector<int32_t> query_head_dims_;
  std::vector<int32_t> value_head_dims_;
  std::vector<int32_t> num_heads_;
  std::vector<int32_t> num_encoder_layers_;
  std::vector<int32_t> cnn_module_kernels_;
  std::vector<int32_t> left_context_len_;

  int32_t T_ = 0;
  int32_t decode_chunk_len_ = 0;

  int32_t context_size_ = 0;
  int32_t vocab_size_ = 0;
  int32_t feature_dim_ = 80;

  // for models from
  // https://github.com/k2-fsa/icefall/blob/master/egs/multi_zh-hans/ASR/RESULTS.md#streaming-with-ctc-head
  bool use_whisper_feature_ = false;
};

}  // namespace sherpa_onnx

#endif  // SHERPA_ONNX_CSRC_ONLINE_ZIPFORMER2_TRANSDUCER_MODEL_H_
