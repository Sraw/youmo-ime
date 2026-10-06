# sherpa-onnx, vendored

The speech recognizer of the voice builds: [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
(k2-fsa, Apache-2.0, `src/main/cpp/LICENSE`), built from source by this module instead of taken as
upstream's AAR, so that it is ours to change.

- **Upstream:** v1.13.8 (11afbd0). Of it, `src/main/cpp` holds the top `CMakeLists.txt`,
  `cmake/`, `LICENSE`, and of `sherpa-onnx/` the `CMakeLists.txt`, `csrc/`, `jni/` and
  `kotlin-api/`, as they were: the commit "Vendor sherpa-onnx v1.13.8, unmodified".
- **Ours:** every later commit touching `src/main/cpp`. `git log -p -- lib/sherpa-onnx/src/main/cpp`
  after that commit is the whole difference from upstream.
- **Built:** by the module's CMake build (`build.gradle.kts`), recognizer, VAD and JNI only, no
  speech synthesis. CMake downloads onnxruntime's Android libraries and the small dependencies
  (kaldi-native-fbank, kaldi-decoder, simple-sentencepiece, eigen, json), each SHA-256 checked by
  its `cmake/*.cmake`. Only the app's `voice` flavor depends on the module.

## What we changed

- **Blocked phrases:** `OfflineRecognizer::CreateStream(hotwords, blocked)` (Kotlin
  `createStream(hotwords, blocked)`): a transducer's modified beam search does not let a hypothesis
  complete any of them (`ContextGraph::CompletingTokens`), so the next best is written instead.
- **N-best:** `OfflineRecognizerResult.nbest` and `nbestScores`, the beam's hypotheses best first
  (in `AsJsonString` too). The app ranks them again with its pinyin model (`VoiceRerank`).

Each change is marked `youmo` in the source. Their C++ unit tests run on this machine with
`lib/sherpa-onnx/host-test.sh`; the app's `VoiceEngineTest` covers them through the JNI.
`lib/sherpa-onnx/host-eval.sh` builds `sherpa-onnx-offline` for this machine, to evaluate the
recognizer on the app's code without a phone.

## Updating upstream

Copy the same paths of the new release over `src/main/cpp` in a commit of its own, then bring our
commits forward onto it (`git log` above lists them).
