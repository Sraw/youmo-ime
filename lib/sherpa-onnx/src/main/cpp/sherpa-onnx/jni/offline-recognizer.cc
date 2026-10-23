// sherpa-onnx/jni/offline-recognizer.cc
//
// Copyright (c)  2024  Xiaomi Corporation

#include "sherpa-onnx/csrc/offline-recognizer.h"

#include <stdlib.h>

#include <memory>

#include "sherpa-onnx/csrc/macros.h"
#include "sherpa-onnx/csrc/text-utils.h"
#include "sherpa-onnx/jni/common.h"

namespace sherpa_onnx {

static OfflineRecognizerConfig GetOfflineConfig(JNIEnv *env, jobject config,
                                                bool *ok) {
  OfflineRecognizerConfig ans;

  jclass cls = env->GetObjectClass(config);
  jfieldID fid;

  SHERPA_ONNX_JNI_READ_STRING(ans.decoding_method, decodingMethod, cls, config);

  SHERPA_ONNX_JNI_READ_INT(ans.max_active_paths, maxActivePaths, cls, config);

  SHERPA_ONNX_JNI_READ_STRING(ans.hotwords_file, hotwordsFile, cls, config);

  SHERPA_ONNX_JNI_READ_FLOAT(ans.hotwords_score, hotwordsScore, cls, config);

  SHERPA_ONNX_JNI_READ_STRING(ans.rule_fsts, ruleFsts, cls, config);

  SHERPA_ONNX_JNI_READ_STRING(ans.rule_fars, ruleFars, cls, config);

  SHERPA_ONNX_JNI_READ_FLOAT(ans.blank_penalty, blankPenalty, cls, config);

  fid = env->GetFieldID(cls, "featConfig",
                        "Lcom/k2fsa/sherpa/onnx/FeatureConfig;");
  jobject feat_config = env->GetObjectField(config, fid);
  jclass feat_config_cls = env->GetObjectClass(feat_config);

  SHERPA_ONNX_JNI_READ_INT(ans.feat_config.sampling_rate, sampleRate,
                           feat_config_cls, feat_config);

  SHERPA_ONNX_JNI_READ_INT(ans.feat_config.feature_dim, featureDim,
                           feat_config_cls, feat_config);

  SHERPA_ONNX_JNI_READ_FLOAT(ans.feat_config.dither, dither, feat_config_cls,
                             feat_config);

  fid = env->GetFieldID(cls, "modelConfig",
                        "Lcom/k2fsa/sherpa/onnx/OfflineModelConfig;");
  jobject model_config = env->GetObjectField(config, fid);
  jclass model_config_cls = env->GetObjectClass(model_config);

  SHERPA_ONNX_JNI_READ_STRING(ans.model_config.tokens, tokens, model_config_cls,
                              model_config);

  SHERPA_ONNX_JNI_READ_INT(ans.model_config.num_threads, numThreads,
                           model_config_cls, model_config);

  SHERPA_ONNX_JNI_READ_BOOL(ans.model_config.debug, debug, model_config_cls,
                            model_config);

  SHERPA_ONNX_JNI_READ_STRING(ans.model_config.provider, provider,
                              model_config_cls, model_config);

  SHERPA_ONNX_JNI_READ_STRING(ans.model_config.model_type, modelType,
                              model_config_cls, model_config);

  SHERPA_ONNX_JNI_READ_STRING(ans.model_config.modeling_unit, modelingUnit,
                              model_config_cls, model_config);

  SHERPA_ONNX_JNI_READ_STRING(ans.model_config.bpe_vocab, bpeVocab,
                              model_config_cls, model_config);

  fid = env->GetFieldID(model_config_cls, "transducer",
                        "Lcom/k2fsa/sherpa/onnx/OfflineTransducerModelConfig;");
  jobject transducer_config = env->GetObjectField(model_config, fid);
  jclass transducer_config_cls = env->GetObjectClass(transducer_config);

  SHERPA_ONNX_JNI_READ_STRING(ans.model_config.transducer.encoder_filename,
                              encoder, transducer_config_cls,
                              transducer_config);

  SHERPA_ONNX_JNI_READ_STRING(ans.model_config.transducer.decoder_filename,
                              decoder, transducer_config_cls,
                              transducer_config);

  SHERPA_ONNX_JNI_READ_STRING(ans.model_config.transducer.joiner_filename,
                              joiner, transducer_config_cls, transducer_config);

  fid = env->GetFieldID(transducer_config_cls, "qnnConfig",
                        "Lcom/k2fsa/sherpa/onnx/QnnConfig;");
  jobject transducer_qnn_config = env->GetObjectField(transducer_config, fid);
  jclass transducer_qnn_config_cls = env->GetObjectClass(transducer_qnn_config);

  SHERPA_ONNX_JNI_READ_STRING(
      ans.model_config.transducer.qnn_config.backend_lib, backendLib,
      transducer_qnn_config_cls, transducer_qnn_config);

  SHERPA_ONNX_JNI_READ_STRING(
      ans.model_config.transducer.qnn_config.context_binary, contextBinary,
      transducer_qnn_config_cls, transducer_qnn_config);

  SHERPA_ONNX_JNI_READ_STRING(
      ans.model_config.transducer.qnn_config.system_lib, systemLib,
      transducer_qnn_config_cls, transducer_qnn_config);

  // homophone replacer config
  fid = env->GetFieldID(cls, "hr",
                        "Lcom/k2fsa/sherpa/onnx/HomophoneReplacerConfig;");
  jobject hr_config = env->GetObjectField(config, fid);
  jclass hr_config_cls = env->GetObjectClass(hr_config);

  SHERPA_ONNX_JNI_READ_STRING(ans.hr.lexicon, lexicon, hr_config_cls,
                              hr_config);

  SHERPA_ONNX_JNI_READ_STRING(ans.hr.rule_fsts, ruleFsts, hr_config_cls,
                              hr_config);

  *ok = true;
  return ans;
}

}  // namespace sherpa_onnx

SHERPA_ONNX_EXTERN_C
JNIEXPORT jlong JNICALL
Java_com_k2fsa_sherpa_onnx_OfflineRecognizer_newFromAsset(JNIEnv *env,
                                                          jobject /*obj*/,
                                                          jobject asset_manager,
                                                          jobject _config) {
#if __ANDROID_API__ >= 9
  AAssetManager *mgr = AAssetManager_fromJava(env, asset_manager);
  if (!mgr) {
    SHERPA_ONNX_LOGE("Failed to get asset manager: %p", mgr);
    return 0;
  }
#endif
  bool ok = false;
  auto config = sherpa_onnx::GetOfflineConfig(env, _config, &ok);

  if (!ok) {
    SHERPA_ONNX_LOGE("Please read the error message carefully");
    return 0;
  }

  if (config.model_config.debug) {
#if __ANDROID_API__
    // logcat truncates long strings, so we split the string into chunks
    auto str_vec = sherpa_onnx::SplitString(config.ToString(), 128);
    for (const auto &s : str_vec) {
      SHERPA_ONNX_LOGE("%s", s.c_str());
    }
#else
    SHERPA_ONNX_LOGE("%s", config.ToString().c_str());
#endif
  }

  auto model = new sherpa_onnx::OfflineRecognizer(
#if __ANDROID_API__ >= 9
      mgr,
#endif
      config);

  return (jlong)model;
}

SHERPA_ONNX_EXTERN_C
JNIEXPORT jlong JNICALL
Java_com_k2fsa_sherpa_onnx_OfflineRecognizer_newFromFile(JNIEnv *env,
                                                         jobject /*obj*/,
                                                         jobject _config) {
  bool ok = false;
  auto config = sherpa_onnx::GetOfflineConfig(env, _config, &ok);

  if (!ok) {
    SHERPA_ONNX_LOGE("Please read the error message carefully");
    return 0;
  }

  if (config.model_config.debug) {
#if __ANDROID_API__
    auto str_vec = sherpa_onnx::SplitString(config.ToString(), 128);
    for (const auto &s : str_vec) {
      SHERPA_ONNX_LOGE("%s", s.c_str());
    }
#else
    SHERPA_ONNX_LOGE("%s", config.ToString().c_str());
#endif
  }

  if (!config.Validate()) {
    SHERPA_ONNX_LOGE("Errors found in config!");
    return 0;
  }

  auto model = new sherpa_onnx::OfflineRecognizer(config);

  return (jlong)model;
}

SHERPA_ONNX_EXTERN_C
JNIEXPORT void JNICALL Java_com_k2fsa_sherpa_onnx_OfflineRecognizer_setConfig(
    JNIEnv *env, jobject /*obj*/, jlong ptr, jobject _config) {
  bool ok = false;
  auto config = sherpa_onnx::GetOfflineConfig(env, _config, &ok);

  if (!ok) {
    SHERPA_ONNX_LOGE("Please read the error message carefully");
    return;
  }

  if (config.model_config.debug) {
    SHERPA_ONNX_LOGE("config:\n%s", config.ToString().c_str());
  }

  auto recognizer = reinterpret_cast<sherpa_onnx::OfflineRecognizer *>(ptr);
  recognizer->SetConfig(config);
}

SHERPA_ONNX_EXTERN_C
JNIEXPORT void JNICALL Java_com_k2fsa_sherpa_onnx_OfflineRecognizer_delete(
    JNIEnv * /*env*/, jobject /*obj*/, jlong ptr) {
  delete reinterpret_cast<sherpa_onnx::OfflineRecognizer *>(ptr);
}

SHERPA_ONNX_EXTERN_C
JNIEXPORT jlong JNICALL
Java_com_k2fsa_sherpa_onnx_OfflineRecognizer_createStream(JNIEnv * /*env*/,
                                                          jobject /*obj*/,
                                                          jlong ptr) {
  auto recognizer = reinterpret_cast<sherpa_onnx::OfflineRecognizer *>(ptr);
  std::unique_ptr<sherpa_onnx::OfflineStream> s = recognizer->CreateStream();

  // The user is responsible to free the returned pointer.
  //
  // See Java_com_k2fsa_sherpa_onnx_OfflineStream_delete() from
  // ./offline-stream.cc
  sherpa_onnx::OfflineStream *p = s.release();
  return (jlong)p;
}

SHERPA_ONNX_EXTERN_C
JNIEXPORT jlong JNICALL
Java_com_k2fsa_sherpa_onnx_OfflineRecognizer_createStreamWithHotwords(
    JNIEnv *env, jobject /*obj*/, jlong ptr, jstring j_hotwords) {
  auto recognizer = reinterpret_cast<sherpa_onnx::OfflineRecognizer *>(ptr);
  if (!j_hotwords) {
    std::unique_ptr<sherpa_onnx::OfflineStream> s = recognizer->CreateStream();
    sherpa_onnx::OfflineStream *p = s.release();
    return (jlong)p;
  }
  const char *utf = env->GetStringUTFChars(j_hotwords, nullptr);
  if (!utf) {
    std::unique_ptr<sherpa_onnx::OfflineStream> s = recognizer->CreateStream();
    sherpa_onnx::OfflineStream *p = s.release();
    return (jlong)p;
  }
  std::string hotwords(utf);
  env->ReleaseStringUTFChars(j_hotwords, utf);
  std::unique_ptr<sherpa_onnx::OfflineStream> s =
      recognizer->CreateStream(hotwords);
  sherpa_onnx::OfflineStream *p = s.release();
  return (jlong)p;
}

// youmo: hotwords and phrases never to recognize, each "/"-separated
SHERPA_ONNX_EXTERN_C
JNIEXPORT jlong JNICALL
Java_com_k2fsa_sherpa_onnx_OfflineRecognizer_createStreamWithBlocked(
    JNIEnv *env, jobject /*obj*/, jlong ptr, jstring j_hotwords,
    jstring j_blocked) {
  return SafeJNI(
      env, "OfflineRecognizer_createStreamWithBlocked",
      [&]() -> jlong {
        auto recognizer =
            reinterpret_cast<sherpa_onnx::OfflineRecognizer *>(ptr);
        auto str = [env](jstring j) {
          std::string s;
          if (!j) return s;
          const char *utf = env->GetStringUTFChars(j, nullptr);
          if (utf) {
            s = utf;
            env->ReleaseStringUTFChars(j, utf);
          }
          return s;
        };
        std::unique_ptr<sherpa_onnx::OfflineStream> s =
            recognizer->CreateStream(str(j_hotwords), str(j_blocked));
        return (jlong)s.release();
      },
      (jlong)0);
}

SHERPA_ONNX_EXTERN_C
JNIEXPORT void JNICALL Java_com_k2fsa_sherpa_onnx_OfflineRecognizer_decode(
    JNIEnv *env, jobject /*obj*/, jlong ptr, jlong stream_ptr) {
  SafeJNI(env, "OfflineRecognizer_decode", [&] {
    if (!ValidatePointer(env, ptr, "OfflineRecognizer_decode",
                         "OfflineRecognizer pointer is null.") ||
        !ValidatePointer(env, stream_ptr, "OfflineRecognizer_decode",
                         "OfflineStream pointer is null.")) {
      return;
    }

    auto recognizer = reinterpret_cast<sherpa_onnx::OfflineRecognizer *>(ptr);
    auto stream = reinterpret_cast<sherpa_onnx::OfflineStream *>(stream_ptr);
    recognizer->DecodeStream(stream);
  });
}

SHERPA_ONNX_EXTERN_C
JNIEXPORT void JNICALL
Java_com_k2fsa_sherpa_onnx_OfflineRecognizer_decodeStreams(
    JNIEnv *env, jobject /*obj*/, jlong ptr, jlongArray stream_ptrs) {
  SafeJNI(env, "OfflineRecognizer_decode_streams", [&] {
    if (!ValidatePointer(env, ptr, "OfflineRecognizer_decode_streams",
                         "OfflineRecognizer pointer is null.")) {
      return;
    }

    auto recognizer = reinterpret_cast<sherpa_onnx::OfflineRecognizer *>(ptr);

    jlong *p = env->GetLongArrayElements(stream_ptrs, nullptr);
    jsize n = env->GetArrayLength(stream_ptrs);

    auto ss = reinterpret_cast<sherpa_onnx::OfflineStream **>(p);
    recognizer->DecodeStreams(ss, n);

    env->ReleaseLongArrayElements(stream_ptrs, p, JNI_ABORT);
  });
}

SHERPA_ONNX_EXTERN_C
JNIEXPORT jobject JNICALL
Java_com_k2fsa_sherpa_onnx_OfflineRecognizer_getResult(JNIEnv *env,
                                                       jobject /*obj*/,
                                                       jlong streamPtr) {
  auto stream = reinterpret_cast<sherpa_onnx::OfflineStream *>(streamPtr);
  sherpa_onnx::OfflineRecognitionResult result = stream->GetResult();

  // 2. Find the Java class and constructor
  jclass cls = env->FindClass("com/k2fsa/sherpa/onnx/OfflineRecognizerResult");
  if (cls == nullptr) {
    SHERPA_ONNX_LOGE("Failed to find class OfflineRecognizerResult");
    return nullptr;
  }
  jmethodID ctor =
      env->GetMethodID(cls, "<init>",
                       "(Ljava/lang/String;[Ljava/lang/String;[FLjava/lang/"
                       "String;Ljava/lang/String;Ljava/lang/String;[F"
                       "[Ljava/lang/String;[F)V");
  jstring jtext = SafeNewStringUTF(env, result.text);

  jclass string_cls = env->FindClass("java/lang/String");
  jobjectArray jtokens =
      env->NewObjectArray(result.tokens.size(), string_cls, nullptr);

  for (size_t i = 0; i < result.tokens.size(); ++i) {
    jstring token_str = SafeNewStringUTF(env, result.tokens[i]);
    env->SetObjectArrayElement(jtokens, i, token_str);
    env->DeleteLocalRef(token_str);
  }

  jfloatArray jtimestamps = env->NewFloatArray(result.timestamps.size());
  env->SetFloatArrayRegion(jtimestamps, 0, result.timestamps.size(),
                           result.timestamps.data());

  jstring jlang = SafeNewStringUTF(env, result.lang);
  jstring jemotion = SafeNewStringUTF(env, result.emotion);
  jstring jevent = SafeNewStringUTF(env, result.event);

  jfloatArray jdurations = env->NewFloatArray(result.durations.size());
  env->SetFloatArrayRegion(jdurations, 0, result.durations.size(),
                           result.durations.data());

  // youmo: the beam's hypotheses, best first, and their scores
  jobjectArray jnbest =
      env->NewObjectArray(result.nbest.size(), string_cls, nullptr);
  env->DeleteLocalRef(string_cls);
  for (size_t i = 0; i < result.nbest.size(); ++i) {
    jstring s = SafeNewStringUTF(env, result.nbest[i]);
    env->SetObjectArrayElement(jnbest, i, s);
    env->DeleteLocalRef(s);
  }
  jfloatArray jnbest_scores = env->NewFloatArray(result.nbest_scores.size());
  env->SetFloatArrayRegion(jnbest_scores, 0, result.nbest_scores.size(),
                           result.nbest_scores.data());

  jobject jresult =
      env->NewObject(cls, ctor, jtext, jtokens, jtimestamps, jlang, jemotion,
                     jevent, jdurations, jnbest, jnbest_scores);
  env->DeleteLocalRef(jnbest);
  env->DeleteLocalRef(jnbest_scores);

  env->DeleteLocalRef(jtext);
  env->DeleteLocalRef(jtokens);
  env->DeleteLocalRef(jtimestamps);
  env->DeleteLocalRef(jlang);
  env->DeleteLocalRef(jemotion);
  env->DeleteLocalRef(jevent);
  env->DeleteLocalRef(jdurations);
  env->DeleteLocalRef(cls);

  return jresult;  // returned object is safe
}

SHERPA_ONNX_EXTERN_C
JNIEXPORT void JNICALL
Java_com_k2fsa_sherpa_onnx_OfflineRecognizer_prependAdspLibraryPath(
    JNIEnv *env, jclass /*cls*/, jstring new_path) {
  const char *p = env->GetStringUTFChars(new_path, nullptr);
  sherpa_onnx::PrependAdspLibraryPath(p);

  env->ReleaseStringUTFChars(new_path, p);
}
