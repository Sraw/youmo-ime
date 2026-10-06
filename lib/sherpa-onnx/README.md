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

## Updating upstream

Copy the same paths of the new release over `src/main/cpp` in a commit of its own, then bring our
commits forward onto it (`git log` above lists them).
